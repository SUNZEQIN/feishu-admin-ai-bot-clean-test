package com.sunzeqin.feishuadmin.service;

import com.sunzeqin.feishuadmin.config.FeishuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 电商 MCP 客户端服务。
 *
 * <p>作用：原飞书机器人项目通过这个服务调用独立的电商 MCP Server。</p>
 *
 * @author sunzeqin
 */
@Service
public class EcommerceMcpClientService {
    // 当前服务使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(EcommerceMcpClientService.class);

    // 飞书机器人配置，里面包含电商 MCP 服务地址。
    private final FeishuProperties properties;

    // HTTP 客户端构造器。
    private final RestClient.Builder restClientBuilder;

    // 统一带连接和读取超时的请求工厂，避免电商 MCP 卡住时把工具调用线程占死。
    private final ClientHttpRequestFactory requestFactory;

    public EcommerceMcpClientService(FeishuProperties properties, RestClient.Builder restClientBuilder) {
        // 保存配置对象。
        this.properties = properties;

        // 保存 RestClient 构造器。
        this.restClientBuilder = restClientBuilder;

        // 创建带超时的请求工厂，超时时间来自配置。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(properties.getEcommerceMcpConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(properties.getEcommerceMcpReadTimeoutSeconds()));
        this.requestFactory = factory;
    }

    public Map<String, Object> listTools() {
        // 确认电商 MCP 已启用。
        ensureEnabled();

        // 打印请求摘要。
        log.info("[电商MCP] 查询工具列表：baseUrl={}", properties.getEcommerceMcpBaseUrl());

        // 请求电商 MCP 工具列表。
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClientBuilder.clone()
                .baseUrl(properties.getEcommerceMcpBaseUrl())
                .requestFactory(requestFactory)
                .build()
                .get()
                .uri("/mcp/tools/list")
                .retrieve()
                .body(Map.class);

        // 打印响应摘要。
        log.info("[电商MCP] 工具列表返回：字段={}", response == null ? "空响应" : response.keySet());
        log.debug("[电商MCP] 工具列表完整响应：{}", response);

        // 返回响应。
        return response == null ? Map.of() : response;
    }

    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        // 确认电商 MCP 已启用。
        ensureEnabled();

        // 组装请求体。
        Map<String, Object> body = Map.of(
                "toolName", toolName,
                "arguments", arguments == null ? Map.of() : arguments
        );

        // 打印请求摘要。
        log.info("[电商MCP] 调用工具：baseUrl={}，工具名称={}，入参={}",
                properties.getEcommerceMcpBaseUrl(), toolName, arguments);

        // 调用电商 MCP 工具。
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClientBuilder.clone()
                .baseUrl(properties.getEcommerceMcpBaseUrl())
                .requestFactory(requestFactory)
                .build()
                .post()
                .uri("/mcp/tools/call")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        // 打印响应摘要。
        log.info("[电商MCP] 工具返回：工具名称={}，success={}，message={}，data={}",
                toolName,
                response == null ? null : response.get("success"),
                response == null ? null : response.get("message"),
                response == null ? null : response.get("data"));
        log.debug("[电商MCP] 工具完整响应：工具名称={}，响应={}", toolName, response);

        // 返回响应。
        return response == null ? Map.of() : response;
    }

    private void ensureEnabled() {
        // 未启用时直接失败。
        if (!properties.isEcommerceMcpEnabled()) {
            throw new IllegalStateException("电商 MCP 未启用，请配置 FEISHU_ECOMMERCE_MCP_ENABLED=true");
        }

        // 地址为空时直接失败。
        if (properties.getEcommerceMcpBaseUrl() == null || properties.getEcommerceMcpBaseUrl().isBlank()) {
            throw new IllegalStateException("电商 MCP 地址为空，请配置 FEISHU_ECOMMERCE_MCP_BASE_URL");
        }
    }
}
