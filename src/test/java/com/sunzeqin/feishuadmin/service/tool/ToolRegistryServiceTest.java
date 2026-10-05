package com.sunzeqin.feishuadmin.service.tool;

import com.sunzeqin.feishuadmin.config.FeishuProperties;
import com.sunzeqin.feishuadmin.pojo.tool.ToolCall;
import com.sunzeqin.feishuadmin.pojo.tool.ToolResult;
import com.sunzeqin.feishuadmin.service.EcommerceMcpClientService;
import com.sunzeqin.feishuadmin.service.FeishuUserScopeMappingService;
import com.sunzeqin.feishuadmin.service.cli.SkillCliExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具注册表护栏测试。
 *
 * <p>覆盖三个关键护栏：未知工具拒绝、权限拒绝、工具超时。</p>
 *
 * @author sunzeqin
 */
class ToolRegistryServiceTest {

    // 保存当前用例创建的工具注册表，方便用例结束后关闭线程池。
    private ToolRegistryService registry;

    @AfterEach
    void tearDown() {
        // 关闭工具执行线程池，避免用例之间线程泄漏。
        if (registry != null) {
            registry.shutdownToolExecutor();
        }
    }

    @Test
    void rejectsUnknownTool() {
        FeishuProperties properties = new FeishuProperties();
        registry = newRegistry(mock(SkillCliExecutorService.class), properties);

        ToolResult result = registry.execute(new ToolCall("feishu.delete_everything", Map.of()));

        assertFalse(result.success());
        assertTrue(result.message().contains("未知工具"));
    }

    @Test
    void rejectsCallerOutsideAllowlist() {
        FeishuProperties properties = new FeishuProperties();
        properties.setToolAllowedOpenIds("ou_allowed");

        SkillCliExecutorService cli = mock(SkillCliExecutorService.class);
        registry = newRegistry(cli, properties);

        ToolResult result = registry.execute(new ToolCall("cli.run_skill", Map.of(
                "domain", "im",
                "goal", "查一下本群成员",
                "sourceChatId", "oc_1",
                "senderOpenId", "ou_other")));

        assertFalse(result.success());
        assertTrue(result.message().contains("权限"));
    }

    @Test
    void allowsCallerInsideAllowlist() {
        FeishuProperties properties = new FeishuProperties();
        properties.setToolAllowedOpenIds("ou_allowed");

        SkillCliExecutorService cli = mock(SkillCliExecutorService.class);
        when(cli.runSkill(any(), any(), any(), any(), any(), any())).thenReturn(Map.of("finalReply", "ok"));
        registry = newRegistry(cli, properties);

        ToolResult result = registry.execute(new ToolCall("cli.run_skill", Map.of(
                "domain", "im",
                "goal", "查一下本群成员",
                "sourceChatId", "oc_1",
                "senderOpenId", "ou_allowed")));

        assertTrue(result.success());
    }

    @Test
    void timesOutWhenToolIsSlow() throws Exception {
        FeishuProperties properties = new FeishuProperties();
        properties.setToolTimeoutSeconds(1);

        SkillCliExecutorService cli = mock(SkillCliExecutorService.class);
        when(cli.runSkill(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            // 模拟卡住的工具：睡 5 秒，超过 1 秒超时。
            Thread.sleep(5000);
            return Map.of();
        });
        registry = newRegistry(cli, properties);

        ToolResult result = registry.execute(new ToolCall("cli.run_skill", Map.of(
                "domain", "im",
                "goal", "查一下本群成员",
                "sourceChatId", "oc_1")));

        assertFalse(result.success());
        assertTrue(result.message().contains("超时"));
    }

    @Test
    void toolDescriptionsMatchDispatchWhitelist() {
        FeishuProperties properties = new FeishuProperties();
        registry = newRegistry(mock(SkillCliExecutorService.class), properties);

        // 启动自检不抛异常，并保证工具都在说明文本里。
        registry.verifyToolCatalog();
        String descriptions = registry.toolDescriptions();

        assertTrue(descriptions.contains("cli.run_skill"));
        assertTrue(descriptions.contains("feishu.scope_for_domain"));
        assertTrue(descriptions.contains("ecommerce.list_tools"));
        assertTrue(descriptions.contains("ecommerce.call_tool"));
        assertTrue(descriptions.contains("workflow.list"));
        assertTrue(descriptions.contains("workflow.run"));
        assertEquals(6, descriptions.lines().filter(line -> line.matches("\\s*\\d+\\.\\s*[a-z][a-z0-9_.]+\\s*")).count());
    }

    private ToolRegistryService newRegistry(SkillCliExecutorService cli, FeishuProperties properties) {
        // 用 Mock 构造依赖，避免测试依赖数据库和飞书网络。
        return new ToolRegistryService(cli, mock(EcommerceMcpClientService.class),
                mock(FeishuUserScopeMappingService.class),
                mock(com.sunzeqin.feishuadmin.service.workflow.WorkflowExecutionService.class),
                new ToolPermissionService(properties), properties);
    }
}
