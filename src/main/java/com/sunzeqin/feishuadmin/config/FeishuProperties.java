package com.sunzeqin.feishuadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 飞书应用配置。
 *
 * <p>作用：统一承载 OpenAPI 地址、应用凭据、事件校验 token 和处理中回复文案。</p>
 *
 * @author sunzeqin
 */
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {
    // 飞书 OpenAPI 基础地址，默认使用飞书官方地址。
    private String baseUrl = "https://open.feishu.cn";

    // 飞书应用 app_id，用于获取 tenant_access_token。
    private String appId = "";

    // 飞书应用 app_secret，用于获取 tenant_access_token。
    private String appSecret = "";

    // 飞书事件订阅里的 verification token，用于校验回调来源。
    private String verificationToken = "";

    // 是否在收到消息后先回复“正在处理”，避免用户觉得机器人卡住。
    private boolean processingReplyEnabled = true;

    // 收到消息后的处理中提示文案。
    private String processingReplyText = "⏳ 正在处理，请稍等...";

    // 收到消息后给原消息添加的处理中表情，多个表情用逗号分隔。飞书 emoji_type 大小写敏感。
    private String processingReactionTypes = "Get,OnIt";

    // 飞书 OAuth 回调地址，必须和开放平台后台配置一致。
    private String oauthRedirectUri = "";

    // 飞书 OAuth 授权页基础地址，国内飞书通常是 https://accounts.feishu.cn。
    private String oauthAuthorizeBaseUrl = "https://accounts.feishu.cn";

    // 用户授权兜底 scope，缺少具体 scope 时使用。
    private String oauthDefaultScopes = "offline_access";

    // 用户 token 刷新提前量，单位秒。
    private int oauthRefreshBeforeSeconds = 600;

    // 是否启用 LLM 意图识别；关闭时使用本地规则兜底。
    private boolean llmEnabled = false;

    // OpenAI 兼容模型接口地址，例如 DeepSeek 的 API 地址。
    private String llmBaseUrl = "https://api.deepseek.com";

    // 大模型 API Key。
    private String llmApiKey = "";

    // 大模型名称。
    private String llmModelName = "deepseek-chat";

    // 意图识别温度，默认 0，保证输出稳定。
    private double llmTemperature = 0.0;

    // 是否启用会话记忆。
    private boolean memoryEnabled = true;

    // 每个“会话 + 用户”最多保留多少条记忆消息。
    private int memoryMaxMessages = 20;

    // 触发会话压缩的消息条数阈值。
    private int memoryCompressThreshold = 20;

    // 压缩后继续保留的最近消息条数。
    private int memoryRecentMessages = 8;

    // 每次最多压缩多少条旧消息。
    private int memoryCompressBatchSize = 12;

    // 会话摘要最大字符数，超过后保留后半段。
    private int memorySummaryMaxChars = 4000;

    // 是否启用 Skill + CLI 兜底执行器。
    private boolean cliEnabled = true;

    // lark-cli 命令路径，容器里一般就是 lark-cli。
    private String cliCommand = "lark-cli";

    // Skill + CLI 内部最多执行多少轮。
    private int cliMaxSteps = 15;

    // 单条 CLI 命令最大等待秒数。
    private int cliTimeoutSeconds = 60;

    // 允许通过 Skill + CLI 执行的业务域。
    private String cliAllowedDomains = "im,base,docs,calendar,vc,contact,approval,attendance";

    // 是否启用电商 MCP 服务。
    private boolean ecommerceMcpEnabled = false;

    // 电商 MCP 服务地址。
    private String ecommerceMcpBaseUrl = "http://127.0.0.1:8090";

    // 单次工具调用最大等待秒数（含 Skill + CLI、电商 MCP），超时后中断本次调用并返回失败。
    private int toolTimeoutSeconds = 90;

    // 工具执行线程池大小，避免工具卡住时拖死 Agent 循环。
    private int toolExecutorThreads = 8;

    // 允许触发工具执行的飞书 open_id / user_id 白名单，多个用逗号分隔；为空表示不限制。
    private String toolAllowedOpenIds = "";

    // 允许触发工具执行的飞书会话 ID 白名单，多个用逗号分隔；为空表示不限制。
    private String toolAllowedChatIds = "";

    // 飞书 Agent 业务线程池核心线程数。
    private int agentCorePoolSize = 4;

    // 飞书 Agent 业务线程池最大线程数。
    private int agentMaxPoolSize = 8;

    // 飞书 Agent 业务线程池队列容量，超限后直接拒绝并回复用户稍后再试。
    private int agentQueueCapacity = 100;

    // 飞书 OpenAPI 连接超时秒数。
    private int openApiConnectTimeoutSeconds = 3;

    // 飞书 OpenAPI 读取超时秒数。
    private int openApiReadTimeoutSeconds = 20;

    // 电商 MCP 连接超时秒数。
    private int ecommerceMcpConnectTimeoutSeconds = 3;

    // 电商 MCP 读取超时秒数。
    private int ecommerceMcpReadTimeoutSeconds = 15;

    public String getBaseUrl() {
        // 返回飞书 OpenAPI 基础地址。
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        // 设置飞书 OpenAPI 基础地址。
        this.baseUrl = baseUrl;
    }

    public String getAppId() {
        // 返回飞书应用 app_id。
        return appId;
    }

    public void setAppId(String appId) {
        // 设置飞书应用 app_id。
        this.appId = appId;
    }

    public String getAppSecret() {
        // 返回飞书应用 app_secret。
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        // 设置飞书应用 app_secret。
        this.appSecret = appSecret;
    }

    public String getVerificationToken() {
        // 返回飞书事件 verification token。
        return verificationToken;
    }

    public void setVerificationToken(String verificationToken) {
        // 设置飞书事件 verification token。
        this.verificationToken = verificationToken;
    }

    public boolean isProcessingReplyEnabled() {
        // 返回是否开启处理中提示。
        return processingReplyEnabled;
    }

    public void setProcessingReplyEnabled(boolean processingReplyEnabled) {
        // 设置是否开启处理中提示。
        this.processingReplyEnabled = processingReplyEnabled;
    }

    public String getProcessingReplyText() {
        // 返回处理中提示文案。
        return processingReplyText;
    }

    public void setProcessingReplyText(String processingReplyText) {
        // 设置处理中提示文案。
        this.processingReplyText = processingReplyText;
    }

    public String getProcessingReactionTypes() {
        // 返回处理中表情配置。
        return processingReactionTypes;
    }

    public void setProcessingReactionTypes(String processingReactionTypes) {
        // 设置处理中表情配置。
        this.processingReactionTypes = processingReactionTypes;
    }

    public String getOauthRedirectUri() {
        // 返回 OAuth 回调地址。
        return oauthRedirectUri;
    }

    public void setOauthRedirectUri(String oauthRedirectUri) {
        // 设置 OAuth 回调地址。
        this.oauthRedirectUri = oauthRedirectUri;
    }

    public String getOauthAuthorizeBaseUrl() {
        // 返回 OAuth 授权页基础地址。
        return oauthAuthorizeBaseUrl;
    }

    public void setOauthAuthorizeBaseUrl(String oauthAuthorizeBaseUrl) {
        // 设置 OAuth 授权页基础地址。
        this.oauthAuthorizeBaseUrl = oauthAuthorizeBaseUrl;
    }

    public String getOauthDefaultScopes() {
        // 返回 OAuth 默认 scope。
        return oauthDefaultScopes;
    }

    public void setOauthDefaultScopes(String oauthDefaultScopes) {
        // 设置 OAuth 默认 scope。
        this.oauthDefaultScopes = oauthDefaultScopes;
    }

    public int getOauthRefreshBeforeSeconds() {
        // 返回 token 提前刷新秒数。
        return oauthRefreshBeforeSeconds;
    }

    public void setOauthRefreshBeforeSeconds(int oauthRefreshBeforeSeconds) {
        // 设置 token 提前刷新秒数，最小值保护为 60 秒。
        this.oauthRefreshBeforeSeconds = Math.max(60, oauthRefreshBeforeSeconds);
    }

    public boolean isLlmEnabled() {
        // 返回是否启用 LLM 意图识别。
        return llmEnabled;
    }

    public void setLlmEnabled(boolean llmEnabled) {
        // 设置是否启用 LLM 意图识别。
        this.llmEnabled = llmEnabled;
    }

    public String getLlmBaseUrl() {
        // 返回 OpenAI 兼容接口地址。
        return llmBaseUrl;
    }

    public void setLlmBaseUrl(String llmBaseUrl) {
        // 设置 OpenAI 兼容接口地址。
        this.llmBaseUrl = llmBaseUrl;
    }

    public String getLlmApiKey() {
        // 返回大模型 API Key。
        return llmApiKey;
    }

    public void setLlmApiKey(String llmApiKey) {
        // 设置大模型 API Key。
        this.llmApiKey = llmApiKey;
    }

    public String getLlmModelName() {
        // 返回大模型名称。
        return llmModelName;
    }

    public void setLlmModelName(String llmModelName) {
        // 设置大模型名称。
        this.llmModelName = llmModelName;
    }

    public double getLlmTemperature() {
        // 返回模型温度。
        return llmTemperature;
    }

    public void setLlmTemperature(double llmTemperature) {
        // 设置模型温度。
        this.llmTemperature = llmTemperature;
    }

    public boolean isMemoryEnabled() {
        // 返回是否启用会话记忆。
        return memoryEnabled;
    }

    public void setMemoryEnabled(boolean memoryEnabled) {
        // 设置是否启用会话记忆。
        this.memoryEnabled = memoryEnabled;
    }

    public int getMemoryMaxMessages() {
        // 返回最大记忆消息条数。
        return memoryMaxMessages;
    }

    public void setMemoryMaxMessages(int memoryMaxMessages) {
        // 设置最大记忆消息条数，最小值保护为 2。
        this.memoryMaxMessages = Math.max(2, memoryMaxMessages);
    }

    public int getMemoryCompressThreshold() {
        // 返回触发压缩的消息条数。
        return Math.max(getMemoryRecentMessages() + 2, memoryCompressThreshold);
    }

    public void setMemoryCompressThreshold(int memoryCompressThreshold) {
        // 设置触发压缩的消息条数，最小值保护为 4。
        this.memoryCompressThreshold = Math.max(4, memoryCompressThreshold);
    }

    public int getMemoryRecentMessages() {
        // 返回压缩后保留的最近消息条数。
        return Math.max(2, memoryRecentMessages);
    }

    public void setMemoryRecentMessages(int memoryRecentMessages) {
        // 设置压缩后保留的最近消息条数，最小值保护为 2。
        this.memoryRecentMessages = Math.max(2, memoryRecentMessages);
    }

    public int getMemoryCompressBatchSize() {
        // 返回单次压缩的最大消息条数。
        return Math.max(2, memoryCompressBatchSize);
    }

    public void setMemoryCompressBatchSize(int memoryCompressBatchSize) {
        // 设置单次压缩的最大消息条数，最小值保护为 2。
        this.memoryCompressBatchSize = Math.max(2, memoryCompressBatchSize);
    }

    public int getMemorySummaryMaxChars() {
        // 返回摘要最大字符数。
        return Math.max(500, memorySummaryMaxChars);
    }

    public void setMemorySummaryMaxChars(int memorySummaryMaxChars) {
        // 设置摘要最大字符数，最小值保护为 500。
        this.memorySummaryMaxChars = Math.max(500, memorySummaryMaxChars);
    }

    public boolean isCliEnabled() {
        // 返回是否启用 Skill + CLI。
        return cliEnabled;
    }

    public void setCliEnabled(boolean cliEnabled) {
        // 设置是否启用 Skill + CLI。
        this.cliEnabled = cliEnabled;
    }

    public String getCliCommand() {
        // 返回 lark-cli 命令路径。
        return cliCommand;
    }

    public void setCliCommand(String cliCommand) {
        // 设置 lark-cli 命令路径。
        this.cliCommand = cliCommand;
    }

    public int getCliMaxSteps() {
        // 返回 CLI 最大执行轮数。
        return cliMaxSteps;
    }

    public void setCliMaxSteps(int cliMaxSteps) {
        // 设置 CLI 最大执行轮数，最小值保护为 1。
        this.cliMaxSteps = Math.max(1, cliMaxSteps);
    }

    public int getCliTimeoutSeconds() {
        // 返回 CLI 单命令超时时间。
        return cliTimeoutSeconds;
    }

    public void setCliTimeoutSeconds(int cliTimeoutSeconds) {
        // 设置 CLI 单命令超时时间，最小值保护为 5 秒。
        this.cliTimeoutSeconds = Math.max(5, cliTimeoutSeconds);
    }

    public String getCliAllowedDomains() {
        // 返回允许的 CLI 业务域。
        return cliAllowedDomains;
    }

    public void setCliAllowedDomains(String cliAllowedDomains) {
        // 设置允许的 CLI 业务域。
        this.cliAllowedDomains = cliAllowedDomains;
    }

    public boolean isEcommerceMcpEnabled() {
        // 返回是否启用电商 MCP。
        return ecommerceMcpEnabled;
    }

    public void setEcommerceMcpEnabled(boolean ecommerceMcpEnabled) {
        // 设置是否启用电商 MCP。
        this.ecommerceMcpEnabled = ecommerceMcpEnabled;
    }

    public String getEcommerceMcpBaseUrl() {
        // 返回电商 MCP 服务地址。
        return ecommerceMcpBaseUrl;
    }

    public void setEcommerceMcpBaseUrl(String ecommerceMcpBaseUrl) {
        // 设置电商 MCP 服务地址。
        this.ecommerceMcpBaseUrl = ecommerceMcpBaseUrl;
    }

    public int getToolTimeoutSeconds() {
        // 返回工具调用超时秒数。
        return toolTimeoutSeconds;
    }

    public void setToolTimeoutSeconds(int toolTimeoutSeconds) {
        // 设置工具调用超时秒数，最小值保护为 1 秒。
        this.toolTimeoutSeconds = Math.max(1, toolTimeoutSeconds);
    }

    public int getToolExecutorThreads() {
        // 返回工具执行线程数。
        return toolExecutorThreads;
    }

    public void setToolExecutorThreads(int toolExecutorThreads) {
        // 设置工具执行线程数，最小值保护为 2。
        this.toolExecutorThreads = Math.max(2, toolExecutorThreads);
    }

    public String getToolAllowedOpenIds() {
        // 返回工具调用调用者白名单。
        return toolAllowedOpenIds;
    }

    public void setToolAllowedOpenIds(String toolAllowedOpenIds) {
        // 设置工具调用调用者白名单。
        this.toolAllowedOpenIds = toolAllowedOpenIds == null ? "" : toolAllowedOpenIds;
    }

    public String getToolAllowedChatIds() {
        // 返回工具调用会话白名单。
        return toolAllowedChatIds;
    }

    public void setToolAllowedChatIds(String toolAllowedChatIds) {
        // 设置工具调用会话白名单。
        this.toolAllowedChatIds = toolAllowedChatIds == null ? "" : toolAllowedChatIds;
    }

    public int getAgentCorePoolSize() {
        // 返回业务线程池核心线程数。
        return agentCorePoolSize;
    }

    public void setAgentCorePoolSize(int agentCorePoolSize) {
        // 设置核心线程数，最小值保护为 1。
        this.agentCorePoolSize = Math.max(1, agentCorePoolSize);
    }

    public int getAgentMaxPoolSize() {
        // 返回业务线程池最大线程数。
        return agentMaxPoolSize;
    }

    public void setAgentMaxPoolSize(int agentMaxPoolSize) {
        // 设置最大线程数，不能小于核心线程数。
        this.agentMaxPoolSize = Math.max(agentCorePoolSize, agentMaxPoolSize);
    }

    public int getAgentQueueCapacity() {
        // 返回业务线程池队列容量。
        return agentQueueCapacity;
    }

    public void setAgentQueueCapacity(int agentQueueCapacity) {
        // 设置队列容量，最小值保护为 1。
        this.agentQueueCapacity = Math.max(1, agentQueueCapacity);
    }

    public int getOpenApiConnectTimeoutSeconds() {
        // 返回飞书 OpenAPI 连接超时秒数。
        return openApiConnectTimeoutSeconds;
    }

    public void setOpenApiConnectTimeoutSeconds(int openApiConnectTimeoutSeconds) {
        // 设置连接超时秒数，最小值保护为 1 秒。
        this.openApiConnectTimeoutSeconds = Math.max(1, openApiConnectTimeoutSeconds);
    }

    public int getOpenApiReadTimeoutSeconds() {
        // 返回飞书 OpenAPI 读取超时秒数。
        return openApiReadTimeoutSeconds;
    }

    public void setOpenApiReadTimeoutSeconds(int openApiReadTimeoutSeconds) {
        // 设置读取超时秒数，最小值保护为 1 秒。
        this.openApiReadTimeoutSeconds = Math.max(1, openApiReadTimeoutSeconds);
    }

    public int getEcommerceMcpConnectTimeoutSeconds() {
        // 返回电商 MCP 连接超时秒数。
        return ecommerceMcpConnectTimeoutSeconds;
    }

    public void setEcommerceMcpConnectTimeoutSeconds(int ecommerceMcpConnectTimeoutSeconds) {
        // 设置连接超时秒数，最小值保护为 1 秒。
        this.ecommerceMcpConnectTimeoutSeconds = Math.max(1, ecommerceMcpConnectTimeoutSeconds);
    }

    public int getEcommerceMcpReadTimeoutSeconds() {
        // 返回电商 MCP 读取超时秒数。
        return ecommerceMcpReadTimeoutSeconds;
    }

    public void setEcommerceMcpReadTimeoutSeconds(int ecommerceMcpReadTimeoutSeconds) {
        // 设置读取超时秒数，最小值保护为 1 秒。
        this.ecommerceMcpReadTimeoutSeconds = Math.max(1, ecommerceMcpReadTimeoutSeconds);
    }
}
