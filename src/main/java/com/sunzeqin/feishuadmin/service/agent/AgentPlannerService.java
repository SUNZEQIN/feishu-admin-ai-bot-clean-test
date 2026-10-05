package com.sunzeqin.feishuadmin.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.pojo.agent.AgentDecision;
import com.sunzeqin.feishuadmin.pojo.tool.ToolCall;
import com.sunzeqin.feishuadmin.pojo.tool.ToolResult;
import com.sunzeqin.feishuadmin.service.tool.ToolRegistryService;
import com.sunzeqin.feishuadmin.utils.JsonUtils;
import com.sunzeqin.feishuadmin.utils.LlmErrorUtils;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 规划服务。
 *
 * <p>作用：每一轮根据用户目标和已有工具观察结果，决定下一步调用哪个工具，
 * 或者直接输出最终回复。</p>
 *
 * @author sunzeqin
 */
@Service
public class AgentPlannerService {
    // 当前规划器使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(AgentPlannerService.class);

    // JSON 工具类，用来解析模型输出。
    private final JsonUtils jsonUtils;

    // 工具注册表，用来把工具说明给模型看。
    private final ToolRegistryService toolRegistry;

    // LangChain4j 聊天模型，未启用时为空。
    private final ChatModel chatModel;

    // 第二层 Skill：业务能力说明，从 resources/skills/business 目录读取。
    // 这一层描述「业务规则」，例如电商需求该走哪条工具链，不描述飞书 CLI 命令细节。
    private final String ecommerceAgentSkill;

    public AgentPlannerService(FeishuProperties properties, JsonUtils jsonUtils, ToolRegistryService toolRegistry) {
        // 保存 JSON 工具类。
        this.jsonUtils = jsonUtils;

        // 保存工具注册表。
        this.toolRegistry = toolRegistry;

        // 创建模型。
        this.chatModel = buildChatModel(properties);

        // 读取电商业务 Skill。
        this.ecommerceAgentSkill = readSkill("skills/business/ecommerce-agent.md");
    }

    public boolean enabled() {
        // 有模型才表示 LLM Agent Loop 可用。
        return chatModel != null;
    }

    public AgentDecision decide(String messageId, int step, String userText, String chatId,
            String memoryText, List<ToolResult> observations) {
        // LLM 没启用时不应该调用这个方法。
        if (chatModel == null) {
            return new AgentDecision("final_answer", "LLM 未启用", null, "LLM 未启用");
        }

        // 构造 Agent 提示词。
        String prompt = buildPrompt(userText, chatId, memoryText, observations);

        // 打印规划输入摘要，排查提示词和 observation 数量。
        log.info("[阶段3 外层Agent规划] 规划输入：消息ID={}，步骤={}，会话ID={}，观察结果数量={}，用户文本={}",
                messageId, step, chatId, observations.size(), userText);

        // 调用模型。
        String answer;
        try {
            answer = chatModel.chat(prompt);
        } catch (Exception e) {
            // 大模型余额不足时，直接返回用户能看懂的中文提示。
            if (LlmErrorUtils.insufficientBalance(e)) {
                log.warn("[阶段3 外层Agent规划] 规划失败：消息ID={}，步骤={}，原因=大模型余额不足", messageId, step);
                return new AgentDecision("final_answer", "大模型余额不足", null,
                        LlmErrorUtils.insufficientBalanceReply());
            }

            // 其它异常继续抛出，由上层统一处理。
            throw e;
        }

        // 打印模型原始输出，方便排查 JSON 格式问题。
        log.debug("[阶段3 外层Agent规划] 模型原始输出：消息ID={}，步骤={}，模型输出={}", messageId, step, answer);

        // 解析模型决策。
        AgentDecision decision = parseDecision(answer);

        // 打印决策日志，方便排查模型下一步要做什么。
        log.info("[阶段3 外层Agent规划] 规划结果：消息ID={}，步骤={}，决策类型={}，工具={}，原因={}",
                messageId,
                step,
                decision.type(),
                decision.toolCall() == null ? "" : decision.toolCall().name(),
                decision.reason());

        // 返回模型决策。
        return decision;
    }

    private ChatModel buildChatModel(FeishuProperties properties) {
        // 没开启 LLM 时不创建模型。
        if (!properties.isLlmEnabled()) {
            return null;
        }

        // 没配置 API Key 时不创建模型。
        if (properties.getLlmApiKey() == null || properties.getLlmApiKey().isBlank()) {
            log.warn("[阶段3 外层Agent规划] 大模型未启用：原因=API Key为空");
            return null;
        }

        // 创建 OpenAI 兼容模型。
        return OpenAiChatModel.builder()
                .baseUrl(properties.getLlmBaseUrl())
                .apiKey(properties.getLlmApiKey())
                .modelName(properties.getLlmModelName())
                .temperature(properties.getLlmTemperature())
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private String buildPrompt(String userText, String chatId, String memoryText, List<ToolResult> observations) {
        // 把历史工具结果转成 JSON 字符串。
        String observationText = jsonUtils.write(observations);

        // 当前业务日期，给模型处理“今天/明天/后天”使用。
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate tomorrow = today.plusDays(1);
        LocalDate dayAfterTomorrow = today.plusDays(2);

        // 返回完整提示词。
        return """
                你是飞书管理员 Agent 的规划器。你只能决定下一步，不要一次性假装完成任务。

                你的工作方式：
                1. 根据用户目标和已有工具结果，选择下一步工具；
                2. 每次最多调用一个工具；
                3. 工具执行结果会作为 observations 再返回给你；
                4. 任务完成后输出 final_answer。

                输出必须是 JSON，不要 Markdown，不要解释。

                工具调用格式：
                {
                  "type": "tool_call",
                  "reason": "为什么要调用这个工具",
                  "tool": {
                    "name": "工具名称",
                    "params": {}
                  },
                  "finalReply": ""
                }

                最终回复格式：
                {
                  "type": "final_answer",
                  "reason": "为什么任务完成或无法继续",
                  "tool": null,
                  "finalReply": "回复给用户的中文文本"
                }

                工具选择规则：
                1. 如果用户目标像常用业务流程，优先调用 workflow.list 查询可配置工作流；命中后调用 workflow.run 执行。
                2. 如果用户目标是电商业务数据查询、分析、复盘、库存、订单、退款、客户画像，且没有合适工作流，再使用电商 MCP 工具。
                3. 如果用户目标涉及飞书内部操作，统一调用 cli.run_skill，不要调用固定 OpenAPI 工具。
                4. 飞书内部操作包括：群聊、消息、云文档、多维表格、日程、会议、审批、考勤、通讯录、云盘、知识库、妙记、任务等。
                5. cli.run_skill 是飞书能力执行器，不是最终回复；它会读取本地 Skill，先查 lark-cli help/schema，再执行 CLI。
                6. 调用 cli.run_skill 时，domain 要按业务选择：群聊和消息用 im，多维表格用 base，云文档用 docs，日程用 calendar，会议用 vc，妙记用 minutes，会议纪要用 note，通讯录用 contact，审批用 approval，考勤/打卡/请假余额/班次用 attendance，云盘/权限/评论用 drive，知识库用 wiki，Markdown 文档用 markdown，思维笔记用 mindnotes，画板用 whiteboard。
                7. 调用 cli.run_skill 时，goal 必须保留用户完整目标，sourceChatId 必须传当前群 chatId；系统会自动补充 originalMessageId、senderOpenId、senderUserId。
                8. 当前群 chatId 就是本次飞书事件所在群。用户在群聊里说“本群”“当前群”“群里”“这个群”，都默认指当前群 chatId。
                9. 不要编造用户 ID、机器人 appId、群 ID、文档 token、表格 token。缺少信息时，优先通过 cli.run_skill 让 lark-cli 查询；确实查不到时再 final_answer 说明原因。
                10. 飞书操作默认走机器人身份。只有用户原话明确说“用我的身份”“以本人身份”“以用户身份”时，goal 里才允许写用户身份；否则不要主动要求 user 授权。
                11. 当前业务时区固定为 Asia/Shanghai；当前日期是 %s，“今天”=%s，“明天”=%s，“后天”=%s。
                12. 用户说“明天下午3点”时，goal 里必须保留为“明天 15:00 Asia/Shanghai”，不要把历史记忆里的旧日期写成绝对日期。
                13. 调用 workflow.run 时，workflowCode 必须来自 workflow.list 结果或用户明确指定；arguments 必须包含用户目标里的关键参数，例如 customerName、months。

                电商 MCP Skill：
                %s

                当前群 chatId：%s

                当前用户在当前会话里的历史记忆：
                %s

                用户目标：%s

                %s

                已有 observations：
                %s
                """.formatted(today, today, tomorrow, dayAfterTomorrow,
                ecommerceAgentSkill, chatId,
                memoryText == null || memoryText.isBlank() ? "无" : memoryText,
                userText, toolRegistry.toolDescriptions(), observationText);
    }

    private String readSkill(String path) {
        try {
            // 从 resources 读取指定 Skill 文档。
            ClassPathResource resource = new ClassPathResource(path);

            // 文件不存在时返回空字符串，避免启动失败。
            if (!resource.exists()) {
                return "";
            }

            // 返回文件内容。
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Skill 读取失败时记录日志并返回空字符串，不影响主流程启动。
            log.warn("[阶段3 外层Agent规划] Skill读取失败：路径={}，错误={}", path, e.getMessage());
            return "";
        }
    }

    private AgentDecision parseDecision(String answer) {
        // 截取模型输出中的 JSON。
        String json = extractJson(answer);

        // 解析 JSON。
        JsonNode root = jsonUtils.readTree(json);

        // 读取类型。
        String type = root.path("type").asText("final_answer");

        // 读取原因。
        String reason = root.path("reason").asText("");

        // 读取最终回复。
        String finalReply = root.path("finalReply").asText("");

        // 读取工具调用。
        ToolCall toolCall = parseToolCall(root.path("tool"));

        // 返回决策对象。
        return new AgentDecision(type, reason, toolCall, finalReply);
    }

    private ToolCall parseToolCall(JsonNode toolNode) {
        // tool 为空或 null 时返回 null。
        if (toolNode == null || toolNode.isMissingNode() || toolNode.isNull()) {
            return null;
        }

        // 读取工具名。
        String name = toolNode.path("name").asText("");

        // 读取 params。
        Map<String, Object> params = jsonUtils.convertToMap(toolNode.path("params"));

        // 返回工具调用对象。
        return new ToolCall(name, params);
    }

    private String extractJson(String answer) {
        // 空回复时返回最终回复 JSON。
        if (answer == null || answer.isBlank()) {
            return "{\"type\":\"final_answer\",\"reason\":\"模型空回复\",\"tool\":null,\"finalReply\":\"模型没有返回内容\"}";
        }

        // 找到第一个左大括号。
        int start = answer.indexOf('{');

        // 找到最后一个右大括号。
        int end = answer.lastIndexOf('}');

        // 如果存在 JSON 片段，截取 JSON。
        if (start >= 0 && end > start) {
            return answer.substring(start, end + 1);
        }

        // 没有 JSON 时返回原文，让 JSON 解析抛出错误。
        return answer;
    }
}
