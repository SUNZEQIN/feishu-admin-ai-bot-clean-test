package com.sunzeqin.feishuadmin.pojo.workflow;

import java.util.List;
import java.util.Map;

/**
 * 工作流导入请求。
 *
 * <p>作用：承载 YAML / JSON 文件里的工作流定义，导入服务会先校验再落库。</p>
 *
 * @param workflowCode       工作流编码
 * @param workflowName       工作流名称
 * @param domain             业务域
 * @param enabled            是否启用
 * @param riskLevel          风险等级
 * @param requireUserConfirm 是否需要用户二次确认
 * @param intentKeywords     触发关键词
 * @param description        工作流说明
 * @param steps              工作流步骤列表
 *
 * @author sunzeqin
 */
public record WorkflowImportRequest(
        String workflowCode,
        String workflowName,
        String domain,
        Boolean enabled,
        String riskLevel,
        Boolean requireUserConfirm,
        List<String> intentKeywords,
        String description,
        List<WorkflowStepImportRequest> steps) {

    public WorkflowImportRequest {
        // 文本字段统一兜底，避免校验阶段空指针。
        workflowCode = workflowCode == null ? "" : workflowCode.trim();
        workflowName = workflowName == null ? "" : workflowName.trim();
        domain = domain == null ? "" : domain.trim();
        riskLevel = riskLevel == null ? "" : riskLevel.trim();
        description = description == null ? "" : description.trim();

        // 集合字段统一兜底。
        intentKeywords = intentKeywords == null ? List.of() : List.copyOf(intentKeywords);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * 工作流步骤导入请求。
     *
     * @param stepNo        步骤序号
     * @param stepName      步骤名称
     * @param executorType  执行器类型
     * @param toolName      工具名称
     * @param inputTemplate 入参模板
     * @param outputKey     输出变量名
     * @param onError       失败处理策略
     *
     * @author sunzeqin
     */
    public record WorkflowStepImportRequest(
            Integer stepNo,
            String stepName,
            String executorType,
            String toolName,
            Map<String, Object> inputTemplate,
            String outputKey,
            String onError) {

        public WorkflowStepImportRequest {
            // 文本字段统一兜底。
            stepName = stepName == null ? "" : stepName.trim();
            executorType = executorType == null ? "" : executorType.trim();
            toolName = toolName == null ? "" : toolName.trim();
            outputKey = outputKey == null ? "" : outputKey.trim();
            onError = onError == null ? "" : onError.trim();

            // 入参模板统一兜底。
            inputTemplate = inputTemplate == null ? Map.of() : Map.copyOf(inputTemplate);
        }
    }
}
