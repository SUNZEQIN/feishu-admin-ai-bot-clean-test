package com.sunzeqin.feishuadmin.service.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.pojo.FeishuMessageEvent;
import com.sunzeqin.feishuadmin.pojo.UserOAuthToken;
import com.sunzeqin.feishuadmin.pojo.cli.CliCommandResult;
import com.sunzeqin.feishuadmin.pojo.cli.CliStepDecision;
import com.sunzeqin.feishuadmin.service.FeishuUserScopeMappingService;
import com.sunzeqin.feishuadmin.service.FeishuOpenApiService;
import com.sunzeqin.feishuadmin.service.OAuthPermissionReplyFormatter;
import com.sunzeqin.feishuadmin.service.UserOAuthTokenService;
import com.sunzeqin.feishuadmin.utils.JsonUtils;
import com.sunzeqin.feishuadmin.utils.LlmErrorUtils;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill + CLI 执行服务。
 *
 * <p>作用：固定工具不满足时，读取本地 Skill，先查 lark-cli 帮助，再由 LLM 规划并执行受控 CLI 命令。</p>
 *
 * @author sunzeqin
 */
@Service
public class SkillCliExecutorService {
    // 当前服务使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(SkillCliExecutorService.class);

    // 飞书配置，包含 CLI 开关、命令路径、大模型配置。
    private final FeishuProperties properties;

    // JSON 工具类，用来解析 CLI 规划器输出。
    private final JsonUtils jsonUtils;

    // 飞书 OpenAPI 服务，用来获取 tenant_access_token 并写入 lark-cli。
    private final FeishuOpenApiService openApiService;

    // 用户授权服务，用来生成授权链接和判断 scope。
    private final UserOAuthTokenService userOAuthTokenService;

    // 用户身份 scope 映射服务，用来按业务域生成授权 scope。
    private final FeishuUserScopeMappingService scopeMappingService;

    // 破坏性命令闸门：高风险写操作必须由用户原话确认，不允许模型自动确认。
    private final DestructiveCommandGuard destructiveCommandGuard;

    // 飞书 CLI 原生 Skill 缓存，避免每个规划步骤重复启动 lark-cli 读取同一份文档。
    private final Map<String, String> nativeSkillCache = new ConcurrentHashMap<>();

    // CLI 内部规划器使用的大模型。
    private final ChatModel chatModel;

    public SkillCliExecutorService(FeishuProperties properties, JsonUtils jsonUtils,
            FeishuOpenApiService openApiService, UserOAuthTokenService userOAuthTokenService,
            FeishuUserScopeMappingService scopeMappingService,
            DestructiveCommandGuard destructiveCommandGuard) {
        // 保存飞书配置。
        this.properties = properties;

        // 保存 JSON 工具类。
        this.jsonUtils = jsonUtils;

        // 保存飞书 OpenAPI 服务。
        this.openApiService = openApiService;

        // 保存用户授权服务。
        this.userOAuthTokenService = userOAuthTokenService;

        // 保存用户身份 scope 映射服务。
        this.scopeMappingService = scopeMappingService;

        // 保存破坏性命令闸门。
        this.destructiveCommandGuard = destructiveCommandGuard;

        // 创建 CLI 内部规划器模型。
        this.chatModel = buildChatModel(properties);
    }

    public Map<String, Object> runSkill(String domain, String goal, String sourceChatId,
            String originalMessageId, String senderOpenId, String senderUserId) {
        // 校验 CLI 是否启用。
        if (!properties.isCliEnabled()) {
            throw new IllegalStateException("Skill + CLI 未启用");
        }

        // 校验 LLM 是否可用。
        if (chatModel == null) {
            throw new IllegalStateException("Skill + CLI 需要启用 LLM");
        }

        // 规范化业务域。
        String normalizedDomain = normalizeDomain(domain);

        // 校验业务域是否允许。
        ensureDomainAllowed(normalizedDomain);

        // 用户明确要求用户身份时，执行前先检查用户 token。没有 token 就直接返回授权链接，不再让模型继续规划。
        Map<String, Object> authorizeResult = userAuthorizeResultIfNeeded(normalizedDomain, goal,
                sourceChatId, originalMessageId, senderOpenId, senderUserId);
        if (!authorizeResult.isEmpty()) {
            return authorizeResult;
        }

        // 原生思维导图不能被普通文档冒充。当前 lark-cli 只能操作已有 mindnote 节点，
        // 没有稳定的新建 mindnote 文件命令时，快速说明边界，避免通用流程反复探索后生成文档。
        if (shouldStopNativeMindMap(normalizedDomain, goal)) {
            return nativeMindMapUnsupported(normalizedDomain, goal, sourceChatId);
        }

        // 文档版思维导图才走 Markdown 文档直达流程，避免内层模型反复探索 mindnotes/wiki/drive/help。
        if (shouldCreateMindMapDocument(normalizedDomain, goal)) {
            return createMindMapDocument(normalizedDomain, goal, sourceChatId, senderOpenId);
        }

        // 读取所有允许业务域的 Skill 文档，让复合任务可以跨域执行。
        String skillText = readAllowedSkills(normalizedDomain);

        // 保存所有 CLI 执行观察结果。
        List<CliCommandResult> observations = new ArrayList<>();

        // 第一步固定先查当前业务域的 help，避免 LLM 直接猜命令。
        CliCommandResult helpResult = executeCommand(List.of(properties.getCliCommand(), normalizedDomain, "--help"), senderOpenId);

        // 保存 help 结果。
        observations.add(helpResult);

        // help 都失败时直接停止，避免后面模型在没有 CLI 能力说明的情况下继续猜命令。
        if (helpResult.exitCode() != 0) {
            throw new IllegalStateException("CLI 帮助查询失败，业务域=" + normalizedDomain
                    + "，错误=" + firstNotBlank(helpResult.stderr(), helpResult.stdout()));
        }

        // 打印 Skill + CLI 开始日志。
        log.info("[SkillCLI规划] 开始：业务域={}，目标={}，来源群ID={}，最大步骤数={}",
                normalizedDomain, goal, sourceChatId, properties.getCliMaxSteps());

        // 循环执行 CLI 内部规划。
        for (int step = 1; step <= properties.getCliMaxSteps(); step++) {
            // 构造 CLI 内部规划提示词。
            String prompt = buildPrompt(normalizedDomain, goal, sourceChatId, originalMessageId,
                    senderOpenId, senderUserId, skillText, observations);

            // 打印规划输入摘要。
            log.info("[SkillCLI规划] 规划输入：业务域={}，步骤={}，观察结果数量={}",
                    normalizedDomain, step, observations.size());

            // 调用模型规划下一条 CLI 命令或最终回复。
            String answer;
            try {
                answer = chatModel.chat(prompt);
            } catch (Exception e) {
                // 大模型余额不足时，直接把明确提示返回给外层工具。
                if (LlmErrorUtils.insufficientBalance(e)) {
                    log.warn("[SkillCLI规划] 规划失败：业务域={}，步骤={}，原因=大模型余额不足", normalizedDomain, step);
                    return Map.of(
                            "domain", normalizedDomain,
                            "goal", goal,
                            "sourceChatId", sourceChatId,
                            "finalReply", LlmErrorUtils.insufficientBalanceReply(),
                            "observations", observations
                    );
                }

                // 其它异常继续抛出，由工具注册表转成失败结果。
                throw e;
            }

            // 打印模型原始输出。
            log.debug("[SkillCLI规划] 模型原始输出：业务域={}，步骤={}，模型输出={}", normalizedDomain, step, answer);

            // 解析模型决策。
            CliStepDecision decision = parseDecision(answer);

            // 打印模型决策。
            log.info("[SkillCLI规划] 规划结果：业务域={}，步骤={}，类型={}，原因={}，命令={}，最终回复长度={}",
                    normalizedDomain, step, decision.type(), decision.reason(), decision.command(),
                    decision.finalReply() == null ? 0 : decision.finalReply().length());

            // 如果模型认为已经完成，就返回成功结果。
            if (!decision.commandDecision()) {
                return Map.of(
                        "domain", normalizedDomain,
                        "goal", goal,
                        "sourceChatId", sourceChatId,
                        "finalReply", decision.finalReply(),
                        "observations", observations
                );
            }

            // 校验命令是否允许执行。
            List<String> command = normalizeCommand(decision.command(), goal, normalizedDomain, senderOpenId);

            // 破坏性操作闸门：高风险写操作不允许模型自行确认，必须由用户原话明确确认。
            // 之前的事故就是模型给 drive +delete 自己加了 --yes，一句话删掉了 4 个真实多维表格。
            DestructiveCommandGuard.Decision guardDecision = destructiveCommandGuard.check(command, goal);
            if (guardDecision.blocked()) {
                log.warn("[CLI执行] 破坏性命令已拦截，等待用户确认：业务域={}，步骤={}，命令={}，命中={}",
                        normalizedDomain, step, command, guardDecision.matchedToken());
                return Map.of(
                        "domain", normalizedDomain,
                        "goal", goal,
                        "sourceChatId", sourceChatId,
                        "finalReply", destructiveConfirmReply(command),
                        "observations", observations
                );
            }

            // 如果模型重复执行已经成功过的相同命令，跳过真实调用，避免浪费步骤和重复请求飞书。
            if (hasSuccessfulCommand(observations, command)) {
                String message = "重复命令已跳过，请基于已有成功结果继续下一步：" + String.join(" ", command);
                log.warn("[SkillCLI规划] 重复命令已跳过：业务域={}，步骤={}，命令={}", normalizedDomain, step, command);
                observations.add(new CliCommandResult(String.join(" ", command), 0, message, ""));
                continue;
            }

            // 同一条命令第二次失败时不再重试，直接把真实原因抛给上层。
            // 这类重复通常意味着：模型认为应该这么调，但 Java 侧的策略把命令改成了必然失败的形态。
            // 继续循环只会烧完剩余步数，最后报一句和真实问题无关的「超过最大步骤数」。
            String previousFailure = previousFailureReason(observations, command);
            if (previousFailure != null) {
                log.warn("[SkillCLI规划] 同一条命令重复失败，停止重试：业务域={}，步骤={}，命令={}，上次失败={}",
                        normalizedDomain, step, command, previousFailure);
                throw new IllegalStateException("同一条命令重复失败，已停止重试。命令="
                        + String.join(" ", command) + "；上次失败原因=" + previousFailure);
            }

            // 执行 CLI 命令。
            CliCommandResult commandResult = executeCommand(command, senderOpenId);

            // 保存执行结果。
            observations.add(commandResult);

            // CLI 明确返回缺少用户授权 scope 时，生成授权链接并停止当前任务。
            String missingScopes = extractMissingScopes(commandResult);
            if (!missingScopes.isBlank()) {
                // 先判断这次"缺权限"到底缺的是谁的权限。
                // 用户已经持有覆盖当前业务域的授权时，缺权限的不是用户，而是这条命令本身 ——
                // 典型情况是需要本人数据的操作却用了 bot 身份。此时再弹一次二维码既没用又打扰用户，
                // 改为把判断结果回灌给内层规划器，让它换身份或换命令重试。
                // 循环安全由 previousFailureReason 兜住：模型真的换不出新命令时会在两次失败内停下。
                if (hasUserTokenForDomain(senderOpenId, normalizedDomain)) {
                    log.info("[SkillCLI规划] 用户已持有业务域授权，缺权限的是命令本身，不再要求授权：业务域={}，命令={}",
                            normalizedDomain, command);
                    observations.add(new CliCommandResult("SYSTEM_HINT", 1,
                            "本条命令因为缺少权限失败，但该用户已经完成本业务域的授权。"
                                    + "缺的不是用户授权，而是这条命令本身：需要本人数据的操作必须用 --as user，"
                                    + "bot 身份不支持 --mine 以及依赖登录用户的过滤参数。"
                                    + "请改用正确的身份或换一条命令重试。上一条失败原因="
                                    + summarizeCommandFailure(commandResult), ""));
                    continue;
                }

                // 从报错文本里抠出来的 scope 通常只是当前这一条命令用到的子集。
                // 只按它授权的话，用户扫完码换一条命令又会缺权限，被迫二次授权。
                // 这里按当前业务域补齐成完整集合，保证一次授权覆盖整个业务域。
                String requiredScopes = mergeWithDomainScopes(normalizedDomain, missingScopes);
                String authorizeUrl = buildAuthorizeUrl(sourceChatId, originalMessageId, senderOpenId,
                        senderUserId, requiredScopes);
                String reply = OAuthPermissionReplyFormatter.continuationAuthorizationRequired(
                        scopeMappingService, normalizedDomain, requiredScopes);
                return Map.of(
                        "domain", normalizedDomain,
                        "goal", goal,
                        "sourceChatId", sourceChatId,
                        "requiredScopes", requiredScopes,
                        "authorizeUrl", authorizeUrl,
                        "finalReply", reply,
                        "observations", observations
                );
            }
        }

        // 超过最大步骤数还没结束，就抛出异常，并带上最后一次失败的真实原因。
        // 只报「超过最大步骤数」会把真实问题（例如某个身份不被支持）完全藏起来。
        String lastFailure = lastFailureSummary(observations);
        if (lastFailure.isBlank()) {
            throw new IllegalStateException("Skill + CLI 超过最大步骤数，已停止执行");
        }
        throw new IllegalStateException("Skill + CLI 超过最大步骤数，已停止执行。最后一次失败原因：" + lastFailure);
    }

    private boolean shouldCreateMindMapDocument(String domain, String goal) {
        // 空目标不处理。
        if (goal == null || goal.isBlank()) {
            return false;
        }

        // 只处理和思维导图相关的目标。
        boolean mindMapIntent = goal.contains("思维导图")
                || goal.contains("脑图")
                || goal.contains("思维笔记");
        if (!mindMapIntent) {
            return false;
        }

        // 只有用户明确接受文档版/Markdown/大纲文档时，才创建文档。
        boolean documentFallbackAccepted = goal.contains("文档版")
                || goal.contains("文档形式")
                || goal.contains("大纲文档")
                || goal.contains("markdown")
                || goal.contains("Markdown");
        if (!documentFallbackAccepted) {
            return false;
        }

        // 用户明确要操作已有节点时，仍交给普通 SkillCLI 流程，避免误建文档。
        if (goal.contains("节点") || goal.contains("node") || goal.contains("mindnote id")) {
            return false;
        }

        // 只在这些文档型业务域里启用直达流程。
        return "mindnotes".equals(domain)
                || "docs".equals(domain)
                || "markdown".equals(domain);
    }

    private boolean shouldStopNativeMindMap(String domain, String goal) {
        // 空目标不处理。
        if (goal == null || goal.isBlank()) {
            return false;
        }

        // 只处理文档/思维导图相关业务域。
        boolean allowedDomain = "mindnotes".equals(domain)
                || "docs".equals(domain)
                || "markdown".equals(domain);
        if (!allowedDomain) {
            return false;
        }

        // 必须是思维导图意图。
        boolean mindMapIntent = goal.contains("思维导图")
                || goal.contains("脑图")
                || goal.contains("思维笔记");
        if (!mindMapIntent) {
            return false;
        }

        // 已经提供已有 mindnote id / 节点任务时，交给普通 CLI 流程。
        if (goal.contains("节点") || goal.contains("node") || goal.contains("mindnote id")) {
            return false;
        }

        // 用户明确接受文档版时，交给文档直达流程。
        return !(goal.contains("文档版")
                || goal.contains("文档形式")
                || goal.contains("大纲文档")
                || goal.contains("markdown")
                || goal.contains("Markdown"));
    }

    private Map<String, Object> nativeMindMapUnsupported(String domain, String goal, String sourceChatId) {
        String finalReply = """
                暂时不能直接新建原生飞书思维导图。

                原因：当前 lark-cli 的 mindnotes 能力只支持对已有思维笔记节点进行查询/创建/更新，需要已有的 mindnote id；没有稳定的新建 mindnote 文件命令。

                可以这样继续：
                1. 你提供一个已有思维笔记的 mindnote id，我再把群聊总结写入这个思维笔记；
                2. 或者你回复“生成文档版思维导图”，我会用 Markdown 大纲文档承载总结。
                """.strip();

        log.info("[SkillCLI规划] 原生思维导图直达拦截：业务域={}，目标={}，原因=缺少新建mindnote能力",
                domain, goal);

        return Map.of(
                "domain", domain,
                "goal", goal,
                "sourceChatId", sourceChatId,
                "finalReply", finalReply,
                "observations", List.of()
        );
    }

    private Map<String, Object> createMindMapDocument(String domain, String goal,
            String sourceChatId, String senderOpenId) {
        ensureDomainAllowed("docs");

        // 组装标题和正文。
        String title = "当前群聊总结思维导图";
        String content = buildMindMapMarkdown(goal);

        // 使用 docs +create 创建 Markdown 文档。这里使用 ProcessBuilder 参数数组，不经过 shell。
        List<String> command = List.of(
                properties.getCliCommand(),
                "docs",
                "+create",
                "--title",
                title,
                "--doc-format",
                "markdown",
                "--content",
                content,
                "--as",
                "bot",
                "--format",
                "json"
        );

        // 打印直达流程日志。
        log.info("[SkillCLI规划] 命中思维导图直达流程：业务域={}，标题={}，内容长度={}",
                domain, title, content.length());

        // 执行创建文档命令。
        CliCommandResult result = executeCommand(command, senderOpenId);
        List<CliCommandResult> observations = new ArrayList<>();
        observations.add(result);

        // 创建失败时保留真实 CLI 原因。
        if (result.exitCode() != 0) {
            throw new IllegalStateException("创建思维导图文档失败：" + summarizeCommandFailure(result));
        }

        // 从返回结果里提取文档链接或 token，提取不到也不影响成功判断。
        String documentUrl = findJsonText(result.stdout(), "url");
        if (documentUrl.isBlank()) {
            documentUrl = findJsonText(result.stdout(), "token");
        }
        String finalReply = "已根据群聊记忆和上文总结创建思维导图文档。";
        if (!documentUrl.isBlank()) {
            finalReply = finalReply + "\n\n文档信息：" + documentUrl;
        }

        // 返回给外层 Agent。
        return Map.of(
                "domain", domain,
                "goal", goal,
                "sourceChatId", sourceChatId,
                "finalReply", finalReply,
                "observations", observations
        );
    }

    private String buildMindMapMarkdown(String goal) {
        // 保留外层 Agent 已经整理好的内容，同时加上稳定的 Markdown 层级。
        String body = goal == null ? "" : goal.trim();
        int maxBodyChars = 12000;
        if (body.length() > maxBodyChars) {
            body = body.substring(0, maxBodyChars) + "\n\n> 内容较长，后续部分已截断。";
        }

        return """
                # 当前群聊总结思维导图

                ## 中心主题

                当前群聊总结

                ## 分支内容

                %s

                ## 后续跟进

                - 根据未决事项继续拆任务
                - 需要落地执行时，可以继续在群里 @机器人
                """.formatted(body);
    }

    private String findJsonText(String json, String fieldName) {
        // 空文本直接返回。
        if (json == null || json.isBlank() || fieldName == null || fieldName.isBlank()) {
            return "";
        }

        try {
            // CLI 成功输出通常是 JSON，递归查找常见字段。
            JsonNode root = jsonUtils.readTree(json);
            return findJsonText(root, fieldName);
        } catch (Exception e) {
            // 非 JSON 输出时不报错，创建成功本身由退出码判断。
            return "";
        }
    }

    private String findJsonText(JsonNode node, String fieldName) {
        // 空节点直接返回。
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }

        // 当前节点直接命中。
        if (node.has(fieldName) && node.get(fieldName).isTextual()) {
            return node.get(fieldName).asText("");
        }

        // 对象节点递归查找。
        if (node.isObject()) {
            Iterator<Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Entry<String, JsonNode> field = fields.next();
                String value = findJsonText(field.getValue(), fieldName);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }

        // 数组节点递归查找。
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = findJsonText(item, fieldName);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }

        // 没找到。
        return "";
    }

    private boolean hasSuccessfulCommand(List<CliCommandResult> observations, List<String> command) {
        // 拼接当前命令文本，和历史 observation 中的命令保持同一格式。
        String commandText = String.join(" ", command);

        // 遍历历史执行结果。
        for (CliCommandResult observation : observations) {
            // 只拦截已经成功执行过的完全相同命令。
            if (observation.exitCode() == 0 && commandText.equals(observation.command())) {
                return true;
            }
        }

        // 没有重复成功命令。
        return false;
    }

    /**
     * 判断同一条命令是否已经失败过一次。
     *
     * <p>返回上次的失败原因摘要；没有失败过则返回 null。</p>
     *
     * <p>为什么只允许失败一次就停：命令文本完全相同，说明模型没有换思路，重试不会得到
     * 不同结果。允许一次是为了给「间隔中状态发生变化」留余地（例如刚完成授权），
     * 第二次还失败就基本可以确定是死循环，必须停下来把真实原因暴露给用户。</p>
     */
    private String previousFailureReason(List<CliCommandResult> observations, List<String> command) {
        // 命令文本，和历史 observation 保持同一格式。
        String commandText = String.join(" ", command);

        // 统计同一条命令失败过几次，并记录最后一次失败原因。
        int failureCount = 0;
        String lastReason = "";
        for (CliCommandResult observation : observations) {
            if (observation.exitCode() != 0 && commandText.equals(observation.command())) {
                failureCount++;
                lastReason = summarizeCommandFailure(observation);
            }
        }

        // 已经失败过一次：本次就是第二次，停止重试。
        if (failureCount >= 1) {
            return lastReason.isBlank() ? "命令失败，退出码非 0" : lastReason;
        }

        return null;
    }

    /**
     * 取最后一次失败命令的原因摘要，用于超步时报错。
     */
    private String lastFailureSummary(List<CliCommandResult> observations) {
        // 从后往前找第一条失败的命令。
        for (int i = observations.size() - 1; i >= 0; i--) {
            CliCommandResult observation = observations.get(i);
            if (observation.exitCode() != 0) {
                String reason = summarizeCommandFailure(observation);
                if (!reason.isBlank()) {
                    return reason;
                }
            }
        }

        return "";
    }

    /**
     * 组装破坏性操作的确认提示。
     *
     * <p>关键点：把「将要执行什么」写清楚，并明确告诉用户不回复就等于不执行。
     * 用户确认后会用一条新消息触发新一轮任务，会话记忆里带着原来的目标。</p>
     */
    private String destructiveConfirmReply(List<String> command) {
        // 命令可能很长，截断后再展示，避免飞书消息过长。
        String commandText = String.join(" ", command);
        int limit = 400;
        if (commandText.length() > limit) {
            commandText = commandText.substring(0, limit) + "...";
        }

        return "⚠️ 这是一次高风险操作，需要你确认后才会执行。\n\n"
                + "将要执行：\n" + commandText + "\n\n"
                + "如果确认无误，请回复「确认执行」，我会继续。\n"
                + "如果不回复或回复其它内容，我不会做任何改动。";
    }

    /**
     * 把一条失败命令的输出压成一行可读原因，避免把整段 JSON 塞进用户回复。
     */
    private String summarizeCommandFailure(CliCommandResult observation) {
        // 优先看 stderr，CLI 的失败原因基本都在这里。
        String text = safeText(observation.stderr());
        if (text.isBlank()) {
            text = safeText(observation.stdout());
        }

        // 压掉换行和多余空白，只留一行。
        String singleLine = text.replaceAll("\\s+", " ").trim();
        if (singleLine.isBlank()) {
            return "";
        }

        // 限制长度，避免飞书消息过长。
        int limit = 300;
        return singleLine.length() <= limit ? singleLine : singleLine.substring(0, limit) + "...";
    }

    /**
     * 把报错文本里抽出的 scope，和当前业务域的完整 scope 集合合并。
     *
     * <p>问题背景：从 CLI 报错文本里用正则抠 scope，只能抠到当前这条命令提到的那几个，
     * 比业务域真正需要的少得多。用户按这个子集授权之后，下一条命令又会缺权限，
     * 于是被要求再次授权。这里统一按业务域补齐，保证一次授权就够用。</p>
     */
    private String mergeWithDomainScopes(String domain, String missingScopes) {
        // 用 LinkedHashSet 去重并保持稳定顺序。
        Set<String> merged = new LinkedHashSet<>();

        // 先放业务域要求的完整 scope。
        String domainScopes = scopeMappingService.scopeTextForDomain(domain);
        if (domainScopes != null && !domainScopes.isBlank()) {
            for (String item : domainScopes.split("\\s+")) {
                if (!item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }

        // 再补报错文本里出现、但业务域映射里没有的 scope，避免跨域场景漏掉。
        if (missingScopes != null && !missingScopes.isBlank()) {
            for (String item : missingScopes.split("\\s+")) {
                if (!item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }

        log.info("[SkillCLI规划] 授权scope已按业务域补齐：业务域={}，报错抽取={}个，合并后={}个",
                domain, missingScopes == null ? 0 : missingScopes.split("\\s+").length, merged.size());

        return String.join(" ", merged);
    }

    private Map<String, Object> userAuthorizeResultIfNeeded(String normalizedDomain, String goal, String sourceChatId,
            String originalMessageId, String senderOpenId, String senderUserId) {
        // 只有用户明确要求用户身份时才检查用户 token。
        if (!explicitUserIdentityRequired(goal)) {
            return Map.of();
        }

        // 根据当前业务域 + 用户目标计算用户身份需要的 scope。
        // 不能只看单个 domain：例如“云文档”实际横跨 drive/docs；“导入/新建多维表格”常横跨 base/drive。
        // 这里预先合并，避免用户刚扫完 drive，又因为 docs/base 缺权限被要求再扫一次。
        String scopeText = authorizationScopeTextForGoal(normalizedDomain, goal);

        // 打印授权 scope，方便排查为什么生成这个授权链接。
            log.info("[工具调用] 用户身份授权scope映射：业务域={}，scope数量={}，scope={}",
                normalizedDomain, scopeText.split("\\s+").length, scopeText);

        // 已经有可用 token 且 scope 覆盖当前业务域时继续执行。
        UserOAuthToken token = userOAuthTokenService.findUsableTokenForCli(senderOpenId);
        if (token != null && userOAuthTokenService.tokenHasScopes(senderOpenId, scopeText)) {
            log.info("[工具调用] 用户身份任务token可用：用户openId={}，过期时间={}，scope={}",
                    senderOpenId, token.expiresAt(), token.scopeText());
            return Map.of();
        }

        // 有 token 但 scope 不足时，也重新生成带当前业务域 scope 的授权链接。
        if (token != null) {
            log.info("[工具调用] 用户身份任务token权限不足：用户openId={}，已有scope={}，需要scope={}",
                    senderOpenId, token.scopeText(), scopeText);
        }

        // 没有 token 或 scope 不足时直接生成授权链接。
        String authorizeUrl = buildAuthorizeUrl(sourceChatId, originalMessageId, senderOpenId, senderUserId, scopeText);
        String reply = OAuthPermissionReplyFormatter.userAuthorizationRequired(
                scopeMappingService, normalizedDomain, scopeText);

        // 返回授权结果，让外层直接回复给用户。
        return Map.of(
                "domain", normalizedDomain,
                "goal", goal,
                "sourceChatId", sourceChatId,
                "requiredScopes", scopeText,
                "authorizeUrl", authorizeUrl,
                "finalReply", reply,
                "observations", List.of()
        );
    }

    private String authorizationScopeTextForGoal(String domain, String goal) {
        String normalizedDomain = normalizeDomain(domain);
        String normalizedGoal = goal == null ? "" : goal.toLowerCase(Locale.ROOT);

        // “云文档”在飞书里常同时涉及：
        // - drive：云盘/云空间文件搜索、token/URL 解析、导入导出、文件列表；
        // - docs：Docx 正文读取/创建/编辑。
        // 只授权其中一个，真实任务很容易走到第二个域后再次弹二维码。
        if (normalizedGoal.contains("云文档")) {
            return scopeMappingService.scopeTextForDomains(List.of("drive", "docs"));
        }

        // 新建/导入多维表格通常不是纯 base：文件创建、导入 CSV/Excel/Markdown/.base、云盘资源定位
        // 经常要走 drive，再进入 base 处理表内数据。
        if ("base".equals(normalizedDomain)
                && (normalizedGoal.contains("导入") || normalizedGoal.contains("新建")
                || normalizedGoal.contains("创建") || normalizedGoal.contains("上传"))) {
            return scopeMappingService.scopeTextForDomains(List.of("base", "drive"));
        }

        return scopeMappingService.scopeTextForDomain(normalizedDomain);
    }

    private ChatModel buildChatModel(FeishuProperties properties) {
        // 没开启 LLM 时不创建模型。
        if (!properties.isLlmEnabled()) {
            return null;
        }

        // 没配置 API Key 时不创建模型。
        if (properties.getLlmApiKey() == null || properties.getLlmApiKey().isBlank()) {
            return null;
        }

        // 创建 OpenAI 兼容模型。
        return OpenAiChatModel.builder()
                .baseUrl(properties.getLlmBaseUrl())
                .apiKey(properties.getLlmApiKey())
                .modelName(properties.getLlmModelName())
                .temperature(0.0)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private String buildPrompt(String domain, String goal, String sourceChatId, String originalMessageId,
            String senderOpenId, String senderUserId,
            String skillText, List<CliCommandResult> observations) {
        // 把 CLI 历史执行结果转成 JSON，方便模型阅读。
        String observationText = jsonUtils.write(observations);

        // 当前业务日期，专门用于“今天/明天/后天”这类相对时间换算。
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate tomorrow = today.plusDays(1);
        LocalDate dayAfterTomorrow = today.plusDays(2);

        // 返回 CLI 内部规划提示词。
        return """
                你是飞书 lark-cli Skill 执行器的内部规划器。

                你只能做两件事：
                1. 输出下一条要执行的 lark-cli 命令；
                2. 在任务已经完成或无法继续时输出 final_answer。

                输出必须是 JSON，不要 Markdown，不要解释。

                执行命令格式：
                {
                  "type": "cli_command",
                  "reason": "为什么执行这条命令",
                  "command": ["lark-cli", "im", "--help"],
                  "finalReply": ""
                }

                最终回复格式：
                {
                  "type": "final_answer",
                  "reason": "为什么完成或无法继续",
                  "command": [],
                  "finalReply": "给外层 Agent 的中文结果摘要"
                }

                严格规则：
                1. 优先根据 Skill 和已执行 help 的输出选择真实存在的命令，不要编造子命令。
                2. 每次只输出一条 CLI 命令。
                3. command 必须是字符串数组，不要输出一整段 shell 字符串。
                4. command 第一个元素必须是 lark-cli。
                5. 不要使用管道、重定向、分号、&&、||、反引号、$()。
                6. 当前群、当前会话、这个群、本群都指 sourceChatId。
                7. 如果缺参数，先用 CLI 查询对象，不要向用户索要 open_id、chat_id、app_id。
                8. 写操作执行成功后，必须 final_answer 汇总真实结果。
                9. 一个用户目标可以拆成多个 CLI 命令，命令业务域允许在白名单内灵活切换。
                10. 主业务域只表示用户目标的主要方向，不限制后续命令只能调用这个业务域。
                11. 复合任务要按真实步骤跨域执行，例如：
                    - 会议 + IM：先用 im/contact 找人，再用 calendar/vc 创建会议或日程，再用 im 通知参会人。
                    - 文档 + IM：先用 im 读取群消息或找收件人，再用 docs 创建/写入文档，再用 im 发送链接。
                    - 多维表格 + IM：先用 base 处理表格，再用 im 把结果发给群或用户。
                12. 不要因为当前业务域是 docs、vc、calendar、base 就拒绝执行 im/contact 等辅助命令，只要命令业务域在白名单内即可。
                13. 如果要调用其它业务域，但还不知道命令用法，先执行该业务域的 --help 或 schema 查询。
                14. 飞书操作默认必须使用 --as bot。
                15. 只有用户原话明确包含“用我的身份”“以本人身份”“以用户身份”“用用户身份执行”时，才允许使用 --as user。
                16. 命令帮助里写 supports user 或 supports user/bot，不代表必须使用 user；这种情况仍然使用 --as bot。
                17. 如果 bot 身份缺少应用权限，要返回真实失败原因，不要自动切换到 user 身份规避权限。
                18. lark-cli skills read 是只读资料查询命令，可以用来读取内置技能说明，但它不是业务执行结果。
                19. 如果某条列表命令已经带 --page-all 并且退出码为 0，不要再用相同 page-token 重复拉取同一页；应该基于已有结果继续下一步。
                20. 不要重复执行 observations 中已经成功执行过的完全相同命令。
                21. 对“整理聊天成文档并发送”这类任务，读取群消息成功后要尽快创建文档并发送链接，不要反复读取技能说明或重复分页。
                22. 发送飞书卡片或重要结果到当前群时，优先使用 im +messages-reply 引用 originalMessageId，而不是普通 send。
                23. 群聊里发送文本、Markdown、卡片时，内容开头要 @ senderOpenId 对应的人。
                24. 如果命令支持 --message-id、--message-id-type、--receive-id 等参数，要优先用 originalMessageId 完成“引用原文回复”。
                25. 如果用户明确要求用户身份，且 CLI 返回 missing_scope / authorization / scope 不足，不要编造成功，直接 final_answer 说明缺少 scope。
                26. 当前业务时区固定为 Asia/Shanghai。
                27. 当前日期是 %s；“今天”必须按 %s 计算，“明天”必须按 %s 计算，“后天”必须按 %s 计算。
                28. 创建日程/会议时，如果用户说“下午3点”，默认是北京时间 15:00；如果没有说明时长，默认 1 小时。
                29. 生成 --start / --end 时必须使用当前日期推导出的未来日期，不要使用历史 observations 里的旧日期。

                业务域：%s
                允许切换的业务域：%s
                来源群ID：%s
                原消息ID originalMessageId：%s
                触发人open_id senderOpenId：%s
                触发人user_id senderUserId：%s
                用户目标：%s

                Skill 内容集合：
                %s

                已有 CLI observations：
                %s
                """.formatted(today, today, tomorrow, dayAfterTomorrow,
                domain, properties.getCliAllowedDomains(), sourceChatId,
                originalMessageId, senderOpenId, senderUserId, goal, skillText, observationText);
    }

    private String extractMissingScopes(CliCommandResult commandResult) {
        // 空结果直接返回。
        if (commandResult == null) {
            return "";
        }

        // 合并 stdout 和 stderr。
        String text = safeText(commandResult.stdout()) + "\n" + safeText(commandResult.stderr());

        // lark-cli 的 help / skills 输出里也会出现 scope 字样，不能把普通说明误判成缺权限。
        String lowerText = text.toLowerCase(Locale.ROOT);
        boolean authFailed = lowerText.contains("missing_scope")
                || lowerText.contains("missing scope")
                || lowerText.contains("insufficient scope")
                || lowerText.contains("permission denied")
                || lowerText.contains("no access token")
                || lowerText.contains("token_missing")
                || lowerText.contains("not configured")
                || lowerText.contains("用户尚未授权")
                || lowerText.contains("用户token不可用");

        // 成功命令只有明确返回 ok=false 或缺权限关键词时，才允许进入授权分支。
        boolean commandReportedFailure = commandResult.exitCode() != 0
                || lowerText.contains("\"ok\": false")
                || lowerText.contains("\"ok\":false");

        // 没有真实认证失败时直接返回，避免 skills/help 输出误触发授权。
        if (!authFailed || !commandReportedFailure) {
            return "";
        }

        // 抽取类似 im:message.send_as_user / calendar:calendar.event:create 的 scope。
        Pattern pattern = Pattern.compile("[a-z][a-z0-9_]*:[a-zA-Z0-9_.:-]+");
        Matcher matcher = pattern.matcher(text);
        Set<String> scopes = new LinkedHashSet<>();
        while (matcher.find()) {
            String scope = matcher.group();
            if (!scope.startsWith("http:") && !scope.startsWith("https:")) {
                scopes.add(scope);
            }
        }

        // 未抽取到具体 scope 时，返回配置里的默认 scope。
        if (scopes.isEmpty()) {
            return properties.getOauthDefaultScopes();
        }

        // 返回空格分隔 scope。
        return String.join(" ", scopes);
    }

    private String buildAuthorizeUrl(String sourceChatId, String originalMessageId,
            String senderOpenId, String senderUserId, String scopeText) {
        // 构造一个轻量事件对象，复用授权服务的 state 保存逻辑。
        FeishuMessageEvent event = new FeishuMessageEvent(
                properties.getAppId(),
                "",
                "oauth.required",
                senderOpenId,
                senderUserId,
                "user",
                sourceChatId,
                "group",
                originalMessageId,
                "text",
                "",
                List.of()
        );

        // 数据库里 token 不满足时重新生成授权链接。
        if (!userOAuthTokenService.tokenHasScopes(senderOpenId, scopeText)) {
            return userOAuthTokenService.createAuthorizeUrl(event, scopeText);
        }

        // 理论上走到这里表示数据库已满足 scope，但 CLI 仍报错，返回重新授权兜底链接。
        return userOAuthTokenService.createAuthorizeUrl(event, scopeText);
    }

    private CliStepDecision parseDecision(String answer) {
        // 提取 JSON 文本。
        String json = extractJson(answer);

        // 解析 JSON。
        JsonNode root = jsonUtils.readTree(json);

        // 读取类型。
        String type = root.path("type").asText("final_answer");

        // 读取原因。
        String reason = root.path("reason").asText("");

        // 读取最终回复。
        String finalReply = root.path("finalReply").asText("");

        // 读取命令数组。
        List<String> command = new ArrayList<>();

        // 遍历 command 数组。
        for (JsonNode item : root.path("command")) {
            // 只保留非空字符串。
            String value = item.asText("");
            if (!value.isBlank()) {
                command.add(value);
            }
        }

        // 返回决策对象。
        return new CliStepDecision(type, reason, command, finalReply);
    }

    private List<String> normalizeCommand(List<String> command, String goal, String domain, String senderOpenId) {
        // 命令不能为空。
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("CLI 命令不能为空");
        }

        // 第一个参数必须是 lark-cli，避免执行其它系统命令。
        if (!"lark-cli".equals(command.get(0))) {
            throw new IllegalArgumentException("CLI 命令必须以 lark-cli 开头");
        }

        // 禁止把 shell 控制符当成独立参数传进来。
        // 注意：飞书卡片 JSON、Markdown、DocxXML 里可能合法出现 <font>、>、; 等字符，不能按 contains 粗暴拦截。
        for (String part : command) {
            if (forbiddenShellControlArgument(part)) {
                throw new IllegalArgumentException("CLI 命令包含不允许的 shell 符号：" + part);
            }
        }

        // 复制一份命令，避免修改不可变列表。
        List<String> normalized = new ArrayList<>(command);

        // 用配置里的真实命令路径替换 lark-cli。
        normalized.set(0, properties.getCliCommand());

        // 校验 lark-cli 的业务域。复合任务允许在白名单业务域内灵活切换。
        if (normalized.size() > 1) {
            String firstArg = normalized.get(1);
            ensureCommandDomainAllowed(firstArg);
        }

        // 业务命令默认使用 bot 身份；只有用户明确要求用户身份时，才保留 user 身份。
        normalizeIdentity(normalized, goal, domain, senderOpenId);

        // 对今天/明天/后天这类相对日期做代码级校正，避免模型把历史日期写进日程。
        normalizeRelativeDateArguments(normalized, goal);

        // 返回规范化命令。
        return normalized;
    }

    private boolean forbiddenShellControlArgument(String part) {
        // 空参数不属于 shell 控制符。
        if (part == null || part.isBlank()) {
            return false;
        }

        // ProcessBuilder 不经过 shell，普通文本里的 <font>、JSON、Markdown 不会被当成重定向或管道。
        // 这里只拦截模型把 shell 控制符单独放成参数的情况。
        String value = part.trim();
        return "|".equals(value)
                || ">".equals(value)
                || ">>".equals(value)
                || "<".equals(value)
                || "<<".equals(value)
                || ";".equals(value)
                || "&&".equals(value)
                || "||".equals(value);
    }

    private void normalizeIdentity(List<String> command, String goal, String domain, String senderOpenId) {
        // help/config/auth/skills/schema 这类命令不处理身份参数。
        if (!needTenantAccessToken(command)) {
            return;
        }

        // 记录是否已经出现 --as。
        boolean hasAs = false;

        // 遍历命令参数。
        for (int i = 0; i < command.size(); i++) {
            String part = command.get(i);

            // 处理 --as bot 或 --as user。
            if ("--as".equals(part)) {
                hasAs = true;
                if (i + 1 < command.size()) {
                    String oldValue = command.get(i + 1);
                    if (!"bot".equals(oldValue) && !"user".equals(oldValue)) {
                    log.warn("[CLI执行] 身份参数已修正：原身份={}，新身份=bot，原因=只允许bot或user", oldValue);
                        command.set(i + 1, "bot");
                    } else if ("user".equals(oldValue) && !userIdentityAllowed(goal, domain, senderOpenId)) {
                        log.warn("[CLI执行] 身份参数已修正：原身份=user，新身份=bot，原因=用户没有明确要求用户身份，且没有可用的用户token");
                        command.set(i + 1, "bot");
                    }
                } else {
                    command.add("bot");
                }
            }

            // 处理 --as=user 这种写法。
            if (part.startsWith("--as=")) {
                hasAs = true;
                if (!"--as=bot".equals(part) && !"--as=user".equals(part)) {
                    log.warn("[CLI执行] 身份参数已修正：原参数={}，新参数=--as=bot，原因=只允许bot或user", part);
                    command.set(i, "--as=bot");
                } else if ("--as=user".equals(part) && !userIdentityAllowed(goal, domain, senderOpenId)) {
                    log.warn("[CLI执行] 身份参数已修正：原参数=--as=user，新参数=--as=bot，原因=用户没有明确要求用户身份，且没有可用的用户token");
                    command.set(i, "--as=bot");
                }
            }
        }

        // 如果业务命令没带 --as，就默认补 bot。
        if (!hasAs) {
            command.add("--as");
            command.add("bot");
            log.info("[CLI执行] 身份参数已补充：身份=bot，原因=管理员机器人项目默认使用bot身份");
        }
    }

    /**
     * 判断这次 CLI 调用是否允许使用 --as user。
     *
     * <p>两条放行条件，满足其一即可：</p>
     * <ol>
     *   <li>用户原话明确要求用本人身份（{@link #explicitUserIdentityRequired}）；</li>
     *   <li>调用人已经持有覆盖当前业务域的用户 token。</li>
     * </ol>
     *
     * <p>为什么加上第二条：只要用户已经授权过，就没有理由再把模型正确给出的 --as user 改回 bot。
     * 改回 bot 会让命令必然失败（bot 没有登录用户 open_id），模型看到失败再规划一次 --as user，
     * Java 再改回 bot，双方各说各话，直到烧完所有步数。第二条只「承认已授权的身份」，
     * 不会主动要求新的授权，所以不会凭空给用户弹二维码。</p>
     */
    private boolean userIdentityAllowed(String goal, String domain, String senderOpenId) {
        // 条件一：用户原话明确要求本人身份。
        if (explicitUserIdentityRequired(goal)) {
            return true;
        }

        // 条件二：调用人已经有覆盖当前业务域的用户 token。
        if (hasUserTokenForDomain(senderOpenId, domain)) {
            log.info("[CLI执行] 身份参数保留user：用户openId={}，业务域={}，原因=已有覆盖该业务域的用户token",
                    senderOpenId, domain);
            return true;
        }

        return false;
    }

    /**
     * 判断调用人是否已经持有覆盖指定业务域的用户 token。
     *
     * <p>用于两处：一是决定是否保留 --as user；二是判断一次"缺权限"失败到底该不该让用户去授权。
     * 只有明确返回 false（而不是抛异常）时才会拒绝，避免把查询异常误当成"没有授权"。</p>
     */
    private boolean hasUserTokenForDomain(String senderOpenId, String domain) {
        // 缺少定位信息时无法判断，按没有 token 处理。
        if (senderOpenId == null || senderOpenId.isBlank() || domain == null || domain.isBlank()) {
            return false;
        }

        try {
            String scopeText = scopeMappingService.scopeTextForDomain(domain);
            return userOAuthTokenService.tokenHasScopes(senderOpenId, scopeText);
        } catch (Exception e) {
            // token 查询失败时保守处理：按没有 token 对待。
            log.warn("[CLI执行] 查询用户token失败，按无token处理：用户openId={}，业务域={}，错误={}",
                    senderOpenId, domain, e.getMessage());
            return false;
        }
    }

    private boolean explicitUserIdentityRequired(String goal) {
        // 没有用户目标时默认不允许 user 身份。
        if (goal == null || goal.isBlank()) {
            return false;
        }

        // 只有用户明确说要用用户/本人身份时，才允许 --as user。
        return goal.contains("用我的身份")
                || goal.contains("以我的身份")
                || goal.contains("用我身份")
                || goal.contains("以我身份")
                || goal.contains("用本人身份")
                || goal.contains("以本人身份")
                || goal.contains("本人身份")
                || goal.contains("用用户身份")
                || goal.contains("以用户身份")
                || goal.contains("用户身份执行")
                // 所有格表达：用户说「查询我名下的多维表格」，语义上就是在要求以本人身份执行。
                // 这里只补语义无歧义的写法；刻意不加单独的「我的」，因为「把消息发到我的群里」
                // 这类说法用机器人身份同样合理，加进去会让机器人无谓地弹二维码。
                // 真正兜住漏判的是 previousFailureReason：判据再漏，也会在两次失败内停下并报出真实原因。
                || goal.contains("我名下")
                || goal.contains("我自己的")
                || goal.contains("我本人")
                || goal.contains("本人的");
    }

    private void normalizeRelativeDateArguments(List<String> command, String goal) {
        // 没有明确相对日期时不处理，避免误改用户指定的绝对日期。
        LocalDate targetDate = targetDateFromGoal(goal);
        if (targetDate == null) {
            return;
        }

        // 只处理带 ISO 时间的参数值，例如 2025-01-16T15:00:00+08:00。
        for (int i = 0; i < command.size(); i++) {
            String value = command.get(i);
            if (value == null || value.length() < 11) {
                continue;
            }

            // 只替换日期部分，保留时间、秒和时区。
            if (value.matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
                String oldValue = value;
                String newValue = targetDate + value.substring(10);
                if (!oldValue.equals(newValue)) {
                    command.set(i, newValue);
                    log.warn("[CLI执行] 相对日期已校正：原值={}，新值={}，原因=用户目标包含相对日期",
                            oldValue, newValue);
                }
            }
        }
    }

    private LocalDate targetDateFromGoal(String goal) {
        // 空目标不处理。
        if (goal == null || goal.isBlank()) {
            return null;
        }

        // 统一使用北京时间计算业务日期。
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

        // 按最常见相对日期处理。
        if (goal.contains("后天")) {
            return today.plusDays(2);
        }
        if (goal.contains("明天")) {
            return today.plusDays(1);
        }
        if (goal.contains("今天")) {
            return today;
        }

        // 没有相对日期。
        return null;
    }

    private CliCommandResult executeCommand(List<String> command, String senderOpenId) {
        try {
            // 执行业务 CLI 前，先准备对应身份的 token，后续会显式注入到子进程环境。
            CliTokenContext tokenContext = prepareAccessTokenForCli(command, senderOpenId);

            // 打印 CLI 执行入参。
            log.info("[CLI执行] 执行命令：命令={}", command);

            // 创建 CLI 进程。业务命令会使用受控环境，明确注入当前身份 token。
            Process process = buildProcess(command, tokenContext).start();

            // 异步读取标准输出，避免输出较多时进程缓冲区写满导致卡死。
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getInputStream()));

            // 异步读取错误输出，避免错误信息较多时进程缓冲区写满导致卡死。
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getErrorStream()));

            // 等待命令执行完成。
            boolean finished = process.waitFor(properties.getCliTimeoutSeconds(), TimeUnit.SECONDS);

            // 超时时销毁进程。
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("CLI 命令执行超时：" + command);
            }

            // 读取标准输出。
            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);

            // 读取错误输出。
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            // 读取退出码。
            int exitCode = process.exitValue();

            // 打印 CLI 执行结果。
            log.info("[CLI结果] 命令结果摘要：命令={}，退出码={}，标准输出长度={}，错误输出长度={}，标准输出摘要={}，错误输出摘要={}",
                    command, exitCode, length(stdout), length(stderr), firstLine(stdout), firstLine(stderr));
            log.debug("[CLI结果] 命令原始输出：命令={}，退出码={}，标准输出={}，错误输出={}",
                    command, exitCode, stdout, stderr);

            // 返回 CLI 执行结果。
            return new CliCommandResult(String.join(" ", command), exitCode, stdout, stderr);
        } catch (Exception e) {
            // 打印 CLI 执行异常。
            log.warn("[CLI结果] 命令异常：命令={}，错误={}", command, e.getMessage());

            // 返回失败结果。
            return new CliCommandResult(String.join(" ", command), -1, "", e.getMessage());
        }
    }

    private CliTokenContext prepareAccessTokenForCli(List<String> command, String senderOpenId) {
        // 非 lark-cli 命令不处理；正常情况下不会出现。
        if (command == null || command.isEmpty()) {
            return CliTokenContext.empty();
        }

        // 只处理配置里的 lark-cli 命令。
        if (!properties.getCliCommand().equals(command.get(0))) {
            return CliTokenContext.empty();
        }

        // config/auth/help/schema/skills 这类命令不需要 bot token，避免无意义写入。
        if (!needTenantAccessToken(command)) {
            return CliTokenContext.empty();
        }

        // 读取命令身份。
        String identity = commandIdentity(command);

        // 用户身份命令读取数据库里的用户 access_token。
        if ("user".equals(identity)) {
            UserOAuthToken token = userOAuthTokenService.findUsableTokenForCli(senderOpenId);
            if (token == null) {
                throw new IllegalStateException("用户尚未授权或用户token不可用，请先完成用户授权");
            }
            log.info("[CLI执行] 用户token准备完成：用户openId={}，过期时间={}，scope={}",
                    senderOpenId, token.expiresAt(), token.scopeText());
            return new CliTokenContext("user", token.accessToken());
        }

        // appId 不能为空，否则 lark-cli 不知道 token 属于哪个应用。
        if (properties.getAppId() == null || properties.getAppId().isBlank()) {
            throw new IllegalStateException("写入 lark-cli tenant_access_token 失败：FEISHU_APP_ID 为空");
        }

        // 从 Java OpenAPI 服务拿最新 tenant_access_token，不打印 token 明文。
        String token = openApiService.tenantAccessTokenForCli();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("写入 lark-cli tenant_access_token 失败：tenant_access_token 为空");
        }

        // 把 token 写入 lark-cli 的本地 token store，兼容需要读取 credential-store 的命令。
        setTenantAccessToken(token);

        // 返回 token，后续会直接注入到业务命令环境变量里，避免 lark-cli 找不到 bot token。
        return new CliTokenContext("bot", token);
    }

    private String commandIdentity(List<String> command) {
        // 默认使用 bot。
        String identity = "bot";

        // 空命令返回默认身份。
        if (command == null) {
            return identity;
        }

        // 遍历命令参数。
        for (int i = 0; i < command.size(); i++) {
            String part = command.get(i);

            // 处理 --as user。
            if ("--as".equals(part) && i + 1 < command.size()) {
                identity = command.get(i + 1);
            }

            // 处理 --as=user。
            if (part != null && part.startsWith("--as=")) {
                identity = part.substring("--as=".length());
            }
        }

        // 只允许 bot / user。
        if (!"user".equals(identity)) {
            return "bot";
        }
        return "user";
    }

    private boolean needTenantAccessToken(List<String> command) {
        // 只有真实业务域命令才需要 token。
        if (command.size() < 2) {
            return false;
        }

        // 读取第二个参数。
        String domain = command.get(1);

        // 帮助和配置类命令不需要提前写 token。
        if ("--help".equals(domain) || "-h".equals(domain)
                || "config".equals(domain)
                || "auth".equals(domain)
                || "skills".equals(domain)
                || "schema".equals(domain)) {
            return false;
        }

        // 如果只是查询某个业务域 help，也不需要提前写 token。
        if (command.contains("--help") || command.contains("-h")) {
            return false;
        }

        // 其它 lark-cli 业务命令需要 token。
        return true;
    }

    private void setTenantAccessToken(String token) {
        // 组装写入 token 的 lark-cli 命令，token 通过 stdin 传入，不出现在命令行和日志里。
        List<String> command = List.of(
                properties.getCliCommand(),
                "config",
                "tenant-access-token",
                "set",
                "--app-id",
                properties.getAppId()
        );

        try {
            // 打印写入动作，不打印 token 明文。
            log.info("[CLI执行] 写入tenant_access_token：appId={}，命令={}", properties.getAppId(), command);

            // 创建写入 token 的进程。
            Process process = new ProcessBuilder(command).start();

            // 通过 stdin 写入 token。
            try (OutputStream outputStream = process.getOutputStream()) {
                outputStream.write(token.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }

            // 异步读取标准输出。
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getInputStream()));

            // 异步读取错误输出。
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getErrorStream()));

            // 等待写入完成。
            boolean finished = process.waitFor(properties.getCliTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("写入 lark-cli tenant_access_token 超时");
            }

            // 读取退出码和输出。
            int exitCode = process.exitValue();
            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            // 写入失败时抛出异常，让上层返回真实错误。
            if (exitCode != 0) {
                throw new IllegalStateException("写入 lark-cli tenant_access_token 失败，退出码="
                        + exitCode + "，标准输出=" + truncate(stdout) + "，错误输出=" + truncate(stderr));
            }

            // 打印写入成功日志，不打印 token。
            log.info("[CLI执行] 写入tenant_access_token成功：appId={}，退出码={}", properties.getAppId(), exitCode);
        } catch (Exception e) {
            // 写入 token 失败时抛出异常。
            throw new IllegalStateException("写入 lark-cli tenant_access_token 异常：" + e.getMessage(), e);
        }
    }

    private ProcessBuilder buildProcess(List<String> command, CliTokenContext tokenContext) {
        // 创建普通进程构造器。
        ProcessBuilder processBuilder = new ProcessBuilder(command);

        // 只有真实业务命令才调整环境变量；help/config/auth 不动，避免影响诊断命令。
        if (!needTenantAccessToken(command)) {
            return processBuilder;
        }

        // 读取当前进程环境变量。
        Map<String, String> environment = processBuilder.environment();

        // 明确告诉 lark-cli 当前应用 ID，方便它定位当前应用。
        environment.put("LARKSUITE_CLI_APP_ID", properties.getAppId());

        // 传入应用密钥。这里不打印密钥，只给 lark-cli 子进程使用。
        environment.put("LARKSUITE_CLI_APP_SECRET", properties.getAppSecret());

        // 根据命令身份注入 token。
        if ("user".equals(tokenContext.identity())) {
            // 直接传入本次数据库读取到的 user_access_token，避免 lark-cli 读取 token store 失败。
            environment.put("LARKSUITE_CLI_USER_ACCESS_TOKEN", tokenContext.accessToken());
            environment.put("LARKSUITE_CLI_ACCESS_TOKEN", tokenContext.accessToken());
            environment.remove("LARKSUITE_CLI_TENANT_ACCESS_TOKEN");
        } else {
            // 直接传入本次 Java 获取到的 tenant_access_token，避免 lark-cli 读取 token store 失败。
            environment.put("LARKSUITE_CLI_TENANT_ACCESS_TOKEN", tokenContext.accessToken());
            environment.remove("LARKSUITE_CLI_USER_ACCESS_TOKEN");
            environment.remove("LARKSUITE_CLI_ACCESS_TOKEN");
        }

        // 设置当前命令默认身份。
        environment.put("LARKSUITE_CLI_DEFAULT_AS", tokenContext.identity());

        // 强制只允许当前身份，避免 CLI 在 user/bot 之间自动切换。
        environment.put("LARKSUITE_CLI_STRICT_MODE", tokenContext.identity());

        // 不使用 credential-store 作为 token 来源，优先使用本次注入的环境变量 token。
        environment.remove("LARKSUITE_CLI_TENANT_ACCESS_TOKEN_SOURCE");

        // 关闭 CLI 更新提示，减少日志噪音。
        environment.put("LARKSUITE_CLI_NO_UPDATE_NOTIFIER", "1");

        // 打印受控环境说明，不打印密钥和 token。
        log.info("[CLI执行] 业务命令环境已调整：appId={}，默认身份={}，已注入对应身份token，已启用严格模式",
                properties.getAppId(), tokenContext.identity());

        // 返回处理后的进程构造器。
        return processBuilder;
    }

    /**
     * CLI token 上下文。
     *
     * @param identity    当前命令身份
     * @param accessToken 当前身份 token
     *
     * @author sunzeqin
     */
    private record CliTokenContext(String identity, String accessToken) {
        private static CliTokenContext empty() {
            return new CliTokenContext("", "");
        }
    }

    private String readStream(InputStream inputStream) {
        try {
            // 读取进程输出流并按 UTF-8 转成字符串。
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 输出流读取失败时返回错误文本，方便日志继续展示问题。
            return "读取进程输出失败：" + e.getMessage();
        }
    }

    private String readSkill(String domain) {
        // 优先读取 lark-cli 内置原生 Skill 文档，项目本地 md 只作为补充规则和兜底。
        String nativeSkill = readNativeSkill(domain);
        String projectSkill = readProjectSkill(domain);
        String nativeSkillName = nativeSkillName(domain);

        // 原生 Skill 存在时，把原生文档放在前面，项目补充规则放在后面。
        if (!nativeSkill.isBlank()) {
            return """
                    # 飞书 CLI 原生 Skill

                    来源：`lark-cli skills read %s`

                    %s

                    # 项目补充规则

                    %s
                    """.formatted(nativeSkillName, nativeSkill, projectSkill);
        }

        // 原生 Skill 读取失败时回退本地补充规则。
        return projectSkill;
    }

    private String readProjectSkill(String domain) {
        try {
            // 项目补充规则统一放在 resources/skills/lark 目录。
            // 这一层只写本项目约束和兜底提示，不替代 lark-cli 原生 Skill。
            ClassPathResource resource = new ClassPathResource("skills/lark/" + domain + ".md");

            // 如果没有对应补充规则，就使用通用说明。
            if (!resource.exists()) {
                return "没有找到项目补充规则。请优先参考 lark-cli 原生 Skill 和 lark-cli " + domain + " --help。";
            }

            // 读取项目补充规则文件内容。
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 项目补充规则读取失败时抛出业务异常。
            throw new IllegalStateException("读取项目补充规则失败：" + domain, e);
        }
    }

    private String readNativeSkill(String domain) {
        // 按业务域映射到 lark-cli 内置 Skill 名称。
        String skillName = nativeSkillName(domain);
        if (skillName.isBlank()) {
            return "";
        }

        // 优先读缓存，避免同一个请求里反复启动 lark-cli。
        String cached = nativeSkillCache.get(skillName);
        if (cached != null) {
            return cached;
        }

        // 调用 lark-cli skills read 读取原生 Skill。
        List<String> command = List.of(properties.getCliCommand(), "skills", "read", skillName);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().put("LC_ALL", "C.UTF-8");
        processBuilder.environment().put("LANG", "C.UTF-8");

        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[SkillCLI规划] 读取飞书CLI原生Skill超时：业务域={}，原生Skill={}", domain, skillName);
                return "";
            }

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            if (process.exitValue() != 0 || stdout.isBlank()) {
                log.warn("[SkillCLI规划] 读取飞书CLI原生Skill失败：业务域={}，原生Skill={}，退出码={}，错误={}",
                        domain, skillName, process.exitValue(), truncate(stderr));
                return "";
            }

            nativeSkillCache.put(skillName, stdout);
            log.info("[SkillCLI规划] 已读取飞书CLI原生Skill：业务域={}，原生Skill={}，内容长度={}",
                    domain, skillName, stdout.length());
            return stdout;
        } catch (Exception e) {
            log.warn("[SkillCLI规划] 读取飞书CLI原生Skill异常：业务域={}，原生Skill={}，错误={}",
                    domain, skillName, e.getMessage());
            return "";
        }
    }

    private String nativeSkillName(String domain) {
        // 把项目里的业务域名称映射到 lark-cli 内置 Skill 名称。
        return switch (domain) {
            case "im" -> "lark-im";
            case "base" -> "lark-base";
            case "docs", "mindnotes" -> "lark-doc";
            case "calendar" -> "lark-calendar";
            case "vc", "minutes", "note" -> "lark-meeting";
            case "contact" -> "lark-contact";
            case "approval" -> "lark-approval";
            case "attendance" -> "lark-attendance";
            case "drive" -> "lark-drive";
            case "wiki" -> "lark-wiki";
            case "markdown" -> "lark-markdown";
            case "whiteboard" -> "lark-whiteboard";
            default -> "";
        };
    }

    private String readAllowedSkills(String primaryDomain) {
        // 保存合并后的 Skill 文档。
        StringBuilder builder = new StringBuilder();

        // 先放主业务域 Skill，方便模型优先理解当前任务方向。
        appendSkill(builder, primaryDomain);

        // 再放其它白名单业务域 Skill，方便模型处理跨域步骤。
        for (String item : properties.getCliAllowedDomains().split(",")) {
            String domain = item.trim().toLowerCase(Locale.ROOT);
            if (!domain.isBlank() && !domain.equals(primaryDomain)) {
                appendSkill(builder, domain);
            }
        }

        // 返回所有允许业务域的 Skill 文档。
        return builder.toString();
    }

    private void appendSkill(StringBuilder builder, String domain) {
        // 追加 Skill 分隔标题，避免多个文档混在一起看不清。
        builder.append("\n\n================ Skill Domain: ")
                .append(domain)
                .append(" ================\n");

        // 追加具体 Skill 内容。
        builder.append(readSkill(domain));
    }

    private void ensureDomainAllowed(String domain) {
        // 读取允许的业务域配置。
        String allowed = properties.getCliAllowedDomains();

        // 按逗号分隔判断。
        for (String item : allowed.split(",")) {
            if (domain.equals(item.trim())) {
                return;
            }
        }

        // 不在白名单里就拒绝。
        throw new IllegalArgumentException("CLI 业务域不允许：" + domain);
    }

    private void ensureCommandDomainAllowed(String commandDomain) {
        // shortcut 命令以 + 开头，例如 +chat-members-list，由 lark-cli 自己路由，允许执行。
        if (commandDomain.startsWith("+")) {
            return;
        }

        // 根帮助、配置、授权、schema、skills 是只读或诊断元命令，允许执行。
        if ("--help".equals(commandDomain)
                || "-h".equals(commandDomain)
                || "config".equals(commandDomain)
                || "auth".equals(commandDomain)
                || "schema".equals(commandDomain)
                || "skills".equals(commandDomain)) {
            return;
        }

        // 普通业务域必须在白名单里。
        ensureDomainAllowed(commandDomain);
    }

    private String normalizeDomain(String domain) {
        // 空业务域不允许。
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("CLI 业务域不能为空");
        }

        // 转小写并去掉前后空格。
        return domain.trim().toLowerCase(Locale.ROOT);
    }

    private String extractJson(String answer) {
        // 空回复时返回最终回复 JSON。
        if (answer == null || answer.isBlank()) {
            return "{\"type\":\"final_answer\",\"reason\":\"模型空回复\",\"command\":[],\"finalReply\":\"模型没有返回内容\"}";
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

    private String truncate(String text) {
        // 空文本直接返回空字符串。
        if (text == null) {
            return "";
        }

        // 日志最多打印 2000 字符，避免刷屏。
        if (text.length() <= 2000) {
            return text;
        }

        // 返回截断文本。
        return text.substring(0, 2000) + "...";
    }

    private int length(String text) {
        // 空文本长度按 0 处理。
        if (text == null) {
            return 0;
        }

        // 返回字符串长度。
        return text.length();
    }

    private String safeText(String text) {
        // 空文本统一转为空字符串，避免拼接错误信息时出现 null。
        if (text == null) {
            return "";
        }

        // 返回原文本。
        return text;
    }

    private String firstLine(String text) {
        // 空文本直接返回空字符串。
        if (text == null || text.isBlank()) {
            return "";
        }

        // 只取第一行作为 INFO 摘要，完整输出放 DEBUG。
        String[] lines = text.strip().split("\\R", 2);
        return truncate(lines[0]);
    }

    private String firstNotBlank(String first, String second) {
        // 优先返回第一个非空文本。
        if (first != null && !first.isBlank()) {
            return truncate(first);
        }

        // 第一个为空时返回第二个非空文本。
        if (second != null && !second.isBlank()) {
            return truncate(second);
        }

        // 都为空时返回统一提示。
        return "无输出";
    }
}
