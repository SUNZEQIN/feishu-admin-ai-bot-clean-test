package com.sunzeqin.feishuadmin.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.pojo.workflow.WorkflowDefinition;
import com.sunzeqin.feishuadmin.pojo.workflow.WorkflowStep;
import com.sunzeqin.feishuadmin.service.EcommerceMcpClientService;
import com.sunzeqin.feishuadmin.service.cli.SkillCliExecutorService;
import com.sunzeqin.feishuadmin.utils.JsonUtils;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流执行服务。
 *
 * <p>作用：把数据库里的 workflow_definition / workflow_step 接入 Agent 工具链。
 * Agent 只需要调用 workflow.list 或 workflow.run，具体步骤由 Java 按配置顺序执行。</p>
 *
 * @author sunzeqin
 */
@Service
public class WorkflowExecutionService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionService.class);

    // 模板变量正则，支持 {{name}} 和 {{name|default:12}}。
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_]*)(?:\\|default:([^}]+))?\\s*}}");

    private final JdbcTemplate jdbcTemplate;
    private final JsonUtils jsonUtils;
    private final EcommerceMcpClientService ecommerceMcpClient;
    private final SkillCliExecutorService skillCliExecutor;
    private final ChatModel chatModel;

    public WorkflowExecutionService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils,
            EcommerceMcpClientService ecommerceMcpClient, SkillCliExecutorService skillCliExecutor,
            FeishuProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtils = jsonUtils;
        this.ecommerceMcpClient = ecommerceMcpClient;
        this.skillCliExecutor = skillCliExecutor;
        this.chatModel = buildChatModel(properties);
    }

    public Map<String, Object> listWorkflows(String keyword) {
        List<WorkflowDefinition> workflows = loadEnabledWorkflows();
        List<Map<String, Object>> matched = new ArrayList<>();

        for (WorkflowDefinition workflow : workflows) {
            if (keyword == null || keyword.isBlank() || matches(workflow, keyword)) {
                matched.add(Map.of(
                        "workflowCode", workflow.workflowCode(),
                        "workflowName", workflow.workflowName(),
                        "domain", workflow.domain(),
                        "riskLevel", workflow.riskLevel(),
                        "requireUserConfirm", workflow.requireUserConfirm(),
                        "intentKeywords", workflow.intentKeywords(),
                        "description", workflow.description()
                ));
            }
        }

        log.info("[阶段10 工作流执行] 查询候选工作流：关键词={}，启用数量={}，命中数量={}",
                keyword, workflows.size(), matched.size());

        return Map.of(
                "keyword", keyword == null ? "" : keyword,
                "count", matched.size(),
                "workflows", matched
        );
    }

    public Map<String, Object> runWorkflow(String workflowCode, Map<String, Object> arguments) {
        WorkflowDefinition workflow = loadWorkflow(workflowCode);
        List<WorkflowStep> steps = loadSteps(workflowCode);

        Map<String, Object> context = new LinkedHashMap<>();
        if (arguments != null) {
            context.putAll(arguments);
        }

        long startMillis = System.currentTimeMillis();
        log.info("[阶段10 工作流执行] 开始执行：工作流编码={}，名称={}，步骤数={}，初始入参字段={}",
                workflow.workflowCode(), workflow.workflowName(), steps.size(), context.keySet());

        List<Map<String, Object>> stepResults = new ArrayList<>();
        String finalReply = "";

        for (WorkflowStep step : steps) {
            long stepStartMillis = System.currentTimeMillis();
            Map<String, Object> input = renderInput(step.inputTemplate(), context);
            log.info("[阶段10 工作流执行] 步骤开始：工作流编码={}，步骤={}，步骤名称={}，执行器类型={}，工具名称={}，入参字段={}，输出变量={}",
                    workflowCode, step.stepNo(), step.stepName(), step.executorType(), step.toolName(),
                    input.keySet(), step.outputKey());

            try {
                Object output = executeStep(step, input, context);
                if (!step.outputKey().isBlank()) {
                    context.put(step.outputKey(), output);
                }
                if ("FEISHU_REPLY".equals(step.executorType())) {
                    finalReply = output == null ? "" : output.toString();
                }

                long costMillis = System.currentTimeMillis() - stepStartMillis;
                log.info("[阶段10 工作流执行] 步骤完成：工作流编码={}，步骤={}，执行器类型={}，工具名称={}，输出变量={}，输出摘要={}，耗时={}ms",
                        workflowCode, step.stepNo(), step.executorType(), step.toolName(), step.outputKey(),
                        summarizeOutput(output), costMillis);

                stepResults.add(Map.of(
                        "stepNo", step.stepNo(),
                        "stepName", step.stepName(),
                        "executorType", step.executorType(),
                        "toolName", step.toolName(),
                        "outputKey", step.outputKey(),
                        "success", true
                ));
            } catch (Exception e) {
                long costMillis = System.currentTimeMillis() - stepStartMillis;
                log.warn("[阶段10 工作流执行] 步骤失败：工作流编码={}，步骤={}，执行器类型={}，工具名称={}，失败策略={}，错误={}，耗时={}ms",
                        workflowCode, step.stepNo(), step.executorType(), step.toolName(), step.onError(),
                        e.getMessage(), costMillis);
                throw new IllegalStateException("工作流步骤执行失败：步骤=" + step.stepNo()
                        + "，名称=" + step.stepName() + "，原因=" + e.getMessage(), e);
            }
        }

        if (finalReply.isBlank()) {
            finalReply = "✅ 工作流执行完成：" + workflow.workflowName();
        }

        long costMillis = System.currentTimeMillis() - startMillis;
        log.info("[阶段10 工作流执行] 执行完成：工作流编码={}，步骤数={}，上下文字段={}，最终回复长度={}，耗时={}ms",
                workflowCode, steps.size(), context.keySet(), finalReply.length(), costMillis);

        return Map.of(
                "workflowCode", workflow.workflowCode(),
                "workflowName", workflow.workflowName(),
                "stepResults", stepResults,
                "contextKeys", new ArrayList<>(context.keySet()),
                "finalReply", finalReply
        );
    }

    private Object executeStep(WorkflowStep step, Map<String, Object> input, Map<String, Object> context) {
        if ("MCP".equals(step.executorType())) {
            return ecommerceMcpClient.callTool(step.toolName(), input);
        }
        if ("CLI".equals(step.executorType())) {
            return executeCliStep(input);
        }
        if ("LLM_SUMMARY".equals(step.executorType())) {
            return executeLlmSummary(input, context);
        }
        if ("FEISHU_REPLY".equals(step.executorType())) {
            return executeReplyStep(input);
        }
        if ("JAVA_TOOL".equals(step.executorType())) {
            throw new IllegalArgumentException("JAVA_TOOL 工作流执行暂未接入：" + step.toolName());
        }
        throw new IllegalArgumentException("不支持的执行器类型：" + step.executorType());
    }

    private Object executeCliStep(Map<String, Object> input) {
        String domain = stringValue(input.get("domain"));
        String goal = stringValue(input.get("goal"));
        String sourceChatId = stringValue(input.get("sourceChatId"));
        String originalMessageId = stringValue(input.get("originalMessageId"));
        String senderOpenId = stringValue(input.get("senderOpenId"));
        String senderUserId = stringValue(input.get("senderUserId"));

        if (domain.isBlank() || goal.isBlank() || sourceChatId.isBlank()) {
            throw new IllegalArgumentException("CLI 步骤缺少 domain、goal 或 sourceChatId");
        }

        return skillCliExecutor.runSkill(domain, goal, sourceChatId, originalMessageId, senderOpenId, senderUserId);
    }

    private String executeLlmSummary(Map<String, Object> input, Map<String, Object> context) {
        if (chatModel == null) {
            throw new IllegalStateException("LLM 未启用，无法执行 LLM_SUMMARY 步骤");
        }

        String prompt = """
                你是工作流中的数据总结步骤。请只基于输入数据总结，不要编造不存在的数据。

                输入参数：
                %s

                当前上下文字段：
                %s

                请输出简洁中文结果，适合直接回复给飞书用户。
                """.formatted(jsonUtils.write(input), context.keySet());

        String summary = chatModel.chat(prompt);
        return summary == null ? "" : summary.trim();
    }

    private String executeReplyStep(Map<String, Object> input) {
        String content = stringValue(input.get("content"));
        if (content.isBlank()) {
            content = stringValue(input.get("text"));
        }

        String title = stringValue(input.get("title"));
        if (!title.isBlank()) {
            return "✅ " + title + "\n\n" + content;
        }
        return content.isBlank() ? "✅ 工作流执行完成。" : content;
    }

    private Map<String, Object> renderInput(Map<String, Object> template, Map<String, Object> context) {
        if (template == null || template.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            result.put(entry.getKey(), renderValue(entry.getValue(), context));
        }
        return result;
    }

    private Object renderValue(Object value, Map<String, Object> context) {
        if (!(value instanceof String text)) {
            return value;
        }

        Matcher exactMatcher = TEMPLATE_PATTERN.matcher(text.trim());
        if (exactMatcher.matches()) {
            Object resolved = resolveValue(exactMatcher.group(1), exactMatcher.group(2), context);
            return resolved == null ? "" : resolved;
        }

        Matcher matcher = TEMPLATE_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            Object resolved = resolveValue(matcher.group(1), matcher.group(2), context);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolved == null ? "" : resolved.toString()));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private Object resolveValue(String key, String defaultValue, Map<String, Object> context) {
        Object value = context.get(key);
        if (value != null && !value.toString().isBlank()) {
            return value;
        }
        if (defaultValue != null) {
            return defaultValue.trim();
        }
        return "";
    }

    private WorkflowDefinition loadWorkflow(String workflowCode) {
        List<WorkflowDefinition> workflows = jdbcTemplate.query("""
                        SELECT workflow_code, workflow_name, domain, enabled, risk_level,
                               require_user_confirm, intent_keywords, description
                        FROM workflow_definition
                        WHERE workflow_code = ? AND enabled = 1
                        """,
                (rs, rowNum) -> mapWorkflow(rs.getString("workflow_code"),
                        rs.getString("workflow_name"),
                        rs.getString("domain"),
                        rs.getInt("enabled") == 1,
                        rs.getString("risk_level"),
                        rs.getInt("require_user_confirm") == 1,
                        rs.getString("intent_keywords"),
                        rs.getString("description")),
                workflowCode);

        if (workflows.isEmpty()) {
            throw new IllegalArgumentException("未找到启用的工作流：" + workflowCode);
        }
        return workflows.get(0);
    }

    private List<WorkflowDefinition> loadEnabledWorkflows() {
        return jdbcTemplate.query("""
                        SELECT workflow_code, workflow_name, domain, enabled, risk_level,
                               require_user_confirm, intent_keywords, description
                        FROM workflow_definition
                        WHERE enabled = 1
                        ORDER BY updated_at DESC
                        """,
                (rs, rowNum) -> mapWorkflow(rs.getString("workflow_code"),
                        rs.getString("workflow_name"),
                        rs.getString("domain"),
                        rs.getInt("enabled") == 1,
                        rs.getString("risk_level"),
                        rs.getInt("require_user_confirm") == 1,
                        rs.getString("intent_keywords"),
                        rs.getString("description")));
    }

    private List<WorkflowStep> loadSteps(String workflowCode) {
        List<WorkflowStep> steps = jdbcTemplate.query("""
                        SELECT workflow_code, step_no, step_name, executor_type,
                               tool_name, input_template, output_key, on_error
                        FROM workflow_step
                        WHERE workflow_code = ?
                        ORDER BY step_no ASC
                        """,
                (rs, rowNum) -> new WorkflowStep(
                        rs.getString("workflow_code"),
                        rs.getInt("step_no"),
                        rs.getString("step_name"),
                        rs.getString("executor_type"),
                        rs.getString("tool_name"),
                        readMap(rs.getString("input_template")),
                        rs.getString("output_key"),
                        rs.getString("on_error")),
                workflowCode);

        if (steps.isEmpty()) {
            throw new IllegalArgumentException("工作流没有步骤：" + workflowCode);
        }
        return steps;
    }

    private WorkflowDefinition mapWorkflow(String workflowCode, String workflowName, String domain,
            boolean enabled, String riskLevel, boolean requireUserConfirm,
            String intentKeywordsJson, String description) {
        List<String> intentKeywords = readStringList(intentKeywordsJson);
        return new WorkflowDefinition(workflowCode, workflowName, domain, enabled,
                riskLevel, requireUserConfirm, intentKeywords, description);
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

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return jsonUtils.convertToMap(jsonUtils.readTree(json));
    }

    private boolean matches(WorkflowDefinition workflow, String keyword) {
        String text = keyword == null ? "" : keyword;
        if (workflow.workflowCode().contains(text)
                || workflow.workflowName().contains(text)
                || workflow.description().contains(text)) {
            return true;
        }
        for (String item : workflow.intentKeywords()) {
            if (text.contains(item) || item.contains(text)) {
                return true;
            }
        }
        return false;
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

    private String summarizeOutput(Object output) {
        if (output == null) {
            return "空";
        }
        if (output instanceof Map<?, ?> map) {
            return "Map字段=" + map.keySet();
        }
        if (output instanceof List<?> list) {
            return "List数量=" + list.size();
        }

        String text = output.toString().replaceAll("\\s+", " ").trim();
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
