package com.sunzeqin.feishuadmin.service.tool;

import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.pojo.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工具调用权限校验服务。
 *
 * <p>作用：在工具真正执行前判断“谁在调用、在哪个会话调用”，
 * 防止任意飞书用户通过群聊驱动机器人执行飞书或电商操作。</p>
 *
 * <p>设计取舍：白名单为空时保持原有行为（不限制），避免上线即打断现有机器人；
 * 只要配置了任一白名单，就变成强校验，缺少调用者身份的调用会被拒绝。</p>
 *
 * @author sunzeqin
 */
@Service
public class ToolPermissionService {

    // 当前权限校验服务使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(ToolPermissionService.class);

    // 允许触发工具执行的飞书 open_id / user_id 白名单，空集合表示不限制。
    private final Set<String> allowedOpenIds;

    // 允许触发工具执行的飞书会话 ID 白名单，空集合表示不限制。
    private final Set<String> allowedChatIds;

    // 飞书配置，保存下来方便后续扩展按工具名配置权限。
    private final FeishuProperties properties;

    public ToolPermissionService(FeishuProperties properties) {
        // 保存配置对象。
        this.properties = properties;

        // 解析调用者白名单。
        this.allowedOpenIds = parse(properties.getToolAllowedOpenIds());

        // 解析会话白名单。
        this.allowedChatIds = parse(properties.getToolAllowedChatIds());

        // 启动时打印一次权限策略，方便部署后确认是否生效。
        if (!enforced()) {
            log.warn("[工具调用] 工具调用白名单未配置：当前任意飞书用户都可以触发工具执行，"
                    + "生产环境建议配置 FEISHU_TOOL_ALLOWED_OPEN_IDS 或 FEISHU_TOOL_ALLOWED_CHAT_IDS");
        } else {
            log.info("[工具调用] 工具调用白名单已启用：调用者数量={}，会话数量={}",
                    allowedOpenIds.size(), allowedChatIds.size());
        }
    }

    /**
     * 校验一次工具调用是否有权限执行。
     *
     * @param call 工具调用
     * @return 校验结果，拒绝时带上给用户看的原因
     */
    public Decision check(ToolCall call) {
        // 未配置白名单时不限制，保持原有行为。
        if (!enforced()) {
            return Decision.allow();
        }

        // 空调用直接拒绝，避免绕过校验。
        if (call == null) {
            return Decision.deny("工具调用为空，已拒绝执行");
        }

        // 从工具参数里读取调用者身份。这是 Orchestrator 注入的真实飞书事件上下文。
        String openId = param(call, "senderOpenId");
        String userId = param(call, "senderUserId");
        String chatId = param(call, "sourceChatId");

        // 配置了调用者白名单时，必须命中 open_id 或 user_id。
        if (!allowedOpenIds.isEmpty()) {
            boolean callerAllowed = (!openId.isBlank() && allowedOpenIds.contains(openId))
                    || (!userId.isBlank() && allowedOpenIds.contains(userId));
            if (!callerAllowed) {
                log.warn("[工具调用] 权限拒绝：工具={}，调用者={}，原因=调用者不在白名单内",
                        call.name(), mask(openId));
                return Decision.deny("当前用户没有执行该操作的权限，已拒绝执行");
            }
        }

        // 配置了会话白名单时，必须命中来源会话。
        if (!allowedChatIds.isEmpty() && !allowedChatIds.contains(chatId)) {
            log.warn("[工具调用] 权限拒绝：工具={}，会话={}，调用者={}，原因=会话不在白名单内",
                    call.name(), mask(chatId), mask(openId));
            return Decision.deny("当前会话没有执行该操作的权限，已拒绝执行");
        }

        // 日志里只打印脱敏身份，避免把完整 open_id 刷进日志。
        log.info("[工具调用] 权限通过：工具={}，调用者={}，会话={}, 策略=白名单",
                call.name(), mask(openId), mask(chatId));

        return Decision.allow();
    }

    /**
     * 是否已经启用白名单校验。
     *
     * @return true 表示至少配置了一个白名单
     */
    public boolean enforced() {
        // 任一白名单非空就表示需要校验。
        return !allowedOpenIds.isEmpty() || !allowedChatIds.isEmpty();
    }

    private static Set<String> parse(String raw) {
        // 空配置返回空集合。
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }

        // 按逗号分隔并去空格，保留输入顺序方便排查。
        Set<String> result = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String value = item.trim();
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return Set.copyOf(result);
    }

    private static String param(ToolCall call, String key) {
        // 从参数里取值，空值统一转成空字符串。
        Object value = call.params().get(key);
        return value == null ? "" : value.toString();
    }

    /**
     * 身份脱敏，日志和回复里都不出现完整 open_id。
     *
     * @param id 飞书身份 ID
     * @return 脱敏后的字符串
     */
    public static String mask(String id) {
        // 空值直接返回占位符。
        if (id == null || id.isBlank()) {
            return "未知";
        }

        // 太短直接整体脱敏。
        if (id.length() <= 6) {
            return "***";
        }

        // 保留前 6 位，方便对照日志。
        return id.substring(0, 6) + "***";
    }

    /**
     * 权限校验结果。
     *
     * @param allowed 是否允许执行
     * @param reason  拒绝原因，允许时为空
     */
    public record Decision(boolean allowed, String reason) {

        static Decision allow() {
            // 允许执行。
            return new Decision(true, "");
        }

        static Decision deny(String reason) {
            // 拒绝执行，原因会写进 ToolResult 让 Agent 和用户都能看到。
            return new Decision(false, reason);
        }
    }
}
