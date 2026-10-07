package com.sunzeqin.feishuadmin.service.agent;

import com.sunzeqin.feishuadmin.pojo.FeishuMessageEvent;
import com.sunzeqin.feishuadmin.pojo.agent.AgentDecision;
import com.sunzeqin.feishuadmin.pojo.agent.AgentRunResult;
import com.sunzeqin.feishuadmin.pojo.tool.ToolCall;
import com.sunzeqin.feishuadmin.pojo.tool.ToolResult;
import com.sunzeqin.feishuadmin.pojo.workflow.WorkflowRouteResult;
import com.sunzeqin.feishuadmin.service.ConversationMemoryService;
import com.sunzeqin.feishuadmin.service.tool.ToolRegistryService;
import com.sunzeqin.feishuadmin.service.workflow.WorkflowRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 编排服务。
 *
 * <p>作用：像 Codex 一样执行 plan -> tool -> observe -> plan 的循环。
 * LLM 负责决定下一步，Java 负责执行工具和保存观察结果。</p>
 *
 * @author sunzeqin
 */
@Service
public class AgentOrchestratorService {
    // 当前编排器使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorService.class);

    // 最大循环步数，防止模型无限调用工具。
    private static final int MAX_STEPS = 10;

    // Agent 规划器。
    private final AgentPlannerService planner;

    // 工具注册表。
    private final ToolRegistryService toolRegistry;

    // 会话记忆服务，用来读取和保存用户上下文。
    private final ConversationMemoryService memoryService;

    // 工作流路由服务，用来在通用 Agent 规划前优先匹配可配置工作流。
    private final WorkflowRouterService workflowRouterService;

    public AgentOrchestratorService(AgentPlannerService planner, ToolRegistryService toolRegistry,
            ConversationMemoryService memoryService, WorkflowRouterService workflowRouterService) {
        // 保存 Agent 规划器。
        this.planner = planner;

        // 保存工具注册表。
        this.toolRegistry = toolRegistry;

        // 保存会话记忆服务。
        this.memoryService = memoryService;

        // 保存工作流路由服务。
        this.workflowRouterService = workflowRouterService;
    }

    public AgentRunResult run(FeishuMessageEvent event) {
        Map<String, String> oldContext = MDC.getCopyOfContextMap();
        putMdc("messageId", event.messageId());
        putMdc("chatId", event.chatId());
        putMdc("senderOpenId", event.openId());
        MDC.put("threadId", String.valueOf(Thread.currentThread().getId()));
        try {
            return runInternal(event);
        } finally {
            restoreMdc(oldContext);
        }
    }

    private AgentRunResult runInternal(FeishuMessageEvent event) {
        // 打印 Agent 开始执行日志，方便用 messageId 串起整次请求。
        log.debug("[Agent规划] 开始执行：消息ID={}，会话ID={}，会话类型={}，最大步骤数={}，用户文本={}",
                event.messageId(), event.chatId(), event.chatType(), MAX_STEPS, event.text());

        // 读取当前用户在当前会话里的历史记忆。
        String memoryText = memoryService.readMemoryText(event);
        log.debug("[Agent规划] 会话记忆已注入：消息ID={}，会话类型={}，记忆文本长度={}，是否包含历史摘要={}，是否包含近期对话={}",
                event.messageId(),
                event.chatType(),
                memoryText == null ? 0 : memoryText.length(),
                containsMemorySection(memoryText, "历史摘要："),
                containsMemorySection(memoryText, "近期对话："));

        // 保存当前用户输入，供下一轮对话使用。
        memoryService.saveUserMessage(event);

        // LLM Agent Loop 没启用时，使用本地稳定兜底流程。
        if (!planner.enabled()) {
            // 打印未启用日志。
            log.warn("[Agent规划] 停止执行：消息ID={}，原因=规划器未启用", event.messageId());

            // 没有 LLM 时无法判断应该调用哪个 CLI 业务域。
            AgentRunResult result = new AgentRunResult(false,
                    "⚠️ LLM 规划器未启用，无法判断要调用哪个飞书 CLI 能力。请先启用大模型配置。");
            memoryService.saveAssistantMessage(event, result.reply());
            return result;
        }

        // 保存每一步工具观察结果。
        List<ToolResult> observations = new ArrayList<>();

        // 在通用大模型规划前先尝试命中可配置工作流。
        WorkflowAttempt workflowAttempt = tryRunWorkflow(event, memoryText);
        if (workflowAttempt.result() != null) {
            return workflowAttempt.result();
        }
        boolean workflowRouteChecked = workflowAttempt.routeChecked();

        // 最多执行 MAX_STEPS 轮。
        for (int step = 1; step <= MAX_STEPS; step++) {
            // 打印每一轮开始日志。
            log.debug("[Agent规划] 步骤开始：消息ID={}，步骤={}，已有观察结果数量={}",
                    event.messageId(), step, observations.size());

            // 让 LLM 基于当前 observations 决定下一步。
            AgentDecision decision = planner.decide(event.messageId(), step, event.text(), event.chatId(),
                    memoryText, observations, workflowRouteChecked);

            // 打印当前轮规划结果。
            log.debug("[Agent规划] 步骤决策：消息ID={}，步骤={}，决策类型={}，工具={}，原因={}，最终回复长度={}",
                    event.messageId(),
                    step,
                    decision.type(),
                    decision.toolCall() == null ? "" : decision.toolCall().name(),
                    decision.reason(),
                    decision.finalReply() == null ? 0 : decision.finalReply().length());

            // 如果 LLM 输出最终回复，就结束循环。
            if (!decision.toolCallDecision()) {
                // 最终回复属于链路关键结果，INFO 保留摘要，完整正文放到 DEBUG。
                log.info("[Agent结果] 生成最终回复：消息ID={}，步骤={}，回复长度={}，回复摘要={}",
                        event.messageId(), step, textLength(decision.finalReply()), firstLine(decision.finalReply()));
                log.debug("[Agent结果] 最终回复完整内容：消息ID={}，回复={}",
                        event.messageId(), decision.finalReply());
                AgentRunResult result = new AgentRunResult(true, decision.finalReply());
                memoryService.saveAssistantMessage(event, result.reply());
                return result;
            }

            // 如果 LLM 说要调用工具但没给工具参数，直接结束。
            if (decision.toolCall() == null) {
                // 打印缺少工具调用日志。
                log.warn("[Agent规划] 执行失败：消息ID={}，步骤={}，原因=缺少工具调用参数", event.messageId(), step);
                AgentRunResult result = new AgentRunResult(false, "⚠️ Agent 没有给出可执行工具。");
                memoryService.saveAssistantMessage(event, result.reply());
                return result;
            }

            // 注入当前飞书事件上下文，方便工具引用原消息和@触发人。
            ToolCall toolCall = enrichToolCall(event, decision.toolCall());

            // Java 已完成工作流预检查且没有候选时，禁止模型重复查询 workflow.list。
            // 这里做代码级拦截，不能只依赖提示词约束，避免重复数据库查询。
            if (workflowRouteChecked && "workflow.list".equals(toolCall.name())) {
                log.warn("[工作流路由] 已拦截重复查询：消息ID={}，步骤={}，原因=Java预检查未召回候选工作流",
                        event.messageId(), step);
                ToolResult blockedResult = ToolResult.success(
                        toolCall.name(),
                        "Java 已完成工作流预检查且没有候选，禁止重复查询 workflow.list，请改用电商 MCP 或飞书 CLI",
                        Map.of("count", 0, "blockedByJavaRoute", true, "workflows", List.of()));
                observations.add(blockedResult);
                continue;
            }

            // 打印工具执行前日志。
            log.debug("[工具调用] 准备执行工具：消息ID={}，步骤={}，工具={}，入参={}",
                    event.messageId(), step, toolCall.name(), toolCall.params());

            // 执行工具。
            ToolResult result = toolRegistry.execute(toolCall);

            // 保存工具观察结果。
            observations.add(result);

            // 每个普通 Agent 步骤只保留一条最终结果 INFO，避免规划层和工具层重复刷屏。
            log.info("[工具调用] 步骤结果：消息ID={}，步骤={}，工具={}，success={}，message={}，data={}",
                    event.messageId(), step, result.tool(), result.success(), result.message(), result.data());

            // 工作流执行完成后，如果已经生成最终回复，就直接结束，避免再让 LLM 复述一轮。
            String workflowReply = workflowReplyFromToolResult(result);
            if (!workflowReply.isBlank()) {
                log.info("[工作流结果] 工作流已生成最终回复：消息ID={}，步骤={}，工具={}，回复长度={}，回复摘要={}",
                        event.messageId(), step, result.tool(), workflowReply.length(), firstLine(workflowReply));
                log.debug("[工作流结果] 最终回复完整内容：消息ID={}，回复={}",
                        event.messageId(), workflowReply);
                AgentRunResult runResult = new AgentRunResult(true, workflowReply);
                memoryService.saveAssistantMessage(event, runResult.reply());
                return runResult;
            }

            // 普通工具已经生成最终回复时，也直接结束，避免外层 Agent 再规划一轮导致重复回复和耗时变长。
            String finalReply = finalReplyFromToolResult(result);
            if (!finalReply.isBlank()) {
                log.info("[工具结果] 工具已生成最终回复：消息ID={}，步骤={}，工具={}，回复长度={}，回复摘要={}",
                        event.messageId(), step, result.tool(), finalReply.length(), firstLine(finalReply));
                log.debug("[工具结果] 最终回复完整内容：消息ID={}，回复={}",
                        event.messageId(), finalReply);
                AgentRunResult runResult = new AgentRunResult(result.success(), finalReply);
                memoryService.saveAssistantMessage(event, runResult.reply());
                return runResult;
            }

            // 如果工具已经返回授权链接，直接回复用户，不再交给大模型二次解释，避免误说“不支持授权”。
            String authorizeReply = authorizeReplyFromToolResult(result);
            if (!authorizeReply.isBlank()) {
                String authorizeUrl = authorizeUrlFromToolResult(result);
                log.debug("[工具调用] 授权链接已生成，直接结束流程：消息ID={}，步骤={}，工具={}",
                        event.messageId(), step, result.tool());
                AgentRunResult runResult = new AgentRunResult(true, authorizeReply, authorizeUrl);
                memoryService.saveAssistantMessage(event, runResult.reply());
                return runResult;
            }

            // 工具失败时结束执行，并把原因回复给用户。
            if (!result.success()) {
                // 打印工具失败导致 Agent 结束的日志。
                log.warn("[工具调用] 工具失败导致流程结束：消息ID={}，步骤={}，工具={}，原因={}",
                        event.messageId(), step, result.tool(), result.message());
                AgentRunResult runResult = new AgentRunResult(false, "⚠️ 执行失败\n\n🔎 原因：" + result.message());
                memoryService.saveAssistantMessage(event, runResult.reply());
                return runResult;
            }
        }

        // 超过最大步数仍未结束，返回保护性提示。
        log.warn("[Agent规划] 强制停止：消息ID={}，原因=超过最大步骤数，最大步骤数={}",
                event.messageId(), MAX_STEPS);
        AgentRunResult result = new AgentRunResult(false, "⚠️ 本次任务步骤过多，已停止执行，避免重复操作。");
        memoryService.saveAssistantMessage(event, result.reply());
        return result;
    }

    private String finalReplyFromToolResult(ToolResult result) {
        // 空结果直接返回空字符串。
        if (result == null || result.data() == null || result.data().isEmpty()) {
            return "";
        }

        // 授权链接由 authorizeReplyFromToolResult 单独处理，这里不抢它的分支。
        if (!authorizeUrlFromToolResult(result).isBlank()) {
            return "";
        }

        // 读取工具返回的最终回复。
        Object finalReply = result.data().get("finalReply");
        if (finalReply == null || finalReply.toString().isBlank()) {
            return "";
        }

        // 返回最终回复。
        return finalReply.toString();
    }

    private WorkflowAttempt tryRunWorkflow(FeishuMessageEvent event, String memoryText) {
        // 工作流路由失败不能影响原有 Agent 链路，异常时回退到通用规划。
        WorkflowRouteResult routeResult;
        try {
            routeResult = workflowRouterService.route(event, memoryText);
        } catch (Exception e) {
            log.warn("[工作流路由] 路由异常，回退到外层Agent规划：消息ID={}，错误={}",
                    event.messageId(), e.getMessage());
            return new WorkflowAttempt(null, false);
        }

        // 未命中时走原来的 Agent 规划。
        if (routeResult == null || !routeResult.matched()) {
            log.info("[工作流路由] 未命中工作流，继续外层Agent规划：消息ID={}，原因={}",
                    event.messageId(), routeResult == null ? "路由结果为空" : routeResult.reason());
            return new WorkflowAttempt(null, true);
        }

        // 命中工作流后直接调用 workflow.run，不再让外层 LLM 二次选择。
        Map<String, Object> params = new HashMap<>();
        params.put("workflowCode", routeResult.workflowCode());
        params.put("arguments", routeResult.arguments());
        ToolCall toolCall = enrichToolCall(event, new ToolCall("workflow.run", params));

        log.info("[工作流路由] 准备执行命中工作流：消息ID={}，workflowCode={}，置信度={}，原因={}",
                event.messageId(), routeResult.workflowCode(), routeResult.confidence(), routeResult.reason());

        ToolResult result = toolRegistry.execute(toolCall);
            log.debug("[工作流路由] 工作流工具返回：消息ID={}，workflowCode={}，是否成功={}，说明={}，数据字段={}",
                event.messageId(), routeResult.workflowCode(), result.success(), result.message(), result.data().keySet());

        if (!result.success()) {
            AgentRunResult runResult = new AgentRunResult(false, "⚠️ 执行失败\n\n🔎 原因：" + result.message());
            memoryService.saveAssistantMessage(event, runResult.reply());
            return new WorkflowAttempt(runResult, true);
        }

        String workflowReply = workflowReplyFromToolResult(result);
        if (!workflowReply.isBlank()) {
            AgentRunResult runResult = new AgentRunResult(true, workflowReply);
            memoryService.saveAssistantMessage(event, runResult.reply());
            return new WorkflowAttempt(runResult, true);
        }

        AgentRunResult runResult = new AgentRunResult(true, "✅ 工作流执行完成：" + routeResult.workflowCode());
        memoryService.saveAssistantMessage(event, runResult.reply());
        return new WorkflowAttempt(runResult, true);
    }

    private record WorkflowAttempt(AgentRunResult result, boolean routeChecked) {
    }

    private String authorizeReplyFromToolResult(ToolResult result) {
        // 空结果直接返回空字符串。
        if (result == null || result.data() == null || result.data().isEmpty()) {
            return "";
        }

        // 只有真正包含授权链接时，才直接返回。
        if (authorizeUrlFromToolResult(result).isBlank()) {
            return "";
        }

        // 优先使用工具已经整理好的用户回复。
        Object finalReply = result.data().get("finalReply");
        if (finalReply != null && !finalReply.toString().isBlank()) {
            return finalReply.toString();
        }

        // 没有 finalReply 时组装兜底回复，用户侧不展示冗长 scope，避免飞书消息过长截断授权链接。
        return "需要你授权后才能继续执行。\n\n"
                + "请扫描二维码完成授权。\n\n"
                + "授权完成后，系统会保存到用户表并定时刷新 token。";
    }

    private String workflowReplyFromToolResult(ToolResult result) {
        // 只处理 workflow.run 的最终回复。
        if (result == null || !"workflow.run".equals(result.tool()) || result.data() == null) {
            return "";
        }

        // 读取 workflow.run 返回的 finalReply。
        Object finalReply = result.data().get("finalReply");
        if (finalReply == null || finalReply.toString().isBlank()) {
            return "";
        }

        // 返回最终回复。
        return finalReply.toString();
    }

    private String authorizeUrlFromToolResult(ToolResult result) {
        // 空结果直接返回空字符串。
        if (result == null || result.data() == null || result.data().isEmpty()) {
            return "";
        }

        // 读取授权链接。
        Object authorizeUrl = result.data().get("authorizeUrl");
        if (authorizeUrl == null || authorizeUrl.toString().isBlank()) {
            return "";
        }

        // 返回授权链接。
        return authorizeUrl.toString();
    }

    private ToolCall enrichToolCall(FeishuMessageEvent event, ToolCall toolCall) {
        // 空工具调用直接返回。
        if (toolCall == null) {
            return null;
        }

        // 复制一份参数，避免修改不可变 Map。
        Map<String, Object> params = new HashMap<>(toolCall.params());

        // 注入来源会话 ID，工具层权限校验要用它判断会话是否在白名单里。
        putIfPresent(params, "sourceChatId", event.chatId());

        // 注入原消息 ID，用于卡片或消息 reply 原文。
        putIfPresent(params, "originalMessageId", event.messageId());

        // 注入发送人 open_id，用于工具层权限校验和群聊里 @ 对应的人。
        putIfPresent(params, "senderOpenId", event.openId());

        // 注入发送人 user_id，供个别命令需要 user_id 时使用。
        putIfPresent(params, "senderUserId", event.userId());

        // 所有工具都注入真实事件上下文，模型无法再自己编造群 ID 或调用者身份。
        return new ToolCall(toolCall.name(), params);
    }

    private void putIfPresent(Map<String, Object> params, String key, String value) {
        // 空值不写入：ToolCall 内部使用 Map.copyOf，写入 null 会直接抛异常。
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    private int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    private String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "...";
    }

    private boolean containsMemorySection(String memoryText, String sectionName) {
        // 空记忆直接认为没有命中。
        if (memoryText == null || memoryText.isBlank()) {
            return false;
        }

        // 只判断段落是否存在，避免在 INFO 日志里打印真实历史内容。
        return memoryText.contains(sectionName) && !memoryText.contains(sectionName + "无");
    }

    private void putMdc(String key, String value) {
        // 空值不写入 MDC，避免日志里出现无意义的 null。
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private void restoreMdc(Map<String, String> oldContext) {
        // 业务线程会复用，必须恢复旧 MDC，避免下一条消息串到上一条消息ID。
        if (oldContext == null || oldContext.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(oldContext);
    }

}
