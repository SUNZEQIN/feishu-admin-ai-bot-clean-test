package com.sunzeqin.feishuadmin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 飞书 OpenAPI 服务。
 *
 * <p>作用：集中封装 tenant_access_token 和回复消息等基础飞书接口。</p>
 *
 * @author sunzeqin
 */
@Service
public class FeishuOpenApiService {
    // 当前服务使用的日志对象，主要打印飞书接口入参和执行结果。
    private static final Logger log = LoggerFactory.getLogger(FeishuOpenApiService.class);

    // 飞书应用配置，里面有 app_id、app_secret、baseUrl 等。
    private final FeishuProperties properties;

    // Spring 的 HTTP 客户端，用来请求飞书 OpenAPI。
    private final RestClient restClient;

    // JSON 工具类，用来把飞书消息 content 转成 JSON 字符串。
    private final JsonUtils jsonUtils;

    // 缓存 tenant_access_token，避免每次请求飞书都重新获取 token。
    private volatile String tenantAccessToken = "";

    // 记录 token 过期时间，快过期时自动重新获取。
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    // 缓存 app_access_token，用户 OAuth 换 token 时需要用。
    private volatile String appAccessToken = "";

    // 记录 app_access_token 过期时间。
    private volatile Instant appTokenExpiresAt = Instant.EPOCH;

    public FeishuOpenApiService(FeishuProperties properties, RestClient.Builder builder, JsonUtils jsonUtils) {
        // 保存飞书配置。
        this.properties = properties;

        // 创建带超时的请求工厂：飞书接口变慢时不能让回调线程或业务线程无限等待。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getOpenApiConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getOpenApiReadTimeoutSeconds()));

        // 创建带飞书 baseUrl 和超时的 RestClient。
        this.restClient = builder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build();
        // 保存 JSON 工具类。
        this.jsonUtils = jsonUtils;
    }

    /**
     * 回复飞书消息。
     *
     * @param messageId 原消息 ID
     * @param text      回复文本
     * @return 飞书新回复消息 ID
     */
    public String replyText(String messageId, String text) {
        // 组装回复消息请求体。
        Map<String, Object> body = Map.of("msg_type", "text", "content", jsonUtils.write(Map.of("text", text)));

        // 打印回复消息的真实飞书请求入参。
        log.info("[阶段8 回复飞书] 飞书回复请求：消息ID={}，回复长度={}", messageId, text == null ? 0 : text.length());
        log.debug("[阶段8 回复飞书] 飞书回复完整请求：方法=POST，接口=/open-apis/im/v1/messages/{messageId}/reply，消息ID={}，请求体={}",
                messageId, body);

        // 调用飞书“回复消息”接口，把处理结果回复到原消息下。
        JsonNode response = restClient.post()
                .uri("/open-apis/im/v1/messages/{message_id}/reply", messageId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        // 打印回复消息的飞书响应摘要。
        log.info("[阶段8 回复飞书] 飞书回复响应摘要：消息ID={}，状态码={}，消息={}，新消息ID={}",
                messageId,
                response.path("code").asInt(-1),
                response.path("msg").asText(""),
                response.path("data").path("message_id").asText(""));
        log.debug("[阶段8 回复飞书] 飞书回复完整响应：消息ID={}，响应={}", messageId, response);

        // 检查飞书返回码，避免接口返回失败但上层误认为已经回复成功。
        ensureOk(response, "回复飞书消息失败");

        // 返回新消息 ID，方便上层删除“正在处理”这类临时消息。
        return response.path("data").path("message_id").asText("");
    }

    /**
     * 上传飞书消息图片。
     *
     * @param imageBytes 图片字节
     * @param fileName   文件名
     * @return image_key
     */
    public String uploadMessageImage(byte[] imageBytes, String fileName) {
        // 图片内容不能为空。
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("上传飞书图片失败：图片内容为空");
        }

        // 飞书图片上传接口需要 multipart/form-data。
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("image_type", "message");
        builder.part("image", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return fileName == null || fileName.isBlank() ? "oauth-qrcode.png" : fileName;
            }
        }).contentType(MediaType.IMAGE_PNG);

        // 打印上传摘要，不打印图片二进制。
        log.info("[阶段8 回复飞书] 飞书图片上传请求：文件名={}，大小={}字节",
                fileName, imageBytes.length);

        // 调用飞书上传图片接口。
        JsonNode response = restClient.post()
                .uri("/open-apis/im/v1/images")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAccessToken())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .body(JsonNode.class);

        // 打印响应摘要。
        log.info("[阶段8 回复飞书] 飞书图片上传响应摘要：状态码={}，消息={}，imageKey是否存在={}",
                response.path("code").asInt(-1),
                response.path("msg").asText(""),
                !response.path("data").path("image_key").asText("").isBlank());
        log.debug("[阶段8 回复飞书] 飞书图片上传完整响应：响应={}", response);

        // 检查上传结果。
        ensureOk(response, "上传飞书图片失败");

        // 返回 image_key。
        return response.path("data").path("image_key").asText("");
    }

    /**
     * 用图片回复飞书消息。
     *
     * @param messageId 原消息 ID
     * @param imageKey  飞书图片 key
     * @return 飞书新回复消息 ID
     */
    public String replyImage(String messageId, String imageKey) {
        // 图片 key 不能为空。
        if (imageKey == null || imageKey.isBlank()) {
            throw new IllegalArgumentException("回复飞书图片失败：image_key为空");
        }

        // 组装图片消息体。
        Map<String, Object> body = Map.of("msg_type", "image",
                "content", jsonUtils.write(Map.of("image_key", imageKey)));

        // 打印请求摘要。
        log.info("[阶段8 回复飞书] 飞书图片回复请求：消息ID={}，imageKey是否存在={}",
                messageId, !imageKey.isBlank());
        log.debug("[阶段8 回复飞书] 飞书图片回复完整请求：消息ID={}，请求体={}", messageId, body);

        // 调用飞书回复消息接口。
        JsonNode response = restClient.post()
                .uri("/open-apis/im/v1/messages/{message_id}/reply", messageId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        // 打印响应摘要。
        log.info("[阶段8 回复飞书] 飞书图片回复响应摘要：消息ID={}，状态码={}，消息={}，新消息ID={}",
                messageId,
                response.path("code").asInt(-1),
                response.path("msg").asText(""),
                response.path("data").path("message_id").asText(""));
        log.debug("[阶段8 回复飞书] 飞书图片回复完整响应：消息ID={}，响应={}", messageId, response);

        // 检查回复结果。
        ensureOk(response, "回复飞书图片失败");

        // 返回新消息 ID。
        return response.path("data").path("message_id").asText("");
    }

    /**
     * 删除机器人自己发出的飞书消息。
     *
     * <p>作用：最终结果回复后，清理“正在处理，请稍等”这类临时提示。</p>
     *
     * @param messageId 要删除的消息 ID
     */
    public void deleteMessage(String messageId) {
        // 空消息 ID 不能删除，直接跳过。
        if (messageId == null || messageId.isBlank()) {
            return;
        }

        // 打印删除请求摘要。
        log.info("[阶段8 回复飞书] 删除临时消息请求：消息ID={}", messageId);

        // 调用飞书删除消息接口。
        JsonNode response = restClient.delete()
                .uri("/open-apis/im/v1/messages/{message_id}", messageId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAccessToken())
                .retrieve()
                .body(JsonNode.class);

        // 打印删除响应摘要。
        log.info("[阶段8 回复飞书] 删除临时消息响应摘要：消息ID={}，状态码={}，消息={}",
                messageId,
                response.path("code").asInt(-1),
                response.path("msg").asText(""));
        log.debug("[阶段8 回复飞书] 删除临时消息完整响应：消息ID={}，响应={}", messageId, response);

        // 检查删除结果。
        ensureOk(response, "删除飞书临时消息失败");
    }

    /**
     * 给原消息添加表情反馈。
     *
     * <p>作用：收到用户消息后，用表情表示机器人已经开始处理，避免发送“稍等”文本。</p>
     *
     * @param messageId    原消息 ID
     * @param reactionType 表情类型
     */
    public void addReaction(String messageId, String reactionType) {
        // 空消息或空表情不处理。
        if (messageId == null || messageId.isBlank() || reactionType == null || reactionType.isBlank()) {
            return;
        }

        // 组装添加表情请求体。飞书接口要求 reaction_type 是对象，里面放 emoji_type。
        Map<String, Object> body = Map.of("reaction_type", Map.of("emoji_type", reactionType));

        // 打印请求摘要。
        log.info("[阶段2 回复处理中] 添加消息表情请求：消息ID={}，表情={}", messageId, reactionType);
        log.debug("[阶段2 回复处理中] 添加消息表情完整请求：消息ID={}，请求体={}", messageId, body);

        // 调用飞书添加表情接口。
        JsonNode response = restClient.post()
                .uri("/open-apis/im/v1/messages/{message_id}/reactions", messageId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        // 打印响应摘要。
        log.info("[阶段2 回复处理中] 添加消息表情响应摘要：消息ID={}，表情={}，状态码={}，消息={}",
                messageId,
                reactionType,
                response.path("code").asInt(-1),
                response.path("msg").asText(""));
        log.debug("[阶段2 回复处理中] 添加消息表情完整响应：消息ID={}，响应={}", messageId, response);

        // 检查返回码。
        ensureOk(response, "添加飞书消息表情失败");
    }

    /**
     * 给 lark-cli 使用的 tenant_access_token。
     *
     * <p>作用：复用 Java OpenAPI 已经验证过的应用凭据，在执行 lark-cli 前写入 CLI 的 token store。</p>
     *
     * @return tenant_access_token 明文，只能传给 lark-cli，不允许打印到日志
     */
    public String tenantAccessTokenForCli() {
        // 复用已有 token 缓存和刷新逻辑。
        return tenantAccessToken();
    }

    /**
     * 获取 app_access_token。
     *
     * <p>作用：OAuth 授权码换用户 token 时需要 app_access_token。</p>
     *
     * @return app_access_token 明文，不能打印到日志
     */
    public String appAccessTokenForOAuth() {
        // 复用 app token 缓存。
        return appAccessToken();
    }

    private synchronized String appAccessToken() {
        // token 未过期时直接复用。
        if (!appAccessToken.isBlank() && Instant.now().isBefore(appTokenExpiresAt.minusSeconds(60))) {
            log.info("[工具调用] 飞书app_access_token缓存命中：过期时间={}", appTokenExpiresAt);
            return appAccessToken;
        }

        // 打印请求摘要，不打印 appSecret。
        log.info("[工具调用] 飞书接口请求：方法=POST，接口=/open-apis/auth/v3/app_access_token/internal，appId={}，appSecret是否已配置={}",
                properties.getAppId(), properties.getAppSecret() != null && !properties.getAppSecret().isBlank());

        // 获取 app_access_token。
        JsonNode response = restClient.post()
                .uri("/open-apis/auth/v3/app_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("app_id", properties.getAppId(), "app_secret", properties.getAppSecret()))
                .retrieve()
                .body(JsonNode.class);

        // 打印响应摘要，不打印 token。
        log.info("[工具调用] 飞书接口响应摘要：方法=POST，接口=/open-apis/auth/v3/app_access_token/internal，状态码={}，消息={}，有效期秒数={}",
                response.path("code").asInt(-1),
                response.path("msg").asText(""),
                response.path("expire").asLong(0));

        // 检查返回码。
        ensureOk(response, "获取 app_access_token 失败");

        // 保存 token。
        appAccessToken = response.path("app_access_token").asText("");
        appTokenExpiresAt = Instant.now().plusSeconds(response.path("expire").asLong(7200));

        // 返回 token。
        return appAccessToken;
    }

    private synchronized String tenantAccessToken() {
        // 如果 token 已存在且距离过期还有 60 秒以上，就直接复用缓存。
        if (!tenantAccessToken.isBlank() && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            // 打印 token 缓存命中日志，不打印 token 明文。
            log.debug("[工具调用] 飞书token缓存命中：过期时间={}", tokenExpiresAt);
            return tenantAccessToken;
        }

        // 打印获取 token 请求日志，只打印 appId，不打印 appSecret。
        log.info("[工具调用] 飞书接口请求：方法=POST，接口=/open-apis/auth/v3/tenant_access_token/internal，appId={}，appSecret是否已配置={}",
                properties.getAppId(), properties.getAppSecret() != null && !properties.getAppSecret().isBlank());

        // token 不存在或快过期时，调用飞书接口重新获取 tenant_access_token。
        JsonNode response = restClient.post()
                .uri("/open-apis/auth/v3/tenant_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("app_id", properties.getAppId(), "app_secret", properties.getAppSecret()))
                .retrieve()
                .body(JsonNode.class);

        // 打印获取 token 响应摘要，不打印 token 明文。
        log.info("[工具调用] 飞书接口响应摘要：方法=POST，接口=/open-apis/auth/v3/tenant_access_token/internal，状态码={}，消息={}，有效期秒数={}",
                response.path("code").asInt(-1),
                response.path("msg").asText(""),
                response.path("expire").asLong(0));
        // token 接口响应里包含 tenant_access_token，不能在 DEBUG 里打印完整响应。

        // 检查获取 token 的返回结果。
        ensureOk(response, "获取 tenant_access_token 失败");

        // 保存新的 token。
        tenantAccessToken = response.path("tenant_access_token").asText("");

        // 保存 token 过期时间，飞书 expire 通常是秒数。
        tokenExpiresAt = Instant.now().plusSeconds(response.path("expire").asLong(7200));

        // 返回可用 token。
        return tenantAccessToken;
    }

    private void ensureOk(JsonNode response, String message) {
        // 飞书接口成功时 code 为 0；如果 response 为空，就给一个 -1。
        int code = response == null ? -1 : response.path("code").asInt(-1);

        // code 不是 0 就说明飞书接口失败。
        if (code != 0) {
            // 失败时保留飞书原始返回，方便从日志里查 code、msg、log_id。
            String detail = response == null ? "empty response" : response.toString();

            // 抛异常给上层，由上层组织用户可读的失败回复。
            throw new IllegalStateException(message + "：" + detail);
        }
    }
}
