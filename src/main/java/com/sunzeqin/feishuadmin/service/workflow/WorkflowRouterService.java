package com.sunzeqin.feishuadmin.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.pojo.FeishuMessageEvent;
import com.sunzeqin.feishuadmin.pojo.workflow.WorkflowRouteCandidate;
import com.sunzeqin.feishuadmin.pojo.workflow.WorkflowRouteResult;
import com.sunzeqin.feishuadmin.utils.JsonUtils;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流路由服务。
 *
 * <p>作用：在外层 Agent 自由规划之前，先用规则召回可配置工作流，再让大模型只在候选里裁决。
 * Java 最终校验 workflowCode 和置信度，避免大模型编造或误执行工作流。</p>
 *
 * @author sunzeqin
 */
@Service
public class WorkflowRouterService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowRouterService.class);

    // 最多交给 LLM 裁决的候选数量，避免提示词过长。
    private static final int MAX_CANDIDATE_SIZE = 5;

    // 规则召回最低分，低于这个分数说明用户输入和工作流关系太弱。
    private static final int MIN_RULE_SCORE = 20;

    // 自动执行最低置信度，宁可不命中，也不要误执行。
    private static final double MIN_LLM_CONFIDENCE = 0.85;

    // 从“最近12个月 / 近 3 个月”这类文本里提取月份。
    private static final Pattern MONTH_PATTERN = Pattern.compile("(\\d{1,2})\\s*个?月");

    private final JdbcTemplate jdbcTemplate;
    private final JsonUtils jsonUtils;
    private final ChatModel chatModel;

    public WorkflowRouterService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils, FeishuProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtils = jsonUtils;
        this.chatModel = buildChatModel(properties);
    }

    /**
     * 根据用户消息尝试匹配已有工作流。
     *
     * @param event      飞书消息事件
     * @param memoryText 当前会话记忆
     * @return 路由结果
     */
    public WorkflowRouteResult route(FeishuMessageEvent event, String memoryText) {
        if (event == null || event.text() == null || event.text().isBlank()) {
            return WorkflowRouteResult.notMatched("用户文本为空");
        }

        String routingText = routingText(event.text(), memoryText);
        List<WorkflowRouteCandidate> candidates = findCandidates(routingText);
        log.info("[工作流路由] 规则召回完成：消息ID={}，候选数量={}，候选={}",
                event.messageId(), candidates.size(), candidateNames(candidates));

        if (candidates.isEmpty()) {
            return WorkflowRouteResult.notMatched("没有召回到候选工作流");
        }

        if (chatModel == null) {
            WorkflowRouteCandidate first = candidates.get(0);
            if (first.score() >= 60 && uniqueTopCandidate(candidates)) {
                log.info("[工作流路由] LLM未启用，使用高分唯一候选：消息ID={}，工作流={}，规则分={}",
                        event.messageId(), first.workflowCode(), first.score());
                return new WorkflowRouteResult(true, first.workflowCode(), 0.9,
                        "规则高分唯一命中：" + first.workflowName(), defaultArguments(event));
            }
            return WorkflowRouteResult.notMatched("LLM未启用，且没有高分唯一候选");
        }

        WorkflowRouteResult decision = decideByLlm(event, routingText, candidates);
        if (!decision.matched()) {
            return decision;
        }

        WorkflowRouteCandidate matched = findCandidate(candidates, decision.workflowCode());
        if (matched == null) {
            log.warn("[工作流路由] LLM返回了候选外工作流，已拒绝：消息ID={}，workflowCode={}",
                    event.messageId(), decision.workflowCode());
            return WorkflowRouteResult.notMatched("LLM返回了候选外工作流");
        }

        if (decision.confidence() < MIN_LLM_CONFIDENCE) {
            log.info("[工作流路由] 置信度不足，放弃工作流：消息ID={}，workflowCode={}，置信度={}",
                    event.messageId(), decision.workflowCode(), decision.confidence());
            return WorkflowRouteResult.notMatched("工作流匹配置信度不足：" + decision.confidence());
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.putAll(defaultArguments(event));
        if (decision.arguments() != null) {
            arguments.putAll(decision.arguments());
        }

        Map<String, Object> normalizedArguments = normalizeArguments(arguments, event.text());
        if (!normalizedArguments.keySet().equals(arguments.keySet())) {
            log.info("[工作流路由] 入参已归一化：消息ID={}，原始字段={}，归一化字段={}",
                    event.messageId(), arguments.keySet(), normalizedArguments.keySet());
        }

        log.info("[工作流路由] 命中工作流：消息ID={}，workflowCode={}，workflowName={}，置信度={}，原因={}，入参字段={}",
                event.messageId(), matched.workflowCode(), matched.workflowName(),
                decision.confidence(), decision.reason(), normalizedArguments.keySet());

        return new WorkflowRouteResult(true, matched.workflowCode(), decision.confidence(),
                decision.reason(), normalizedArguments);
    }

    private List<WorkflowRouteCandidate> findCandidates(String routingText) {
        List<WorkflowRouteCandidate> all = jdbcTemplate.query("""
                        SELECT workflow_code, workflow_name, domain, risk_level,
                               require_user_confirm, intent_keywords, description
                        FROM workflow_definition
                        WHERE enabled = 1
                        ORDER BY updated_at DESC
                        """,
                (rs, rowNum) -> scoreCandidate(
                        rs.getString("workflow_code"),
                        rs.getString("workflow_name"),
                        rs.getString("domain"),
                        rs.getString("risk_level"),
                        rs.getInt("require_user_confirm") == 1,
                        readStringList(rs.getString("intent_keywords")),
                        rs.getString("description"),
                        routingText));

        log.info("[工作流路由] 数据库工作流定义读取：启用数量={}，匹配文本={}",
                all.size(), routingText);
        for (WorkflowRouteCandidate candidate : all) {
            log.info("[工作流路由] 工作流定义：workflowCode={}，workflowName={}，intentKeywords={}，description={}，规则分数={}，命中原因={}",
                    candidate.workflowCode(), candidate.workflowName(), candidate.intentKeywords(),
                    candidate.description(), candidate.score(), candidate.matchedReasons());
        }

        List<WorkflowRouteCandidate> result = new ArrayList<>();
        for (WorkflowRouteCandidate candidate : all) {
            if (candidate.score() >= MIN_RULE_SCORE) {
                result.add(candidate);
            }
        }

        result.sort(Comparator.comparingInt(WorkflowRouteCandidate::score).reversed());
        log.info("[工作流路由] 规则过滤完成：最低分数={}，候选数量={}，候选={}",
                MIN_RULE_SCORE, result.size(), candidateNames(result));
        if (result.size() > MAX_CANDIDATE_SIZE) {
            return new ArrayList<>(result.subList(0, MAX_CANDIDATE_SIZE));
        }
        return result;
    }

    private WorkflowRouteCandidate scoreCandidate(String workflowCode, String workflowName, String domain,
            String riskLevel, boolean requireUserConfirm, List<String> intentKeywords,
            String description, String routingText) {
        String text = normalize(routingText);
        String name = normalize(workflowName);
        String code = normalize(workflowCode);
        String desc = normalize(description);
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (!name.isBlank() && text.contains(name)) {
            score += 60;
            reasons.add("命中工作流名称");
        }

        if (!code.isBlank() && text.contains(code)) {
            score += 60;
            reasons.add("命中工作流编码");
        }

        int keywordHits = 0;
        for (String keyword : intentKeywords) {
            String item = normalize(keyword);
            if (item.isBlank()) {
                continue;
            }
            if (text.contains(item)) {
                keywordHits++;
                score += item.length() >= 4 ? 24 : 16;
                reasons.add("命中关键词：" + keyword);
            }
        }

        if (keywordHits >= 2) {
            score += 20;
            reasons.add("多关键词组合命中");
        }

        if (!desc.isBlank() && containsAnyLongToken(text, desc)) {
            score += 10;
            reasons.add("说明文本弱命中");
        }

        return new WorkflowRouteCandidate(workflowCode, workflowName, domain, riskLevel,
                requireUserConfirm, score, reasons, intentKeywords, description);
    }

    private WorkflowRouteResult decideByLlm(FeishuMessageEvent event, String routingText,
            List<WorkflowRouteCandidate> candidates) {
        String prompt = """
                你是工作流路由裁决器。

                你只能在候选工作流里选择，不能编造 workflowCode。
                如果用户需求不明确，或者候选工作流不完全符合用户目标，必须返回 matched=false。
                工作流会真的执行，所以宁可不命中，也不要错命中。

                输出必须是 JSON，不要 Markdown，不要解释。
                格式：
                {
                  "matched": true,
                  "workflowCode": "候选里的 workflowCode",
                  "confidence": 0.0到1.0,
                  "reason": "中文原因",
                  "arguments": {}
                }

                用户原文：
                %s

                路由文本：
                %s

                候选工作流：
                %s
                """.formatted(event.text(), routingText, jsonUtils.write(candidates));

        String answer = chatModel.chat(prompt);
        log.debug("[工作流路由] LLM原始输出：消息ID={}，输出={}", event.messageId(), answer);
        return parseDecision(answer);
    }

    private WorkflowRouteResult parseDecision(String answer) {
        JsonNode root = jsonUtils.readTree(extractJson(answer));
        boolean matched = root.path("matched").asBoolean(false);
        String workflowCode = root.path("workflowCode").asText("");
        double confidence = root.path("confidence").asDouble(0);
        String reason = root.path("reason").asText("");
        Map<String, Object> arguments = Map.of();
        if (root.has("arguments") && root.get("arguments").isObject()) {
            arguments = jsonUtils.convertToMap(root.get("arguments"));
        }

        if (!matched) {
            return WorkflowRouteResult.notMatched(reason.isBlank() ? "LLM判断不匹配" : reason);
        }

        return new WorkflowRouteResult(true, workflowCode, confidence, reason, arguments);
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        String value = text.trim();
        if (value.startsWith("```")) {
            int firstLineEnd = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
                value = value.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        return value;
    }

    private WorkflowRouteCandidate findCandidate(List<WorkflowRouteCandidate> candidates, String workflowCode) {
        if (workflowCode == null || workflowCode.isBlank()) {
            return null;
        }
        for (WorkflowRouteCandidate candidate : candidates) {
            if (workflowCode.equals(candidate.workflowCode())) {
                return candidate;
            }
        }
        return null;
    }

    private boolean uniqueTopCandidate(List<WorkflowRouteCandidate> candidates) {
        if (candidates.size() == 1) {
            return true;
        }
        return candidates.get(0).score() - candidates.get(1).score() >= 20;
    }

    private String routingText(String userText, String memoryText) {
        if (continuationText(userText) && memoryText != null && !memoryText.isBlank()) {
            return userText + "\n\n会话记忆：\n" + memoryText;
        }
        return userText;
    }

    private boolean continuationText(String userText) {
        String text = userText == null ? "" : userText.replaceAll("@_user_\\d+", "").trim();
        return text.length() <= 12
                && (text.contains("已授权")
                || text.contains("授权了")
                || text.contains("确认")
                || text.contains("继续")
                || text.contains("可以"));
    }

    private Map<String, Object> defaultArguments(FeishuMessageEvent event) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("sourceChatId", event.chatId());
        arguments.put("originalMessageId", event.messageId());
        arguments.put("senderOpenId", event.openId());
        arguments.put("senderUserId", event.userId());
        arguments.put("userText", event.text());
        return arguments;
    }

    private Map<String, Object> normalizeArguments(Map<String, Object> arguments, String userText) {
        // 模型抽参经常使用 snake_case，这里统一补成工作流模板常用的 camelCase。
        Map<String, Object> result = new LinkedHashMap<>(arguments);
        copyAlias(result, "customerName", "customer_name");
        copyAlias(result, "customerName", "customer");
        copyAlias(result, "customerName", "name");
        copyAlias(result, "timeRange", "time_range");
        if (blankValue(result.get("customerName"))) {
            String customerName = parseCustomerName(userText);
            if (!customerName.isBlank()) {
                result.put("customerName", customerName);
            }
        }

        // months 没有直接给出时，从 timeRange 或用户原文里提取。
        if (blankValue(result.get("months"))) {
            Integer months = parseMonths(result.get("timeRange"));
            if (months == null) {
                months = parseMonths(userText);
            }
            if (months != null) {
                result.put("months", months);
            }
        }
        return result;
    }

    private void copyAlias(Map<String, Object> arguments, String targetKey, String aliasKey) {
        // 目标字段已有值时不覆盖，只在缺失时补别名。
        if (!blankValue(arguments.get(targetKey))) {
            return;
        }
        Object value = arguments.get(aliasKey);
        if (!blankValue(value)) {
            arguments.put(targetKey, value);
        }
    }

    private boolean blankValue(Object value) {
        return value == null || value.toString().isBlank();
    }

    private Integer parseMonths(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        Matcher matcher = MONTH_PATTERN.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        String normalized = normalize(text);
        if (normalized.contains("近一年") || normalized.contains("最近一年") || normalized.contains("过去一年")) {
            return 12;
        }
        if (normalized.contains("半年")) {
            return 6;
        }
        if (normalized.contains("本月") || normalized.contains("这个月")) {
            return 1;
        }
        return null;
    }

    private String parseCustomerName(String userText) {
        if (userText == null || userText.isBlank()) {
            return "";
        }

        String text = userText.replaceAll("@_user_\\d+", "")
                .replace("帮我", "")
                .replace("请", "")
                .trim();

        String[] patterns = {
                ".*?(?:查询|查一下|查下|看一下|看下)(.+?)(?:最近|近|过去|本月|今年|客户订单|订单|消费|买了什么).*",
                ".*?(.+?)(?:最近|近|过去)\\s*\\d{1,2}\\s*个?月.*?(?:客户订单|订单|消费).*",
                ".*?(?:客户|用户|会员)(.+?)(?:的)?(?:客户订单|订单|消费).*"
        };
        for (String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(text);
            if (matcher.matches()) {
                return cleanCustomerName(matcher.group(1));
            }
        }
        return "";
    }

    private String cleanCustomerName(String name) {
        if (name == null) {
            return "";
        }
        String value = name.replaceAll("[，,。.!！?？：:；;\\s]", "")
                .replace("的", "")
                .replace("客户", "")
                .replace("用户", "")
                .replace("会员", "")
                .trim();
        if (value.length() < 2 || value.length() > 20) {
            return "";
        }
        return value;
    }

    private String candidateNames(List<WorkflowRouteCandidate> candidates) {
        List<String> names = new ArrayList<>();
        for (WorkflowRouteCandidate candidate : candidates) {
            names.add(candidate.workflowCode() + ":" + candidate.score());
        }
        return names.toString();
    }

    private boolean containsAnyLongToken(String text, String source) {
        String[] tokens = source.split("[，。；、\\s,.;:：]+");
        for (String token : tokens) {
            String item = normalize(token);
            if (item.length() >= 4 && text.contains(item)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root = jsonUtils.readTree(json);
        List<String> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode item : root) {
                if (item != null && !item.asText("").isBlank()) {
                    result.add(item.asText(""));
                }
            }
        }
        return result;
    }

    private ChatModel buildChatModel(FeishuProperties properties) {
        if (!properties.isLlmEnabled()) {
            return null;
        }
        if (properties.getLlmApiKey() == null || properties.getLlmApiKey().isBlank()) {
            return null;
        }
        return OpenAiChatModel.builder()
                .baseUrl(properties.getLlmBaseUrl())
                .apiKey(properties.getLlmApiKey())
                .modelName(properties.getLlmModelName())
                .temperature(properties.getLlmTemperature())
                .timeout(Duration.ofSeconds(30))
                .build();
    }
}
