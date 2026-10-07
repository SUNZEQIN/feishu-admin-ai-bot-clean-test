package com.sunzeqin.feishuadmin.pojo.agent;

/**
 * Agent 完整执行结果。
 *
 * <p>作用：保存 Agent 多步执行后的最终回复。</p>
 *
 * @param success 是否成功
 * @param reply        回复给飞书用户的文本
 * @param authorizeUrl 授权链接，非空时发送层会转成二维码
 * @param externalMessageSent 是否已经由工具真实发送了飞书消息或卡片
 *
 * @author sunzeqin
 */
public record AgentRunResult(boolean success, String reply, String authorizeUrl, boolean externalMessageSent) {

    public AgentRunResult(boolean success, String reply) {
        // 普通结果没有授权链接。
        this(success, reply, "", false);
    }

    public AgentRunResult(boolean success, String reply, String authorizeUrl) {
        // 兼容授权结果构造方式；授权二维码仍由发送层负责发送。
        this(success, reply, authorizeUrl, false);
    }

    public AgentRunResult {
        // reply 为空时转成空字符串。
        reply = reply == null ? "" : reply;

        // authorizeUrl 为空时转成空字符串。
        authorizeUrl = authorizeUrl == null ? "" : authorizeUrl;
    }
}
