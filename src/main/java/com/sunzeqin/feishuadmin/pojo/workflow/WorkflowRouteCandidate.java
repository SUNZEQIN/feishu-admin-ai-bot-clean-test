package com.sunzeqin.feishuadmin.pojo.workflow;

import java.util.List;

/**
 * 工作流路由候选。
 *
 * <p>作用：保存 Java 规则召回出来的候选工作流和规则分，后续再交给 LLM 只在候选里裁决。</p>
 *
 * @param workflowCode       工作流编码
 * @param workflowName       工作流名称
 * @param domain             业务域
 * @param riskLevel          风险等级
 * @param requireUserConfirm 是否需要用户二次确认
 * @param score              规则召回分数
 * @param matchedReasons     命中原因
 * @param intentKeywords     触发关键词
 * @param description        工作流说明
 *
 * @author sunzeqin
 */
public record WorkflowRouteCandidate(
        String workflowCode,
        String workflowName,
        String domain,
        String riskLevel,
        boolean requireUserConfirm,
        int score,
        List<String> matchedReasons,
        List<String> intentKeywords,
        String description) {
}
