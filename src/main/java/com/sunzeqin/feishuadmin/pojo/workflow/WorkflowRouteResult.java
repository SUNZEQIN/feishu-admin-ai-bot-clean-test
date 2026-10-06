package com.sunzeqin.feishuadmin.pojo.workflow;

import java.util.Map;

/**
 * 工作流路由结果。
 *
 * <p>作用：表达当前用户请求是否命中了一个可执行工作流。</p>
 *
 * @param matched      是否命中
 * @param workflowCode 工作流编码
 * @param confidence   大模型裁决置信度
 * @param reason       命中或未命中的原因
 * @param arguments    工作流初始参数
 *
 * @author sunzeqin
 */
public record WorkflowRouteResult(
        boolean matched,
        String workflowCode,
        double confidence,
        String reason,
        Map<String, Object> arguments) {

    public static WorkflowRouteResult notMatched(String reason) {
        return new WorkflowRouteResult(false, "", 0, reason, Map.of());
    }
}
