package com.sunzeqin.feishuadmin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.pojo.UserScopeInfo;
import com.sunzeqin.feishuadmin.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 飞书用户身份 scope 映射服务。
 *
 * <p>作用：从资源文件读取“业务域 -> 用户身份 scope”映射，给 OAuth 授权链接和 Agent 工具复用。</p>
 *
 * @author sunzeqin
 */
@Service
public class FeishuUserScopeMappingService {
    // 当前服务使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(FeishuUserScopeMappingService.class);

    // scope 映射资源路径，由飞书开放平台应用信息文件生成。
    private static final String RESOURCE_PATH = "feishu/user-scope-map.json";

    // 飞书 refresh_token 需要的固定 scope。
    private static final String OFFLINE_ACCESS_SCOPE = "offline_access";

    // JSON 工具类，用来解析资源文件。
    private final JsonUtils jsonUtils;

    // 飞书配置，资源不存在时使用配置里的默认 scope 兜底。
    private final FeishuProperties properties;

    // 缓存后的业务域 scope 映射，避免每次授权都读文件。
    private volatile Map<String, List<UserScopeInfo>> cachedDomainScopes;

    public FeishuUserScopeMappingService(JsonUtils jsonUtils, FeishuProperties properties) {
        // 保存 JSON 工具类。
        this.jsonUtils = jsonUtils;

        // 保存飞书配置。
        this.properties = properties;
    }

    /**
     * 查询某个业务域的 scope 列表。
     *
     * @param domain 业务域，例如 im、calendar、docs
     * @return scope 信息列表
     */
    public List<UserScopeInfo> scopesForDomain(String domain) {
        // 规范化业务域，避免大小写和空格影响匹配。
        String normalizedDomain = normalizeDomain(domain);

        // 空业务域直接返回空列表。
        if (normalizedDomain.isBlank()) {
            return List.of();
        }

        // 从缓存里读取业务域对应的 scope。
        return domainScopes().getOrDefault(normalizedDomain, List.of());
    }

    /**
     * 查询多个业务域合并后的 scope 文本。
     *
     * @param domains 业务域集合
     * @return 空格分隔的 scope 文本
     */
    public String scopeTextForDomains(List<String> domains) {
        // 用 LinkedHashSet 保持顺序并去重。
        Set<String> scopes = new LinkedHashSet<>();

        // refresh_token 场景默认带 offline_access。
        scopes.add(OFFLINE_ACCESS_SCOPE);

        // 遍历业务域并合并 scope。
        if (domains != null) {
            for (String domain : domains) {
                addDomainScopes(scopes, domain);
            }
        }

        // 没有命中任何业务域时使用配置兜底。
        if (scopes.size() == 1) {
            return defaultScopeText();
        }

        // 返回飞书 OAuth 接口需要的空格分隔格式。
        return String.join(" ", scopes);
    }

    /**
     * 查询单个业务域的 scope 文本。
     *
     * @param domain 业务域
     * @return 空格分隔的 scope 文本
     */
    public String scopeTextForDomain(String domain) {
        // 空业务域直接使用默认 scope。
        String normalizedDomain = normalizeDomain(domain);
        if (normalizedDomain.isBlank()) {
            return defaultScopeText();
        }

        // 单业务域复用多业务域拼接逻辑。
        return scopeTextForDomains(List.of(normalizedDomain));
    }

    /**
     * 查询所有业务域映射。
     *
     * @return 不可变映射
     */
    public Map<String, List<UserScopeInfo>> allDomainScopes() {
        // 返回缓存映射，避免调用方修改内部数据。
        return domainScopes();
    }

    /**
     * 给工具调用返回结构化结果。
     *
     * @param domain 业务域
     * @return 工具可序列化的 Map
     */
    public Map<String, Object> scopeToolResult(String domain) {
        // 规范化业务域。
        String normalizedDomain = normalizeDomain(domain);

        // 查询 scope 信息列表。
        List<UserScopeInfo> scopeInfos = scopesForDomain(normalizedDomain);

        // 返回给 Agent 的结构化结果。
        return Map.of(
                "domain", normalizedDomain,
                "scopeText", scopeTextForDomain(normalizedDomain),
                "scopeCount", scopeInfos.size(),
                "scopes", scopeInfos
        );
    }

    private void addDomainScopes(Set<String> scopes, String domain) {
        // 查询业务域的 scope 信息。
        List<UserScopeInfo> scopeInfos = scopesForDomain(domain);

        // 只把非空 scope 加入授权集合。
        for (UserScopeInfo scopeInfo : scopeInfos) {
            if (!scopeInfo.scope().isBlank()) {
                scopes.add(scopeInfo.scope());
            }
        }
    }

    private Map<String, List<UserScopeInfo>> domainScopes() {
        // 已加载过时直接返回缓存。
        Map<String, List<UserScopeInfo>> localCache = cachedDomainScopes;
        if (localCache != null) {
            return localCache;
        }

        // 首次加载时加锁，避免并发重复解析资源文件。
        synchronized (this) {
            if (cachedDomainScopes == null) {
                cachedDomainScopes = loadDomainScopes();
            }
            return cachedDomainScopes;
        }
    }

    private Map<String, List<UserScopeInfo>> loadDomainScopes() {
        try {
            // 从 classpath 读取 scope 映射文件。
            ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);

            // 资源不存在时返回空映射，调用方会使用默认 scope 兜底。
            if (!resource.exists()) {
                log.warn("[工具调用] 飞书用户scope映射文件不存在：路径={}", RESOURCE_PATH);
                return Map.of();
            }

            // 读取资源文件内容。
            String text;
            try (InputStream inputStream = resource.getInputStream()) {
                text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            // 解析 JSON 根节点。
            JsonNode root = jsonUtils.readTree(text);

            // 解析 domains 节点。
            JsonNode domainsNode = root.path("domains");

            // 保存解析后的映射。
            Map<String, List<UserScopeInfo>> result = new LinkedHashMap<>();

            // 遍历每个业务域。
            domainsNode.fields().forEachRemaining(entry -> {
                String domain = normalizeDomain(entry.getKey());
                List<UserScopeInfo> scopes = readScopeInfos(entry.getValue());
                result.put(domain, Collections.unmodifiableList(scopes));
            });

            // 打印加载结果，方便确认映射是否生效。
            log.info("[工具调用] 飞书用户scope映射加载完成：业务域数量={}", result.size());

            // 返回不可变映射。
            return Collections.unmodifiableMap(result);
        } catch (Exception e) {
            // 加载失败时记录错误并使用空映射兜底，不影响服务启动。
            log.warn("[工具调用] 飞书用户scope映射加载失败：错误={}", e.getMessage());
            return Map.of();
        }
    }

    private List<UserScopeInfo> readScopeInfos(JsonNode node) {
        // 保存单个业务域下的 scope 列表。
        List<UserScopeInfo> result = new ArrayList<>();

        // 资源里每个业务域都是数组。
        for (JsonNode item : node) {
            String scope = item.path("scope").asText("");
            String description = item.path("description").asText("");
            int level = item.path("level").asInt(0);
            if (!scope.isBlank()) {
                result.add(new UserScopeInfo(scope, description, level));
            }
        }

        // 返回解析结果。
        return result;
    }

    private String defaultScopeText() {
        // 配置里有默认 scope 时优先使用配置。
        if (properties.getOauthDefaultScopes() != null && !properties.getOauthDefaultScopes().isBlank()) {
            return properties.getOauthDefaultScopes();
        }

        // 配置为空时至少保留 offline_access。
        return OFFLINE_ACCESS_SCOPE;
    }

    private String normalizeDomain(String domain) {
        // 空业务域返回空字符串。
        if (domain == null) {
            return "";
        }

        // 统一转小写，避免模型输出大小写不一致。
        return domain.trim().toLowerCase(Locale.ROOT);
    }
}
