package com.sunzeqin.feishuadmin.pojo.workflow;

import java.util.Map;

/**
 * 工作流步骤。
 *
 * <p>作用：承载数据库中的工作流步骤配置，执行器会按 stepNo 顺序执行。</p>
 *
 * @param workflowCode  工作流编码
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
public record WorkflowStep(
        String workflowCode,
        int stepNo,
        String stepName,
        String executorType,
        String toolName,
        Map<String, Object> inputTemplate,
        String outputKey,
        String onError) {
}
