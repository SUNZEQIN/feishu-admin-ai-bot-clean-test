package com.sunzeqin.feishuadmin.service;

import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.config.FeishuAsyncConfig;
import com.sunzeqin.feishuadmin.pojo.agent.AgentRunResult;
import com.sunzeqin.feishuadmin.pojo.FeishuMessageEvent;
import com.sunzeqin.feishuadmin.service.agent.AgentOrchestratorService;
import com.sunzeqin.feishuadmin.utils.LlmErrorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 管理员机器人业务服务。
 *
 * <p>作用：接收飞书消息事件，判断是否命中当前功能，并调用粗粒度 Tool 完成业务。</p>
 *
 * @author sunzeqin
 */
@Service
public class AdminAgentService {
    // 当前服务使用的日志对象，用来排查后台异步处理是否成功。
    private static final Logger log = LoggerFactory.getLogger(AdminAgentService.class);

    // 飞书应用配置，用来判断是否开启处理中提示。
    private final FeishuProperties properties;

    // 飞书 OpenAPI 服务，用来回复飞书消息。
    private final FeishuOpenApiService openApi;

    // Agent 编排服务，负责 plan -> tool -> observe -> plan 的循环。
    private final AgentOrchestratorService orchestrator;

    // 用户 OAuth 授权服务，用来生成用户授权链接。
    private final UserOAuthTokenService userOAuthTokenService;

    // 用户身份 scope 映射服务，用来按用户提到的模块生成授权 scope。
    private final FeishuUserScopeMappingService scopeMappingService;

    // 二维码服务，用来把 OAuth 授权链接转成二维码图片。
    private final QrCodeService qrCodeService;

    public AdminAgentService(FeishuProperties properties, FeishuOpenApiService openApi,
            AgentOrchestratorService orchestrator, UserOAuthTokenService userOAuthTokenService,
            FeishuUserScopeMappingService scopeMappingService, QrCodeService qrCodeService) {
        // 保存配置对象。
        this.properties = properties;
        // 保存 OpenAPI 服务。
        this.openApi = openApi;
        // 保存 Agent 编排器。
        this.orchestrator = orchestrator;
        // 保存用户授权服务。
        this.userOAuthTokenService = userOAuthTokenService;
        // 保存用户身份 scope 映射服务。
        this.scopeMappingService = scopeMappingService;
        // 保存二维码服务。
        this.qrCodeService = qrCodeService;
    }

    /**
     * 处理一条飞书消息。
     *
     * @param event 消息事件
     */
    public void handleMessage(FeishuMessageEvent event) {
        // 这里留给同步调用兜底；真实入口会优先调用异步方法。
        withMdc(event, () -> doHandleMessage(event));
    }

    @Async(FeishuAsyncConfig.FEISHU_AGENT_EXECUTOR)
    public void handleMessageAsync(FeishuMessageEvent event) {
        // 后台异步处理飞书消息，避免飞书回调接口等待 LLM 执行。
        // 指定专用线程池，避免使用无界默认执行器把线程数打满。
        withMdc(event, () -> doHandleMessage(event));
    }

    private void withMdc(FeishuMessageEvent event, Runnable action) {
        // MDC 默认不会跨线程传递，业务入口统一注入消息和当前线程标识。
        java.util.Map<String, String> oldContext = MDC.getCopyOfContextMap();
        try {
            if (event != null) {
                putMdc("messageId", event.messageId());
                putMdc("chatId", event.chatId());
                putMdc("senderOpenId", event.openId());
            }
            MDC.put("threadId", String.valueOf(Thread.currentThread().getId()));
            action.run();
        } finally {
            if (oldContext == null || oldContext.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(oldContext);
            }
        }
    }

    private void putMdc(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    /**
     * 业务线程池已满时的兜底回复。
     *
     * <p>@Async 被拒绝时异常抛在调用方线程上，所以这里提供一个同步入口给 Controller 调用，
     * 保证用户不会只看到“已收到”却一直等不到结果。</p>
     *
     * @param event 消息事件
     */
    public void handleRejected(FeishuMessageEvent event) {
        // 线程池满时不进入 Agent 循环，只回复一条用户能理解的提示。
        safeReplyToSender(event, "⏳ 当前任务较多，请稍后再发一次。");
    }

    private void doHandleMessage(FeishuMessageEvent event) {
        try {
            // 用户询问机器人能力时，直接返回结构化功能说明，不进入 Agent 多步执行。
            if (helpQuestion(event.text())) {
                replyToSender(event, helpReply());
                return;
            }

            // 如果配置开启了处理中提示，就给原消息加表情，不再发送“稍等”文本。
            if (properties.isProcessingReplyEnabled()) {
                addProcessingReactions(event);
            } else {
                log.info("[阶段2 回复处理中] 处理中表情跳过：消息ID={}，原因=FEISHU_PROCESSING_REPLY_ENABLED=false",
                        event.messageId());
            }

            // 用户主动要求授权链接时，直接生成 OAuth 链接，不进入 LLM，避免模型误判为“不支持授权”。
            if (authorizationQuestion(event.text())) {
                AuthorizationReply authorizationReply = authorizationReply(event);
                safeReplyAuthorizationQr(event, authorizationReply.text(), authorizationReply.authorizeUrl());
                return;
            }

            // 交给 Agent 编排器执行多步循环。
            AgentRunResult result = orchestrator.run(event);

            // 授权类结果不直接发链接，统一转成二维码回复。
            if (!result.authorizeUrl().isBlank()) {
                log.info("[阶段8 回复飞书] 授权结果准备发送二维码：消息ID={}，授权链接长度={}",
                        event.messageId(), result.authorizeUrl().length());
                safeReplyAuthorizationQr(event, result.reply(), result.authorizeUrl());
                return;
            }

            // 只有底层工具返回结构化外发成功标记时，才追加发送确认。
            // 不能根据大模型最终文本猜测“卡片已发送”，否则只查到数据但没有调用飞书发送接口时也会误报成功。
            if (cardOrMessageAlreadySent(result)) {
                log.info("[阶段8 回复飞书] 卡片或消息已发送，准备回复简短确认：消息ID={}", event.messageId());
                safeReplyToSender(event, cardOrMessageSentReply(result));
                return;
            }

            // 把最终处理结果回复到飞书原消息下面。
            safeReplyToSender(event, result.reply());
        } catch (Exception e) {
            // 捕获后台线程异常，避免异步任务静默失败。
            log.error("[阶段8 回复飞书] 消息异步处理失败：消息ID={}，错误={}", event.messageId(), e.getMessage(), e);

            // 任何异常都要尽量回复用户，避免用户只看到“正在处理”。
            safeReplyToSender(event, errorReply(event, e));
        }
    }

    private String replyToSender(FeishuMessageEvent event, String text) {
        // 打印最终回复摘要，不在 INFO 里刷完整正文。
        log.info("[阶段8 回复飞书] 准备回复用户：消息ID={}，会话类型={}，是否@发送人={}，回复长度={}",
                event.messageId(), event.chatType(), "group".equals(event.chatType()), text == null ? 0 : text.length());
        // 给飞书用户回复消息。群聊里统一 @ 触发本次请求的人。
        return openApi.replyText(event.messageId(), withSenderMention(event, text));
    }

    private void safeReplyToSender(FeishuMessageEvent event, String text) {
        // 先清洗一次回复文本，减少平台限制触发概率。
        String cleanedText = cleanReplyText(text);

        try {
            // 优先发送完整清洗后的结果。
            replyToSender(event, cleanedText);
        } catch (Exception firstError) {
            // 第一次回复失败时打印完整异常，方便查飞书 code / log_id。
            log.warn("[阶段8 回复飞书] 第一次回复失败：消息ID={}，错误={}", event.messageId(), firstError.getMessage());

            // 如果是飞书平台限制，改发更短的兜底文本。
            String fallbackText = fallbackReply(event, firstError);
            try {
                replyToSender(event, fallbackText);
            } catch (Exception secondError) {
                // 兜底回复仍失败时只能记录日志，避免异步线程继续抛异常。
                log.error("[阶段8 回复飞书] 兜底回复仍失败：消息ID={}，错误={}",
                        event.messageId(), secondError.getMessage(), secondError);
            }
        }
    }

    private void safeReplyAuthorizationQr(FeishuMessageEvent event, String text, String authorizeUrl) {
        try {
            // 先回复简短说明，不包含授权链接。
            replyToSender(event, cleanReplyText(text));

            // 生成二维码图片。
            byte[] qrCodeBytes = qrCodeService.generatePng(authorizeUrl);

            // 上传二维码图片，拿到飞书 image_key。
            String imageKey = openApi.uploadMessageImage(qrCodeBytes, "feishu-oauth-qrcode.png");

            // 用二维码图片回复原消息。
            openApi.replyImage(event.messageId(), imageKey);
        } catch (Throwable e) {
            // 二维码发送失败时不回退明文链接，避免再次触发链接截断。
            // 这里捕获 Throwable，是为了接住 NoClassDefFoundError 这类依赖缺失错误，避免异步线程静默炸掉。
            log.error("[阶段8 回复飞书] 授权二维码发送失败：消息ID={}，错误类型={}，错误={}",
                    event.messageId(), e.getClass().getSimpleName(), e.getMessage(), e);
            safeReplyToSender(event, "⚠️ 授权二维码发送失败，请管理员按消息 ID 查看服务日志：" + event.messageId());
        }
    }

    private String withSenderMention(FeishuMessageEvent event, String text) {
        // 非群聊不需要 @，私聊里直接回复即可。
        if (!"group".equals(event.chatType())) {
            return text;
        }

        // open_id 为空时不能构造 @，直接返回原文。
        if (event.openId() == null || event.openId().isBlank()) {
            return text;
        }

        // 飞书文本消息里使用 open_id 作为 at 的 user_id。
        return "<at user_id=\"" + event.openId() + "\"></at> " + text;
    }

    private void addProcessingReactions(FeishuMessageEvent event) {
        // 读取配置里的表情类型。
        String reactionTypes = properties.getProcessingReactionTypes();
        if (reactionTypes == null || reactionTypes.isBlank()) {
            log.info("[阶段2 回复处理中] 处理中表情跳过：消息ID={}，原因=FEISHU_PROCESSING_REACTION_TYPES为空",
                    event.messageId());
            return;
        }

        // 打印表情配置摘要，方便部署后确认配置是否生效。
        log.info("[阶段2 回复处理中] 准备添加处理中表情：消息ID={}，配置={}",
                event.messageId(), reactionTypes);

        // 多个表情用逗号分隔，逐个添加。
        for (String item : reactionTypes.split(",")) {
            String reactionType = item.trim();
            if (reactionType.isBlank()) {
                continue;
            }

            try {
                // 给原消息加表情，表示机器人已经收到并开始处理。
                String normalizedReactionType = normalizeReactionType(reactionType);
                openApi.addReaction(event.messageId(), normalizedReactionType);
            } catch (Exception e) {
                // 表情失败不影响主流程。
                log.warn("[阶段2 回复处理中] 添加处理中表情失败：消息ID={}，表情={}，错误={}",
                        event.messageId(), reactionType, e.getMessage());
            }
        }
    }

    private String normalizeReactionType(String reactionType) {
        // 飞书 IM reaction 的 emoji_type 大小写敏感，这里兼容历史配置里的大写写法。
        if (reactionType == null || reactionType.isBlank()) {
            return "";
        }

        // 去掉前后空格。
        String value = reactionType.trim();

        // 兼容之前配置的 GET。
        if ("GET".equals(value)) {
            return "Get";
        }

        // 飞书 IM reaction 官方列表里没有 ROBOT，用 OnIt 表达“机器人已接手处理”。
        if ("ROBOT".equals(value)) {
            return "OnIt";
        }

        // 其它合法枚举保持原样，避免破坏大小写敏感值，例如 EatingFood、CheckMark。
        return value;
    }

    private boolean helpQuestion(String text) {
        // 空消息不属于功能咨询。
        if (text == null || text.isBlank()) {
            return false;
        }

        // 去掉机器人 @ 占位符，避免影响关键词判断。
        String normalizedText = text.replaceAll("@_user_\\d+", "").trim().toLowerCase(Locale.ROOT);

        // 常见功能咨询关键词。
        return normalizedText.contains("你能做什么")
                || normalizedText.contains("有什么功能")
                || normalizedText.contains("功能介绍")
                || normalizedText.contains("怎么用")
                || normalizedText.equals("help")
                || normalizedText.equals("帮助");
    }

    private boolean authorizationQuestion(String text) {
        // 空消息不属于授权请求。
        if (text == null || text.isBlank()) {
            return false;
        }

        // 去掉机器人 @ 占位符，避免影响关键词判断。
        String normalizedText = text.replaceAll("@_user_\\d+", "").trim();

        // 用户明确要授权链接时命中。
        return normalizedText.contains("授权链接")
                || normalizedText.contains("提供链接给我授权")
                || normalizedText.contains("给我授权")
                || normalizedText.contains("重新授权")
                || normalizedText.contains("发起授权");
    }

    private AuthorizationReply authorizationReply(FeishuMessageEvent event) {
        // 根据用户话术判断授权模块，例如日程、消息、文档、多维表格。
        String domain = detectAuthorizationDomain(event.text());

        // 命中模块时使用 scope 映射表，未命中时使用配置里的默认 scope。
        String scopeText = domain.isBlank()
                ? properties.getOauthDefaultScopes()
                : scopeMappingService.scopeTextForDomain(domain);

        // 打印授权入口的 scope 来源。
        log.info("[工具调用] 主动授权链接scope选择：消息ID={}，业务域={}，scope={}",
                event.messageId(), domain.isBlank() ? "默认配置" : domain, scopeText);

        // 生成 OAuth 链接。这个链路由 Java 服务保存 token，不依赖 lark-cli 交互式登录。
        String authorizeUrl = userOAuthTokenService.createAuthorizeUrl(event, scopeText);

        // 返回授权说明：不仅提示扫码，也要告诉用户当前申请哪些权限。
        String text = OAuthPermissionReplyFormatter.userAuthorizationRequired(scopeMappingService, domain, scopeText);

        // 返回授权说明和内部使用的授权链接。
        return new AuthorizationReply(text, authorizeUrl);
    }

    /**
     * 授权回复数据。
     *
     * <p>作用：文本给用户看，授权链接只用于生成二维码，不直接展示。</p>
     *
     * @param text         用户可见文本
     * @param authorizeUrl 授权链接
     *
     * @author sunzeqin
     */
    private record AuthorizationReply(String text, String authorizeUrl) {
    }

    private String detectAuthorizationDomain(String text) {
        // 空文本无法判断业务域。
        if (text == null || text.isBlank()) {
            return "";
        }

        // 统一转小写并去掉机器人 @ 占位符。
        String normalizedText = text.replaceAll("@_user_\\d+", "").toLowerCase(Locale.ROOT);

        // 多维表格相关权限。
        if (normalizedText.contains("多维表格") || normalizedText.contains("数据表")
                || normalizedText.contains("base") || normalizedText.contains("bitable")) {
            return "base";
        }

        // 文档相关权限。
        if (normalizedText.contains("文档") || normalizedText.contains("云文档")
                || normalizedText.contains("doc") || normalizedText.contains("docs")) {
            return "docs";
        }

        // 视频会议相关权限。
        if (normalizedText.contains("视频会议") || normalizedText.contains("飞书会议")
                || normalizedText.contains("vc")) {
            return "vc";
        }

        // 日程相关权限。
        if (normalizedText.contains("日程") || normalizedText.contains("日历")
                || normalizedText.contains("会议") || normalizedText.contains("参会")) {
            return "calendar";
        }

        // 消息和群聊相关权限。
        if (normalizedText.contains("消息") || normalizedText.contains("群聊")
                || normalizedText.contains("群成员") || normalizedText.contains("私聊")
                || normalizedText.contains("发给") || normalizedText.contains("发送")) {
            return "im";
        }

        // 通讯录相关权限。
        if (normalizedText.contains("通讯录") || normalizedText.contains("用户")
                || normalizedText.contains("人员") || normalizedText.contains("姓名")
                || normalizedText.contains("open_id") || normalizedText.contains("user_id")) {
            return "contact";
        }

        // 审批相关权限。
        if (normalizedText.contains("审批")) {
            return "approval";
        }

        // 考勤相关权限。
        if (normalizedText.contains("考勤") || normalizedText.contains("打卡")
                || normalizedText.contains("出勤") || normalizedText.contains("班次")
                || normalizedText.contains("attendance")) {
            return "attendance";
        }

        // 云盘相关权限。
        if (normalizedText.contains("云盘") || normalizedText.contains("文件")) {
            return "drive";
        }

        // 知识库相关权限。
        if (normalizedText.contains("知识库") || normalizedText.contains("wiki")) {
            return "wiki";
        }

        // 妙记相关权限。
        if (normalizedText.contains("妙记") || normalizedText.contains("minutes")) {
            return "minutes";
        }

        // 未识别时让默认配置兜底。
        return "";
    }

    private String helpReply() {
        // 返回结构化功能说明，方便用户快速理解怎么提问。
        return """
                ✅ 我可以帮你做这些事

                📊 电商业务分析
                - 查询客户订单、消费金额、客单价、品类偏好
                - 分析商品销量、库存、退款、复购和异常订单
                - 生成适合发到群里的简短汇总或飞书卡片

                🛠 飞书 CLI 能力
                - 群聊 / 消息：查群成员、读群消息、发消息、整理群聊
                - 云文档 / 多维表格：创建文档、整理内容、查询或写入数据
                - 日程 / 会议：创建日程、邀请参会人、查询会议信息
                - 通讯录 / 审批 / 云盘：按已授权能力查询和处理

                💬 你可以这样问
                - 查一下陈金金最近 12 个月订单，并总结消费偏好
                - 把本群今天聊天整理成一份文档，发给测试账号
                - 新建一个群聊，把本群的孙泽勤和测试账号拉进去
                - 创建明天下午 3 点会议，邀请陈金金参加

                如果执行过程中权限、参数或平台接口报错，我会把失败原因直接回复给你。""";
    }

    private boolean cardOrMessageAlreadySent(AgentRunResult result) {
        // 失败结果不能跳过回复，否则用户看不到失败原因。
        if (result == null || !result.success()) {
            return false;
        }

        // 只相信底层执行结果中的结构化标记，不解析大模型生成的自然语言。
        return result.externalMessageSent();
    }

    private String cardOrMessageSentReply(AgentRunResult result) {
        // 工具已经发送卡片或消息时，不再复述正文，只告诉用户真实发送成功。
        String reply = result == null ? "" : result.reply();

        // 能识别到私聊时，提示用户去对应私聊查看。
        if (reply.contains("私聊") || reply.contains("测试账号") || reply.contains("同学")) {
            return "✅ 飞书卡片已发送成功，请到对应私聊查看。";
        }

        // 默认确认发送成功。
        return "✅ 飞书卡片已发送成功。";
    }

    private String cleanReplyText(String text) {
        // 空回复给统一提示，避免飞书接口 content 为空。
        if (text == null || text.isBlank()) {
            return "✅ 已处理完成。";
        }

        // 替换容易触发平台限制或对用户不友好的技术描述。
        String cleanedText = text;
        cleanedText = cleanedText.replace("内容审核", "平台限制");
        cleanedText = cleanedText.replace("审核拦截", "平台限制");
        cleanedText = cleanedText.replace("绕过平台审核", "继续发送");
        cleanedText = cleanedText.replace("shell 转义", "命令参数处理");
        cleanedText = cleanedText.replace("interactive 卡片 JSON", "卡片内容");
        cleanedText = cleanedText.replace("错误码 230028", "平台返回限制");

        // 最终文本过长时截断，避免回复本身再次失败。
        if (cleanedText.length() > 1800) {
            cleanedText = cleanedText.substring(0, 1800) + "\n\n内容较长，已自动截断。";
        }

        // 返回清洗后的文本。
        return cleanedText;
    }

    private String errorReply(FeishuMessageEvent event, Exception error) {
        // 大模型余额不足要给用户明确提示。
        if (LlmErrorUtils.insufficientBalance(error)) {
            return LlmErrorUtils.insufficientBalanceReply();
        }

        // 其它异常返回简短失败说明。
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }

        // 清理报错文本，避免把过长堆栈或敏感内容发给用户。
        message = cleanErrorMessage(message);

        // 返回用户可读的失败提示。
        return "⚠️ 本次处理失败\n\n"
                + "🔎 原因：" + message + "\n"
                + "🧾 消息ID：" + event.messageId();
    }

    private String fallbackReply(FeishuMessageEvent event, Exception error) {
        // 平台限制时用最短文本兜底。
        if (feishuMessageBlocked(error)) {
            return "⚠️ 本次结果已生成，但完整内容没有发送成功。\n\n"
                    + "请改成“只返回简短摘要”，或让我用飞书卡片 / 云文档方式发送。\n"
                    + "🧾 消息ID：" + event.messageId();
        }

        // 普通回复失败时也给用户一个短提示。
        return "⚠️ 回复消息时失败，请管理员按消息ID查看服务日志：" + event.messageId();
    }

    private boolean feishuMessageBlocked(Exception error) {
        // 读取异常文本。
        String text = LlmErrorUtils.fullErrorText(error);

        // 飞书 230028 表示消息没有通过平台侧限制。
        return text.contains("230028")
                || text.contains("do NOT pass")
                || text.contains("not pass");
    }

    private String cleanErrorMessage(String message) {
        // 去掉换行，避免错误太长影响阅读。
        String cleanedMessage = message.replace("\r", " ").replace("\n", " ");

        // 不把完整密钥类字段发给用户。
        cleanedMessage = cleanedMessage.replaceAll("(?i)(app_secret|api_key|access_token|refresh_token|authorization)=[^,\\s}]+", "$1=***");

        // 飞书平台限制给用户更友好的描述。
        cleanedMessage = cleanedMessage.replace("The messages do NOT pass the audit.", "消息内容被平台限制发送。");

        // 报错太长时截断。
        if (cleanedMessage.length() > 800) {
            cleanedMessage = cleanedMessage.substring(0, 800) + "...";
        }

        // 返回清洗后的错误文本。
        return cleanedMessage;
    }
}
