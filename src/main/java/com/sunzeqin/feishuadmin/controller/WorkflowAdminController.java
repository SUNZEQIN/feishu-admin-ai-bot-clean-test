package com.sunzeqin.feishuadmin.controller;

import com.sunzeqin.feishuadmin.service.workflow.WorkflowImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工作流管理控制器。
 *
 * <p>作用：提供工作流 YAML / JSON 导入入口，方便后续不用改代码就新增常用工作流。</p>
 *
 * @author sunzeqin
 */
@RestController
@RequestMapping("/api/admin/workflows")
public class WorkflowAdminController {
    // 当前控制器使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(WorkflowAdminController.class);

    // 工作流导入服务。
    private final WorkflowImportService workflowImportService;

    public WorkflowAdminController(WorkflowImportService workflowImportService) {
        // 保存工作流导入服务。
        this.workflowImportService = workflowImportService;
    }

    /**
     * 导入工作流配置。
     *
     * @param contentType 请求 Content-Type
     * @param body        YAML 或 JSON 原文
     * @return 导入结果
     */
    @PostMapping(value = "/import", consumes = {
            "application/yaml",
            "application/x-yaml",
            "text/yaml",
            "text/plain",
            MediaType.APPLICATION_JSON_VALUE
    })
    public Map<String, Object> importWorkflow(
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestBody String body) {
        // 交给服务层解析、校验、落库。
        return workflowImportService.importWorkflow(contentType, body);
    }

    /**
     * 工作流导入参数错误处理。
     *
     * @param e 参数错误
     * @return 可读错误
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException e) {
        // 导入失败要在日志里保留原因，方便用户根据日志修改 YAML。
        log.warn("[阶段9 工作流导入] 导入失败：原因={}", e.getMessage());
        return Map.of(
                "ok", false,
                "error", e.getMessage()
        );
    }
}
