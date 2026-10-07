package com.sunzeqin.feishuadmin.service;

import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.pojo.ConversationMemoryMessage;
import com.sunzeqin.feishuadmin.pojo.FeishuMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 会话记忆服务。
 *
 * <p>作用：把真实对话消息保存到 MySQL。用户消息保存一条，机器人回复保存一条。</p>
 *
 * @author sunzeqin
 */
@Service
public class ConversationMemoryService {
    // 当前服务使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    // 群聊记忆范围。
    private static final String GROUP_SCOPE = "GROUP";

    // 私聊记忆范围。
    private static final String PRIVATE_SCOPE = "PRIVATE";

    // 飞书配置，用来读取记忆开关和最大记忆条数。
    private final FeishuProperties properties;

    // Spring JDBC 工具，用来读写 MySQL。
    private final JdbcTemplate jdbcTemplate;

    public ConversationMemoryService(FeishuProperties properties, JdbcTemplate jdbcTemplate) {
        // 保存飞书配置。
        this.properties = properties;

        // 保存 JDBC 工具。
        this.jdbcTemplate = jdbcTemplate;
    }

    public String readMemoryText(FeishuMessageEvent event) {
        // 如果记忆功能关闭，就返回空字符串。
        if (!properties.isMemoryEnabled()) {
            return "";
        }

        // 获取当前会话的记忆范围。
        String scope = chatMemoryScope(event);

        // 获取当前会话的记忆键。
        String memoryKey = chatMemoryKey(event);

        // 读取压缩后的历史摘要。
        String summary = readSummary(scope, memoryKey);

        // 按当前会话读取最近记忆。群聊和私聊都以 chat_id 为主线，不重复存储两份。
        List<ConversationMemoryMessage> messages = readMessages(scope, memoryKey, properties.getMemoryRecentMessages());

        // 保存提示词里的记忆文本。
        StringBuilder builder = new StringBuilder();

        // 写入会话记忆标题。
        builder.append("【会话记忆】\n");

        // 追加压缩摘要。
        appendSummary(builder, summary);

        // 追加会话记忆内容。
        appendMessages(builder, messages);

        // 打印记忆读取摘要，帮助确认实际注入了哪些历史内容。
        String memoryText = builder.toString();
        log.info("会话记忆读取：消息ID={}，范围={}，记忆Key={}，摘要长度={}，近期条数={}，记忆文本长度={}，记忆文本摘要={}",
                event.messageId(), scope, memoryKey, summary.length(), messages.size(),
                memoryText.length(), preview(memoryText));
        log.debug("会话记忆读取完整内容：消息ID={}，记忆文本={}", event.messageId(), memoryText);

        // 返回历史记忆文本。
        return memoryText;
    }

    public void saveUserMessage(FeishuMessageEvent event) {
        // 保存用户真实消息。只落一行，不再同时写个人记忆和群聊记忆。
        save(event, "user", event.text());
    }

    public void saveAssistantMessage(FeishuMessageEvent event, String reply) {
        // 保存机器人真实回复。只落一行，不再同时写个人记忆和群聊记忆。
        save(event, "assistant", reply);
    }

    private List<ConversationMemoryMessage> readMessages(String scope, String memoryKey, int limit) {
        // 查询最近 N 条记忆，先倒序取，后面再反转成时间正序。
        List<ConversationMemoryMessage> messages = jdbcTemplate.query("""
                        SELECT role, content, created_at
                        FROM agent_conversation_memory
                        WHERE memory_scope = ? AND memory_key = ?
                        ORDER BY id DESC
                        LIMIT ?
                        """,
                (resultSet, rowNumber) -> new ConversationMemoryMessage(
                        resultSet.getString("role"),
                        resultSet.getString("content"),
                        toInstant(resultSet.getTimestamp("created_at"))
                ),
                scope,
                memoryKey,
                limit);

        // 反转成从旧到新的顺序，让模型按正常对话顺序阅读。
        Collections.reverse(messages);

        // 返回记忆列表。
        return messages;
    }

    private void save(FeishuMessageEvent event, String role, String content) {
        // 如果记忆功能关闭，就不保存。
        if (!properties.isMemoryEnabled()) {
            return;
        }

        // 空内容不保存。
        if (content == null || content.isBlank()) {
            return;
        }

        // 获取当前会话的记忆范围。
        String scope = chatMemoryScope(event);

        // 获取当前会话的记忆键。
        String memoryKey = chatMemoryKey(event);

        // 写入一条真实消息。
        jdbcTemplate.update("""
                        INSERT INTO agent_conversation_memory
                        (memory_scope, memory_key, chat_id, user_open_id, user_id, role, content, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                        """,
                scope,
                memoryKey,
                safe(event.chatId()),
                safe(event.openId()),
                safe(event.userId()),
                role,
                content);

        // 如果消息超过阈值，把旧消息压缩进摘要。
        compressOldMessages(scope, memoryKey, safe(event.chatId()));

        // 打印记忆写入摘要，区分用户消息和机器人回复，并确认实际写入内容。
        log.info("会话记忆写入：消息ID={}，范围={}，记忆Key={}，角色={}，内容长度={}，内容摘要={}",
                event.messageId(), scope, memoryKey, role, content.length(), preview(content));
        log.debug("会话记忆写入完整内容：消息ID={}，角色={}，内容={}", event.messageId(), role, content);
    }

    private void compressOldMessages(String scope, String memoryKey, String chatId) {
        // 查询当前会话消息总数。
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM agent_conversation_memory
                WHERE memory_scope = ? AND memory_key = ?
                """, Integer.class, scope, memoryKey);

        // 空值保护。
        int total = count == null ? 0 : count;

        // 不到压缩阈值就不处理。
        if (total <= properties.getMemoryCompressThreshold()) {
            return;
        }

        // 计算本次要压缩的旧消息数量。
        int overflow = total - properties.getMemoryRecentMessages();
        int batchSize = Math.min(overflow, properties.getMemoryCompressBatchSize());
        if (batchSize <= 0) {
            return;
        }

        // 读取最旧的一批消息。
        List<MemoryRow> rows = readOldestRows(scope, memoryKey, batchSize);
        if (rows.isEmpty()) {
            return;
        }

        // 读取旧摘要。
        String oldSummary = readSummary(scope, memoryKey);

        // 生成新摘要。
        String newSummary = mergeSummary(oldSummary, rows);

        // 保存压缩摘要。
        upsertSummary(scope, memoryKey, chatId, newSummary, rows.size());

        // 删除已经压缩的旧消息。
        deleteCompressedRows(rows);

        // 打印压缩日志。
        log.info("会话记忆压缩：范围={}，记忆Key={}，本次压缩条数={}，摘要长度={}",
                scope, memoryKey, rows.size(), newSummary.length());
    }

    private List<MemoryRow> readOldestRows(String scope, String memoryKey, int limit) {
        // 读取最旧的一批消息，用来压缩成摘要。
        return jdbcTemplate.query("""
                        SELECT id, role, content, created_at
                        FROM agent_conversation_memory
                        WHERE memory_scope = ? AND memory_key = ?
                        ORDER BY id ASC
                        LIMIT ?
                        """,
                (resultSet, rowNumber) -> new MemoryRow(
                        resultSet.getLong("id"),
                        resultSet.getString("role"),
                        resultSet.getString("content"),
                        toInstant(resultSet.getTimestamp("created_at"))
                ),
                scope,
                memoryKey,
                limit);
    }

    private String readSummary(String scope, String memoryKey) {
        // 查询当前会话的压缩摘要。
        List<String> summaries = jdbcTemplate.query("""
                        SELECT summary
                        FROM agent_conversation_summary
                        WHERE memory_scope = ? AND memory_key = ?
                        LIMIT 1
                        """,
                (resultSet, rowNumber) -> resultSet.getString("summary"),
                scope,
                memoryKey);

        // 没有摘要时返回空字符串。
        if (summaries.isEmpty() || summaries.get(0) == null) {
            return "";
        }

        // 返回摘要。
        return summaries.get(0);
    }

    private void upsertSummary(String scope, String memoryKey, String chatId, String summary, int compressedCount) {
        // 插入或更新摘要。
        jdbcTemplate.update("""
                        INSERT INTO agent_conversation_summary
                        (memory_scope, memory_key, chat_id, summary, compressed_message_count, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                        ON DUPLICATE KEY UPDATE
                            chat_id = VALUES(chat_id),
                            summary = VALUES(summary),
                            compressed_message_count = compressed_message_count + VALUES(compressed_message_count),
                            updated_at = NOW()
                        """,
                scope,
                memoryKey,
                chatId,
                summary,
                compressedCount);
    }

    private void deleteCompressedRows(List<MemoryRow> rows) {
        // 逐条删除已经进入摘要的消息，避免动态拼 SQL。
        for (MemoryRow row : rows) {
            jdbcTemplate.update("DELETE FROM agent_conversation_memory WHERE id = ?", row.id());
        }
    }

    private String mergeSummary(String oldSummary, List<MemoryRow> rows) {
        // 保存新的摘要文本。
        StringBuilder builder = new StringBuilder();

        // 先放旧摘要。
        if (oldSummary != null && !oldSummary.isBlank()) {
            builder.append(oldSummary.trim()).append("\n");
        }

        // 写入本次压缩批次。
        builder.append("【历史压缩片段】\n");
        for (MemoryRow row : rows) {
            builder.append(row.role()).append("：").append(row.content()).append("\n");
        }

        // 控制摘要长度，超过上限时保留后半段。
        return limitSummary(builder.toString());
    }

    private String limitSummary(String summary) {
        // 空摘要直接返回空字符串。
        if (summary == null || summary.isBlank()) {
            return "";
        }

        // 获取最大长度。
        int maxChars = properties.getMemorySummaryMaxChars();

        // 未超过上限直接返回。
        if (summary.length() <= maxChars) {
            return summary;
        }

        // 超过上限时保留最近的摘要内容。
        return "【较早摘要已截断】\n" + summary.substring(summary.length() - maxChars);
    }

    private void appendMessages(StringBuilder builder, List<ConversationMemoryMessage> messages) {
        // 没有历史记忆时写入“无”。
        if (messages.isEmpty()) {
            builder.append("近期对话：无\n");
            return;
        }

        // 写入近期对话标题。
        builder.append("近期对话：\n");

        // 遍历历史消息。
        for (ConversationMemoryMessage message : messages) {
            // 拼接角色和内容。
            builder.append(message.role()).append("：").append(message.content()).append("\n");
        }
    }

    private void appendSummary(StringBuilder builder, String summary) {
        // 没有摘要时不写入，避免提示词噪音。
        if (summary == null || summary.isBlank()) {
            builder.append("历史摘要：无\n");
            return;
        }

        // 写入摘要。
        builder.append("历史摘要：\n").append(summary.trim()).append("\n");
    }

    private String chatMemoryKey(FeishuMessageEvent event) {
        // 群聊和私聊都用 chatId 作为会话记忆键。
        return safe(event.chatId());
    }

    private String chatMemoryScope(FeishuMessageEvent event) {
        // group 表示群聊，共享群上下文。
        if ("group".equalsIgnoreCase(event.chatType())) {
            return GROUP_SCOPE;
        }

        // 非 group 都按私聊处理。
        return PRIVATE_SCOPE;
    }

    private String safe(String value) {
        // 空字符串保护，避免数据库 NOT NULL 字段写入 null。
        return value == null ? "" : value;
    }

    private String preview(String value) {
        // INFO 只保留有限长度并压缩换行，完整内容通过 DEBUG 查看。
        if (value == null || value.isBlank()) {
            return "";
        }

        String normalized = value.replaceAll("\\s+", " ").trim();
        int maxLength = 500;
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private Instant toInstant(Timestamp timestamp) {
        // 数据库时间为空时使用当前时间兜底。
        if (timestamp == null) {
            return Instant.now();
        }

        // 转成 Instant。
        return timestamp.toInstant();
    }

    private record MemoryRow(long id, String role, String content, Instant createdAt) {
    }
}
