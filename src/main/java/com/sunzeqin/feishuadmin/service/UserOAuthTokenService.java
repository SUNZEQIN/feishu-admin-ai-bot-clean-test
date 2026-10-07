package com.sunzeqin.feishuadmin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.pojo.FeishuMessageEvent;
import com.sunzeqin.feishuadmin.pojo.UserOAuthToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 飞书用户授权服务。
 *
 * <p>作用：生成用户授权链接、保存用户 token、判断 scope 是否满足，并定时刷新 token。</p>
 *
 * @author sunzeqin
 */
@Service
public class UserOAuthTokenService {
    // 当前服务使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(UserOAuthTokenService.class);

    // 飞书配置。
    private final FeishuProperties properties;

    // 飞书 OpenAPI 基础服务。
    private final FeishuOpenApiService openApiService;

    // 数据库工具。
    private final JdbcTemplate jdbcTemplate;

    // HTTP 客户端。
    private final RestClient restClient;

    public UserOAuthTokenService(FeishuProperties properties, FeishuOpenApiService openApiService,
            JdbcTemplate jdbcTemplate, RestClient.Builder builder) {
        // 保存配置。
        this.properties = properties;

        // 保存 OpenAPI 服务。
        this.openApiService = openApiService;

        // 保存 JDBC 工具。
        this.jdbcTemplate = jdbcTemplate;

        // 创建飞书 HTTP 客户端。
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
    }

    /**
     * 生成授权链接。
     *
     * @param event     当前消息事件
     * @param scopeText 需要申请的scope
     * @return 授权链接
     */
    public String createAuthorizeUrl(FeishuMessageEvent event, String scopeText) {
        // 规范化 scope。
        String normalizedScope = normalizeScopes(scopeText);

        // 如果没有识别到 scope，就使用默认 scope。
        if (normalizedScope.isBlank()) {
            normalizedScope = normalizeScopes(properties.getOauthDefaultScopes());
        }

        // 检查回调地址是否配置。
        if (properties.getOauthRedirectUri() == null || properties.getOauthRedirectUri().isBlank()) {
            throw new IllegalStateException("FEISHU_OAUTH_REDIRECT_URI 未配置，无法生成用户授权链接");
        }

        // 生成随机 state。
        String state = UUID.randomUUID().toString().replace("-", "");

        // 保存 state，回调时用来定位用户和 scope。
        jdbcTemplate.update("""
                        INSERT INTO feishu_oauth_state
                        (state, app_id, user_open_id, user_id, chat_id, message_id, scope_text, used, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 0, NOW())
                        """,
                state,
                safe(properties.getAppId()),
                safe(event.openId()),
                safe(event.userId()),
                safe(event.chatId()),
                safe(event.messageId()),
                normalizedScope);

        // 拼接飞书 OAuth 授权链接，scope 字段必须带上。
        String url = UriComponentsBuilder.fromUriString(properties.getOauthAuthorizeBaseUrl())
                .path("/open-apis/authen/v1/authorize")
                .queryParam("client_id", properties.getAppId())
                .queryParam("redirect_uri", properties.getOauthRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", normalizedScope)
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();

        // 打印授权链接摘要，不打印 token。
        log.info("[工具调用] 生成用户授权链接：用户openId={}，scope={}，state={}",
                event.openId(), normalizedScope, state);

        // 返回授权链接。
        return url;
    }

    /**
     * 判断数据库中用户 token 是否满足 scope。
     *
     * @param userOpenId 用户open_id
     * @param scopeText  需要的scope
     * @return true 表示满足
     */
    public boolean tokenHasScopes(String userOpenId, String scopeText) {
        // 没有用户 open_id 时不能判断。
        if (userOpenId == null || userOpenId.isBlank()) {
            return false;
        }

        // 读取用户 token。
        UserOAuthToken token = findToken(userOpenId);
        if (token == null) {
            return false;
        }

        // 判断 scope 是否覆盖。
        return containsAllScopes(token.scopeText(), scopeText);
    }

    /**
     * 读取给 lark-cli 使用的用户 token。
     *
     * <p>作用：当 CLI 需要 --as user 执行业务命令时，从数据库读取用户 access_token。
     * 如果 token 快过期，会先尝试刷新一次。</p>
     *
     * @param userOpenId 用户open_id
     * @return 可用 token；没有授权或刷新失败时返回 null
     */
    public UserOAuthToken findUsableTokenForCli(String userOpenId) {
        // 没有用户 open_id 时无法定位 token。
        if (userOpenId == null || userOpenId.isBlank()) {
            return null;
        }

        // 读取数据库中的 token。
        UserOAuthToken token = findToken(userOpenId);
        if (token == null) {
            log.info("[工具调用] 用户token查询为空：用户openId={}", userOpenId);
            return null;
        }

        // access_token 为空时不能给 CLI 使用。
        if (token.accessToken() == null || token.accessToken().isBlank()) {
            log.warn("[工具调用] 用户token不可用：用户openId={}，原因=access_token为空", userOpenId);
            return null;
        }

        // token 快过期时先刷新。
        if (token.expiresAt() != null
                && Instant.now().isAfter(token.expiresAt().minusSeconds(properties.getOauthRefreshBeforeSeconds()))) {
            log.info("[工具调用] 用户token即将过期，准备刷新：用户openId={}，过期时间={}",
                    userOpenId, token.expiresAt());
            refreshToken(token);
            token = findToken(userOpenId);
        }

        // 刷新后仍然没有 token 时返回空。
        if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
            return null;
        }

        // 返回可用 token。
        return token;
    }

    /**
     * 用授权码换用户 token 并保存。
     *
     * @param code  飞书回调 code
     * @param state 飞书回调 state
     */
    public void handleCallback(String code, String state) {
        // 参数校验。
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("OAuth callback 缺少 code");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("OAuth callback 缺少 state");
        }

        // 读取 state 记录。
        OAuthState oauthState = readState(state);

        // 用 code 换用户 token。
        JsonNode response = restClient.post()
                .uri("/open-apis/authen/v1/access_token")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + openApiService.appAccessTokenForOAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("grant_type", "authorization_code", "code", code))
                .retrieve()
                .body(JsonNode.class);

        // 打印响应摘要，不打印 token。
        log.info("[工具调用] 用户授权换token响应：state={}，状态码={}，消息={}",
                state, response.path("code").asInt(-1), response.path("msg").asText(""));

        // 检查返回结果。
        ensureOk(response, "用户授权换 token 失败");

        // 保存 token。
        saveTokenFromResponse(oauthState, response.path("data"));

        // 标记 state 已使用。
        jdbcTemplate.update("UPDATE feishu_oauth_state SET used = 1 WHERE state = ?", state);
    }

    /**
     * 定时刷新快过期的用户 token。
     */
    @Scheduled(fixedDelayString = "300000")
    public void refreshExpiringTokens() {
        // 查询即将过期且 refresh_token 不为空的 token。
        List<UserOAuthToken> tokens = jdbcTemplate.query("""
                        SELECT app_id, user_open_id, user_id, scope_text, access_token, refresh_token, expires_at, refresh_expires_at
                        FROM feishu_user_oauth_token
                        WHERE expires_at IS NOT NULL
                          AND refresh_token <> ''
                          AND expires_at <= DATE_ADD(NOW(), INTERVAL ? SECOND)
                        LIMIT 50
                        """,
                (resultSet, rowNumber) -> new UserOAuthToken(
                        resultSet.getString("app_id"),
                        resultSet.getString("user_open_id"),
                        resultSet.getString("user_id"),
                        resultSet.getString("scope_text"),
                        resultSet.getString("access_token"),
                        resultSet.getString("refresh_token"),
                        toInstant(resultSet.getTimestamp("expires_at")),
                        toInstant(resultSet.getTimestamp("refresh_expires_at"))
                ),
                properties.getOauthRefreshBeforeSeconds());

        // 遍历刷新。
        for (UserOAuthToken token : tokens) {
            refreshToken(token);
        }
    }

    private void refreshToken(UserOAuthToken token) {
        try {
            // refresh_token 已经过期时不刷新。
            if (token.refreshExpiresAt() != null && Instant.now().isAfter(token.refreshExpiresAt())) {
                log.warn("[工具调用] 用户token刷新跳过：用户openId={}，原因=refresh_token已过期", token.userOpenId());
                return;
            }

            // 调用刷新接口。
            JsonNode response = restClient.post()
                    .uri("/open-apis/authen/v1/refresh_access_token")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + openApiService.appAccessTokenForOAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("grant_type", "refresh_token", "refresh_token", token.refreshToken()))
                    .retrieve()
                    .body(JsonNode.class);

            // 打印刷新摘要。
            log.info("[工具调用] 用户token刷新响应：用户openId={}，状态码={}，消息={}",
                    token.userOpenId(), response.path("code").asInt(-1), response.path("msg").asText(""));

            // 检查返回结果。
            ensureOk(response, "刷新用户 token 失败");

            // 更新数据库。
            saveTokenFromData(token.userOpenId(), token.userId(), response.path("data"), token.scopeText());
        } catch (Exception e) {
            // 单个用户刷新失败不影响其它用户。
            log.warn("[工具调用] 用户token刷新失败：用户openId={}，错误={}", token.userOpenId(), e.getMessage());
        }
    }

    private void saveTokenFromResponse(OAuthState oauthState, JsonNode data) {
        // 回调返回里优先使用飞书返回的用户身份，没有时用 state 里的用户身份。
        String userOpenId = firstNotBlank(data.path("open_id").asText(""), oauthState.userOpenId());
        String userId = firstNotBlank(data.path("user_id").asText(""), oauthState.userId());

        // 保存 token。
        saveTokenFromData(userOpenId, userId, data, oauthState.scopeText());
    }

    private void saveTokenFromData(String userOpenId, String userId, JsonNode data, String fallbackScope) {
        // 读取 token 字段。
        String accessToken = data.path("access_token").asText("");
        String refreshToken = data.path("refresh_token").asText("");
        String scopeText = mergeScopes(fallbackScope, data.path("scope").asText(""));

        // 计算过期时间。
        Instant expiresAt = Instant.now().plusSeconds(data.path("expires_in").asLong(data.path("expire").asLong(7200)));
        Instant refreshExpiresAt = Instant.now().plusSeconds(data.path("refresh_expires_in").asLong(30L * 24 * 3600));

        // 写入或更新用户 token。
        jdbcTemplate.update("""
                        INSERT INTO feishu_user_oauth_token
                        (app_id, user_open_id, user_id, scope_text, access_token, refresh_token, expires_at, refresh_expires_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                        ON DUPLICATE KEY UPDATE
                            user_id = VALUES(user_id),
                            scope_text = VALUES(scope_text),
                            access_token = VALUES(access_token),
                            refresh_token = VALUES(refresh_token),
                            expires_at = VALUES(expires_at),
                            refresh_expires_at = VALUES(refresh_expires_at),
                            updated_at = NOW()
                        """,
                properties.getAppId(),
                safe(userOpenId),
                safe(userId),
                normalizeScopes(scopeText),
                safe(accessToken),
                safe(refreshToken),
                Timestamp.from(expiresAt),
                Timestamp.from(refreshExpiresAt));

        // 打印保存日志，不打印 token 明文。
        log.info("[工具调用] 用户授权token已保存：用户openId={}，scope={}", userOpenId, normalizeScopes(scopeText));
    }

    private UserOAuthToken findToken(String userOpenId) {
        // 查询用户 token。
        List<UserOAuthToken> tokens = jdbcTemplate.query("""
                        SELECT app_id, user_open_id, user_id, scope_text, access_token, refresh_token, expires_at, refresh_expires_at
                        FROM feishu_user_oauth_token
                        WHERE app_id = ? AND user_open_id = ?
                        LIMIT 1
                        """,
                (resultSet, rowNumber) -> new UserOAuthToken(
                        resultSet.getString("app_id"),
                        resultSet.getString("user_open_id"),
                        resultSet.getString("user_id"),
                        resultSet.getString("scope_text"),
                        resultSet.getString("access_token"),
                        resultSet.getString("refresh_token"),
                        toInstant(resultSet.getTimestamp("expires_at")),
                        toInstant(resultSet.getTimestamp("refresh_expires_at"))
                ),
                properties.getAppId(),
                userOpenId);

        // 没有记录返回 null。
        if (tokens.isEmpty()) {
            return null;
        }

        // 返回第一条。
        return tokens.get(0);
    }

    private OAuthState readState(String state) {
        // 查询 state 记录。
        List<OAuthState> states = jdbcTemplate.query("""
                        SELECT state, app_id, user_open_id, user_id, chat_id, message_id, scope_text, used
                        FROM feishu_oauth_state
                        WHERE state = ?
                        LIMIT 1
                        """,
                (resultSet, rowNumber) -> new OAuthState(
                        resultSet.getString("state"),
                        resultSet.getString("app_id"),
                        resultSet.getString("user_open_id"),
                        resultSet.getString("user_id"),
                        resultSet.getString("chat_id"),
                        resultSet.getString("message_id"),
                        resultSet.getString("scope_text"),
                        resultSet.getInt("used") == 1
                ),
                state);

        // 未找到 state 时抛错。
        if (states.isEmpty()) {
            // 如果收到的 state 比系统生成的 32 位短，通常是飞书消息过长导致授权链接被截断。
            int prefixMatchCount = countStatePrefix(state);
            log.warn("[工具调用] OAuth state未找到：收到state长度={}，疑似前缀匹配数量={}，可能原因=授权链接被截断或链接已失效",
                    state.length(), prefixMatchCount);
            throw new IllegalArgumentException("OAuth state 不存在或已过期");
        }

        // 已使用时抛错。
        OAuthState oauthState = states.get(0);
        if (oauthState.used()) {
            throw new IllegalArgumentException("OAuth state 已使用，请重新发起授权");
        }

        // 返回 state。
        return oauthState;
    }

    private int countStatePrefix(String state) {
        // 空 state 不查询。
        if (state == null || state.isBlank()) {
            return 0;
        }

        // 查询是否存在以收到 state 为前缀的记录，用来判断链接是否被截断。
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(1)
                        FROM feishu_oauth_state
                        WHERE state LIKE CONCAT(?, '%')
                        """,
                Integer.class,
                state);

        // queryForObject 理论上不会返回 null，这里做兜底。
        return count == null ? 0 : count;
    }

    private boolean containsAllScopes(String ownedScopeText, String requiredScopeText) {
        // 读取已有 scope。
        Set<String> ownedScopes = parseScopes(ownedScopeText);

        // 遍历必需 scope。
        for (String requiredScope : parseScopes(requiredScopeText)) {
            if (!ownedScopes.contains(requiredScope)) {
                return false;
            }
        }

        // 全部包含。
        return true;
    }

    private String normalizeScopes(String scopeText) {
        // 去重并保持顺序。
        return String.join(" ", parseScopes(scopeText));
    }

    private String mergeScopes(String firstScopeText, String secondScopeText) {
        // 合并本次申请 scope 和飞书返回 scope，避免飞书只返回部分字段导致下次重复授权。
        Set<String> scopes = new LinkedHashSet<>();

        // 添加第一组 scope。
        scopes.addAll(parseScopes(firstScopeText));

        // 添加第二组 scope。
        scopes.addAll(parseScopes(secondScopeText));

        // 返回空格分隔 scope。
        return String.join(" ", scopes);
    }

    private Set<String> parseScopes(String scopeText) {
        // 保存 scope。
        Set<String> scopes = new LinkedHashSet<>();

        // 空 scope 返回空集合。
        if (scopeText == null || scopeText.isBlank()) {
            return scopes;
        }

        // 支持逗号、空格、换行混合分隔。
        String[] parts = scopeText.split("[,\\s]+");
        for (String part : parts) {
            String scope = part.trim();
            if (!scope.isBlank()) {
                scopes.add(scope);
            }
        }

        // 返回 scope 集合。
        return scopes;
    }

    private void ensureOk(JsonNode response, String message) {
        // code 为 0 表示成功。
        int code = response == null ? -1 : response.path("code").asInt(-1);
        if (code != 0) {
            String detail = response == null ? "empty response" : response.toString();
            throw new IllegalStateException(message + "：" + detail);
        }
    }

    private String firstNotBlank(String first, String second) {
        // 返回第一个非空字符串。
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return "";
    }

    private String safe(String value) {
        // 空字符串保护。
        return value == null ? "" : value;
    }

    private Instant toInstant(Timestamp timestamp) {
        // 空时间返回 null。
        if (timestamp == null) {
            return null;
        }

        // 转成 Instant。
        return timestamp.toInstant();
    }

    /**
     * OAuth state 数据。
     *
     * @author sunzeqin
     */
    private record OAuthState(String state, String appId, String userOpenId, String userId,
                              String chatId, String messageId, String scopeText, boolean used) {
    }
}
