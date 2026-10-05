package com.sunzeqin.feishuadmin.pojo.workflow;

import java.util.List;

/**
 * 工作流定义。
 *
 * <p>作用：承载数据库中的工作流主表信息，供 Agent 查询和执行。</p>
 *
 * @param workflowCode       工作流编码
 * @param workflowName       工作流名称
 * @param domain             业务域
 * @param enabled            是否启用
 * @param riskLevel          风险等级
 * @param requireUserConfirm 是否需要用户二次确认
 * @param intentKeywords     触发关键词
 * @param description        工作流说明
 *
 * @author sunzeqin
 */
public record WorkflowDefinition(
        String workflowCode,
        String workflowName,
        String domain,
        boolean enabled,
        String riskLevel,
        boolean requireUserConfirm,
        List<String> intentKeywords,
        String description) {
}
