package com.sunzeqin.feishuadmin.service.tool;

import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.pojo.tool.ToolCall;
import com.sunzeqin.feishuadmin.pojo.tool.ToolResult;
import com.sunzeqin.feishuadmin.service.EcommerceMcpClientService;
import com.sunzeqin.feishuadmin.service.FeishuUserScopeMappingService;
import com.sunzeqin.feishuadmin.service.cli.SkillCliExecutorService;
import com.sunzeqin.feishuadmin.service.workflow.WorkflowExecutionService;
import com.sunzeqin.feishuadmin.utils.LlmErrorUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具注册与执行服务。
 *
 * <p>作用：集中管理 Agent 可以调用的工具。LLM 只输出工具名和参数，
 * 真实执行统一在这里完成，避免模型直接碰飞书 OpenAPI。</p>
 *
 * <p>执行前统一做三件事：工具白名单校验、调用者权限校验、超时保护。</p>
 *
 * @author sunzeqin
 */
@Service
public class ToolRegistryService {
    // 当前工具注册表使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(ToolRegistryService.class);

    // 当前系统允许执行的全部工具名，未知工具一律拒绝，防止模型编造工具调用。
    private static final Set<String> KNOWN_TOOLS = Set.of(
            "cli.run_skill",
            "feishu.scope_for_domain",
            "ecommerce.list_tools",
            "ecommerce.call_tool",
            "workflow.list",
            "workflow.run");

    // 从工具说明文本里提取工具名的正则，用于启动自检说明与执行是否一致。
    private static final Pattern TOOL_NAME_IN_DESCRIPTION = Pattern.compile("(?m)^\\s*\\d+\\.\\s*([a-z][a-z0-9_.]+)\\s*$");

    // Skill + CLI 执行服务，飞书相关能力统一由它处理。
    private final SkillCliExecutorService skillCliExecutor;

    // 电商 MCP 客户端服务，负责调用独立电商项目。
    private final EcommerceMcpClientService ecommerceMcpClient;

    // 飞书用户身份 scope 映射服务，负责查询业务域需要的授权范围。
    private final FeishuUserScopeMappingService scopeMappingService;

    // 工作流执行服务，负责查询和执行数据库里的可配置工作流。
    private final WorkflowExecutionService workflowExecutionService;

    // 工具调用权限校验服务，负责判断调用者和会话是否有权限。
    private final ToolPermissionService toolPermissionService;

    // 飞书配置，用来读取工具超时时间。
    private final FeishuProperties properties;

    // 工具执行线程池：工具调用放在独立线程里执行，主线程只负责等待超时。
    private final ExecutorService toolExecutor;

    public ToolRegistryService(SkillCliExecutorService skillCliExecutor, EcommerceMcpClientService ecommerceMcpClient,
            FeishuUserScopeMappingService scopeMappingService, WorkflowExecutionService workflowExecutionService,
            ToolPermissionService toolPermissionService, FeishuProperties properties) {
        // 保存 Skill + CLI 执行服务。
        this.skillCliExecutor = skillCliExecutor;

        // 保存电商 MCP 客户端服务。
        this.ecommerceMcpClient = ecommerceMcpClient;

        // 保存用户身份 scope 映射服务。
        this.scopeMappingService = scopeMappingService;

        // 保存工作流执行服务。
        this.workflowExecutionService = workflowExecutionService;

        // 保存权限校验服务。
        this.toolPermissionService = toolPermissionService;

        // 保存飞书配置。
        this.properties = properties;

        // 创建工具执行线程池。线程数固定且带名字，方便排查卡在哪个工具上。
        this.toolExecutor = Executors.newFixedThreadPool(Math.max(2, properties.getToolExecutorThreads()),
                namedDaemonFactory());
    }

    /**
     * 启动自检：工具说明文本和执行白名单必须完全一致。
     *
     * <p>工具说明是给大模型看的，执行白名单是真正能跑的工具。两边一旦漂移，
     * 就会出现“模型看得到但执行不了”或“能执行但模型不知道”的隐性故障，
     * 所以启动时直接对账并打日志。</p>
     */
    @PostConstruct
    void verifyToolCatalog() {
        // 从工具说明里解析出工具名。
        Set<String> described = new LinkedHashSet<>();
        Matcher matcher = TOOL_NAME_IN_DESCRIPTION.matcher(toolDescriptions());
        while (matcher.find()) {
            described.add(matcher.group(1));
        }

        // 只在说明里、不在白名单里的工具：模型可能选到但执行不了。
        Set<String> describeOnly = new LinkedHashSet<>(described);
        describeOnly.removeAll(KNOWN_TOOLS);

        // 只在白名单里、没写进说明的工具：模型不知道能用。
        Set<String> dispatchOnly = new LinkedHashSet<>(KNOWN_TOOLS);
        dispatchOnly.removeAll(described);

        // 两边一致时打印一条 INFO 即可。
        if (describeOnly.isEmpty() && dispatchOnly.isEmpty()) {
            log.info("[阶段4 工具调用] 工具清单自检通过：工具数量={}，工具={}", described.size(), described);
            return;
        }

        // 不一致时打 ERROR，提醒维护者补齐，避免线上出现难排查的“工具不存在”。
        log.error("[阶段4 工具调用] 工具清单不一致：仅写在说明里={}，仅可执行但未写说明={}", describeOnly, dispatchOnly);
    }

    /**
     * 给大模型看的工具清单。
     *
     * @return 工具说明文本
     */
    public String toolDescriptions() {
        // 返回给 LLM 看的工具清单，LLM 只能从这些工具里选择下一步。
        return """
                可用工具：
                1. cli.run_skill
                   作用：统一执行飞书相关能力，包含群聊、消息、文档、多维表格、日程、会议、审批、通讯录等。
                   参数：domain, goal, sourceChatId, originalMessageId, senderOpenId, senderUserId。
                   domain 只能是 im、base、docs、calendar、vc、minutes、note、contact、approval、attendance、drive、wiki、markdown、mindnotes、whiteboard。
                   goal 是用户原始目标的完整中文描述。
                   sourceChatId 是当前飞书事件所在群或会话 ID。
                   originalMessageId 是用户原消息 ID，发卡片或消息时优先引用这条原文。
                   senderOpenId 是触发人的 open_id，群聊回复时优先 @ 这个人。
                   注意：飞书内部操作都走这个工具，不要再调用固定 OpenAPI 工具。

                2. feishu.scope_for_domain
                   作用：查询某个飞书业务域在用户身份下需要申请哪些 OAuth scope。
                   参数：domain。
                   domain 例如 im、base、docs、calendar、vc、contact、approval、attendance、drive、wiki、minutes。
                   用途：当用户明确要求“用本人身份 / 以用户身份”执行飞书操作时，可先查询对应模块 scope。

                3. ecommerce.list_tools
                   作用：查询电商 MCP 服务可用工具。
                   参数：无。
                   用途：当用户提出电商业务需求，但你不确定具体工具名时，先调用它。

                4. ecommerce.call_tool
                   作用：调用电商 MCP 服务里的具体业务工具。
                   参数：toolName, arguments。
                   toolName 例如 ecommerce.query_top_products、ecommerce.query_low_inventory、ecommerce.query_customer_orders。
                   arguments 是电商工具入参，例如 limit、threshold、customerName、months。
                   注意：电商数据分析、订单、商品、库存、退款、客户画像、活动复盘需求，应优先调用这个工具拿真实结构化数据。

                5. workflow.list
                   作用：查询数据库里已启用的可配置工作流。
                   参数：keyword。
                   用途：当用户需求像常用业务流程，但你不确定 workflowCode 时，先调用它查候选。

                6. workflow.run
                   作用：执行数据库里配置好的工作流，Java 会按 workflow_step 顺序执行 MCP、CLI、LLM_SUMMARY、FEISHU_REPLY。
                   参数：workflowCode, arguments。
                   workflowCode 必须来自 workflow.list 返回或用户明确指定。
                   arguments 是工作流初始参数，例如 customerName、months、sourceChatId、originalMessageId、senderOpenId。
                   注意：工作流执行日志会打印每一步映射到哪个执行器和工具。
                """;
    }

    /**
     * 执行一次工具调用。
     *
     * @param call 工具调用
     * @return 工具结果，任何异常都转成 failed，让 Agent 继续决策
     */
    public ToolResult execute(ToolCall call) {
        // 打印工具调用入参，方便排查 Agent 到底让系统做了什么。
        log.info("[阶段4 工具调用] 开始：工具名称={}，入参={}", call.name(), call.params());

        // 工具名为空时无法分发。
        if (call.name().isBlank()) {
            ToolResult result = ToolResult.failed(call.name(), "工具名称为空，已拒绝执行");
            logResult(result);
            return result;
        }

        // 第一步：白名单校验。不是注册过的工具一律拒绝，防止模型编造工具名。
        if (!KNOWN_TOOLS.contains(call.name())) {
            ToolResult result = ToolResult.failed(call.name(), "未知工具：" + call.name());
            logResult(result);
            return result;
        }

        // 第二步：权限校验。判断调用者和会话是否有权限执行。
        ToolPermissionService.Decision permission = toolPermissionService.check(call);
        if (!permission.allowed()) {
            ToolResult result = ToolResult.failed(call.name(), permission.reason());
            logResult(result);
            return result;
        }

        // 第三步：带超时执行。工具卡住时不能让 Agent 循环一起挂死。
        try {
            ToolResult result = executeWithTimeout(call);
            logResult(result);
            return result;
        } catch (Exception e) {
            // 工具执行异常时，返回失败结果给 Agent 观察。
            log.warn("[阶段4 工具调用] 异常：工具名称={}，错误={}", call.name(), e.getMessage());

            // 大模型余额不足时返回中文业务提示，不把底层 JSON 直接抛给用户。
            String message = e.getMessage();
            if (LlmErrorUtils.insufficientBalance(e)) {
                message = LlmErrorUtils.insufficientBalanceReply();
            }

            // 空消息兜底成类名，避免用户只看到 null。
            ToolResult result = ToolResult.failed(call.name(),
                    message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
            logResult(result);
            return result;
        }
    }

    private ToolResult executeWithTimeout(ToolCall call) throws Exception {
        // 读取配置的超时时间，最小 1 秒。
        int timeoutSeconds = Math.max(1, properties.getToolTimeoutSeconds());

        // 工具调用会切到独立线程，提前复制 MDC，保证 CLI / 工作流 / MCP 日志能按 messageId 串起来。
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        // 把工具执行交给独立线程池，主线程只负责等待。
        Future<ToolResult> future;
        try {
            future = toolExecutor.submit(() -> dispatchWithMdc(call, mdcContext));
        } catch (RejectedExecutionException e) {
            // 线程池已满时直接失败，不再阻塞 Agent 循环。
            return ToolResult.failed(call.name(), "工具执行队列已满，请稍后再试");
        }

        try {
            // 等待工具执行完成。
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 超时后取消任务并返回可读原因，让 Agent 决定是否重试或换方案。
            future.cancel(true);
            log.warn("[阶段4 工具调用] 执行超时：工具名称={}，超时时间={}秒", call.name(), timeoutSeconds);
            return ToolResult.failed(call.name(),
                    "工具执行超时：超过 " + timeoutSeconds + " 秒未返回，已中断本次调用");
        } catch (InterruptedException e) {
            // 恢复中断标记，避免吞掉线程中断信号。
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.failed(call.name(), "工具执行被中断");
        } catch (ExecutionException e) {
            // 解包真实异常，交给外层统一翻译成用户可读的失败结果。
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        }
    }

    private ToolResult dispatchWithMdc(ToolCall call, Map<String, String> mdcContext) {
        // 保存工具线程原来的 MDC，执行结束后恢复，避免线程池复用导致日志串号。
        Map<String, String> oldContext = MDC.getCopyOfContextMap();
        try {
            if (mdcContext == null || mdcContext.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(mdcContext);
            }
            // 工具执行已经切换线程，记录真正执行 CLI / MCP 的线程 ID。
            MDC.put("threadId", String.valueOf(Thread.currentThread().getId()));
            return dispatch(call);
        } finally {
            if (oldContext == null || oldContext.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(oldContext);
            }
        }
    }

    private ToolResult dispatch(ToolCall call) {
        // 根据工具名称分发到 Skill + CLI 飞书统一入口。
        if ("cli.run_skill".equals(call.name())) {
            return runSkill(call);
        }

        // 根据工具名称分发到飞书用户身份 scope 映射查询。
        if ("feishu.scope_for_domain".equals(call.name())) {
            return scopeForDomain(call);
        }

        // 根据工具名称分发到电商 MCP 工具列表。
        if ("ecommerce.list_tools".equals(call.name())) {
            return listEcommerceTools(call);
        }

        // 根据工具名称分发到电商 MCP 工具调用。
        if ("ecommerce.call_tool".equals(call.name())) {
            return callEcommerceTool(call);
        }

        // 根据工具名称分发到可配置工作流列表。
        if ("workflow.list".equals(call.name())) {
            return listWorkflows(call);
        }

        // 根据工具名称分发到可配置工作流执行。
        if ("workflow.run".equals(call.name())) {
            return runWorkflow(call);
        }

        // 理论上不会走到这里：execute 已经用白名单挡过一次。
        return ToolResult.failed(call.name(), "未知工具：" + call.name());
    }

    private void logResult(ToolResult result) {
        // INFO 只打印结果摘要，完整数据放到 DEBUG，避免一屏日志被大 JSON 淹没。
        log.info("[阶段4 工具调用] 结果摘要：工具名称={}，是否成功={}，说明={}，数据字段={}",
                result.tool(), result.success(), result.message(), result.data().keySet());
        log.debug("[阶段4 工具调用] 结果完整数据：工具名称={}，数据={}", result.tool(), result.data());
    }

    private ToolResult runSkill(ToolCall call) {
        // 从参数里读取业务域。
        String domain = stringParam(call, "domain");

        // 从参数里读取用户目标。
        String goal = stringParam(call, "goal");

        // 从参数里读取来源会话 ID。
        String sourceChatId = stringParam(call, "sourceChatId");

        // 从参数里读取原消息 ID。
        String originalMessageId = stringParam(call, "originalMessageId");

        // 从参数里读取发送人 open_id。
        String senderOpenId = stringParam(call, "senderOpenId");

        // 从参数里读取发送人 user_id。
        String senderUserId = stringParam(call, "senderUserId");

        // CLI 业务域不能为空。
        if (domain.isBlank()) {
            return ToolResult.failed(call.name(), "cli.run_skill 缺少 domain 参数");
        }

        // 用户目标不能为空。
        if (goal.isBlank()) {
            return ToolResult.failed(call.name(), "cli.run_skill 缺少 goal 参数");
        }

        // 来源会话不能为空，Skill 里“本群/当前群”都要靠它解析。
        if (sourceChatId.isBlank()) {
            return ToolResult.failed(call.name(), "cli.run_skill 缺少 sourceChatId 参数");
        }

        // 调用 Skill + CLI 执行器。
        Map<String, Object> data = skillCliExecutor.runSkill(domain, goal, sourceChatId,
                originalMessageId, senderOpenId, senderUserId);

        // 返回执行结果。
        return ToolResult.success(call.name(), "Skill + CLI 执行完成", data);
    }

    private ToolResult listEcommerceTools(ToolCall call) {
        // 调用电商 MCP 查询工具列表。
        Map<String, Object> data = ecommerceMcpClient.listTools();

        // 返回工具列表结果。
        return ToolResult.success(call.name(), "查询电商 MCP 工具列表成功", data);
    }

    private ToolResult scopeForDomain(ToolCall call) {
        // 从参数里读取业务域。
        String domain = stringParam(call, "domain");

        // 业务域不能为空。
        if (domain.isBlank()) {
            return ToolResult.failed(call.name(), "feishu.scope_for_domain 缺少 domain 参数");
        }

        // 查询业务域对应的用户身份 scope。
        Map<String, Object> data = scopeMappingService.scopeToolResult(domain);

        // 返回查询结果。
        return ToolResult.success(call.name(), "查询飞书用户身份scope成功", data);
    }

    private ToolResult callEcommerceTool(ToolCall call) {
        // 读取电商工具名称。
        String toolName = stringParam(call, "toolName");

        // 工具名称不能为空。
        if (toolName.isBlank()) {
            return ToolResult.failed(call.name(), "ecommerce.call_tool 缺少 toolName 参数");
        }

        // 读取电商工具参数。
        Map<String, Object> arguments = mapParam(call, "arguments");

        // 调用电商 MCP 工具。
        Map<String, Object> data = ecommerceMcpClient.callTool(toolName, arguments);

        // 返回调用结果。
        return ToolResult.success(call.name(), "调用电商 MCP 工具完成", data);
    }

    private ToolResult listWorkflows(ToolCall call) {
        // 读取关键词。
        String keyword = stringParam(call, "keyword");

        // 查询候选工作流。
        Map<String, Object> data = workflowExecutionService.listWorkflows(keyword);

        // 返回查询结果。
        return ToolResult.success(call.name(), "查询可配置工作流成功", data);
    }

    private ToolResult runWorkflow(ToolCall call) {
        // 读取工作流编码。
        String workflowCode = stringParam(call, "workflowCode");
        if (workflowCode.isBlank()) {
            return ToolResult.failed(call.name(), "workflow.run 缺少 workflowCode 参数");
        }

        // 读取模型传入的工作流参数。
        Map<String, Object> arguments = mapParam(call, "arguments");
        java.util.HashMap<String, Object> mergedArguments = new java.util.HashMap<>(arguments);

        // 合并真实飞书事件上下文，避免模型自己编造这些关键字段。
        copyIfPresent(call, mergedArguments, "sourceChatId");
        copyIfPresent(call, mergedArguments, "originalMessageId");
        copyIfPresent(call, mergedArguments, "senderOpenId");
        copyIfPresent(call, mergedArguments, "senderUserId");

        // 执行工作流。
        Map<String, Object> data = workflowExecutionService.runWorkflow(workflowCode, mergedArguments);

        // 返回执行结果。
        return ToolResult.success(call.name(), "可配置工作流执行完成", data);
    }

    private String stringParam(ToolCall call, String key) {
        // 从参数 Map 里取值。
        Object value = call.params().get(key);

        // 空值返回空字符串。
        if (value == null) {
            return "";
        }

        // 其它值统一转成字符串。
        return value.toString();
    }

    private Map<String, Object> mapParam(ToolCall call, String key) {
        // 从参数 Map 里取值。
        Object value = call.params().get(key);

        // 如果参数本身就是 Map，就逐项转成字符串 key。
        if (value instanceof Map<?, ?> map) {
            java.util.HashMap<String, Object> result = new java.util.HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return result;
        }

        // 不存在时返回空 Map。
        return Map.of();
    }

    private void copyIfPresent(ToolCall call, Map<String, Object> target, String key) {
        // 从工具调用参数里复制事件上下文字段。
        Object value = call.params().get(key);
        if (value != null && !value.toString().isBlank()) {
            target.put(key, value);
        }
    }

    private ThreadFactory namedDaemonFactory() {
        // 工具执行线程用守护线程，进程退出时不会被卡住的工具调用拖住。
        return runnable -> {
            Thread thread = new Thread(runnable, "tool-exec-" + System.nanoTime());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 关闭工具执行线程池。
     */
    @PreDestroy
    void shutdownToolExecutor() {
        // 先停止接收新任务，再中断在途任务。
        toolExecutor.shutdownNow();
        log.info("[阶段4 工具调用] 工具执行线程池已关闭");
    }
}
