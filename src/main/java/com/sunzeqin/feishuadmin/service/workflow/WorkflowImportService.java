package com.sunzeqin.feishuadmin.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sunzeqin.feishuadmin.pojo.workflow.WorkflowImportRequest;
import com.sunzeqin.feishuadmin.pojo.workflow.WorkflowImportRequest.WorkflowStepImportRequest;
import com.sunzeqin.feishuadmin.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工作流导入服务。
 *
 * <p>作用：把 YAML / JSON 工作流配置解析、校验并写入 MySQL。
 * 导入时会把每一步映射到哪个执行器、哪个工具打印到日志，方便排查。</p>
 *
 * @author sunzeqin
 */
@Service
public class WorkflowImportService {
    // 当前服务使用的日志对象。
    private static final Logger log = LoggerFactory.getLogger(WorkflowImportService.class);

    // 工作流编码只允许字母、数字、下划线、短横线和点，避免后续拼接查询时出现奇怪字符。
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{2,128}$");

    // 输出变量名只允许普通变量字符。
    private static final Pattern OUTPUT_KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,127}$");

    // 允许的执行器类型。
    private static final Set<String> ALLOWED_EXECUTOR_TYPES = Set.of(
            "MCP",
            "CLI",
            "JAVA_TOOL",
            "LLM_SUMMARY",
            "FEISHU_REPLY");

    // 允许的失败处理策略。
    private static final Set<String> ALLOWED_ON_ERROR = Set.of(
            "STOP",
            "RETRY",
            "FALLBACK");

    // 允许的风险等级。
    private static final Set<String> ALLOWED_RISK_LEVELS = Set.of(
            "LOW",
            "MEDIUM",
            "HIGH");

    // 允许的飞书回复工具。
    private static final Set<String> ALLOWED_FEISHU_REPLY_TOOLS = Set.of(
            "feishu.reply_text",
            "feishu.send_message",
            "feishu.send_card");

    // 允许的 Java 内置工具。
    private static final Set<String> ALLOWED_JAVA_TOOLS = Set.of(
            "feishu.scope_for_domain",
            "ecommerce.list_tools",
            "ecommerce.call_tool");

    // 数据库操作对象。
    private final JdbcTemplate jdbcTemplate;

    // JSON 工具对象。
    private final JsonUtils jsonUtils;

    // YAML 解析器。
    private final ObjectMapper yamlMapper;

    public WorkflowImportService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils) {
        // 保存数据库操作对象。
        this.jdbcTemplate = jdbcTemplate;

        // 保存 JSON 工具对象。
        this.jsonUtils = jsonUtils;

        // 创建 YAML 解析器。
        this.yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    }

    /**
     * 导入工作流配置。
     *
     * @param contentType 请求 Content-Type
     * @param body        YAML 或 JSON 原文
     * @return 导入结果
     */
    @Transactional
    public Map<String, Object> importWorkflow(String contentType, String body) {
        // 解析导入内容。
        String sourceFormat = sourceFormat(contentType, body);
        WorkflowImportRequest request = parse(sourceFormat, body);

        // 先做完整校验，避免写一半失败。
        validate(request);

        // 打印导入总览。
        log.info("[工作流导入] 开始导入：工作流编码={}，名称={}，业务域={}，步骤数={}，启用={}，风险等级={}",
                request.workflowCode(),
                request.workflowName(),
                request.domain(),
                request.steps().size(),
                enabled(request),
                riskLevel(request));

        // 逐步打印工具映射，让日志能直接看出 YAML 会调哪个工具。
        for (WorkflowStepImportRequest step : request.steps()) {
            log.info("[工作流导入] 步骤映射：工作流编码={}，步骤={}，步骤名称={}，执行器类型={}，工具名称={}，入参字段={}，输出变量={}，失败策略={}",
                    request.workflowCode(),
                    step.stepNo(),
                    step.stepName(),
                    executorType(step),
                    step.toolName(),
                    step.inputTemplate().keySet(),
                    step.outputKey(),
                    onError(step));
        }

        // 覆盖式导入：先写定义，再删除旧步骤，再写新步骤。
        upsertWorkflowDefinition(request, sourceFormat, body);
        jdbcTemplate.update("DELETE FROM workflow_step WHERE workflow_code = ?", request.workflowCode());
        for (WorkflowStepImportRequest step : request.steps()) {
            insertWorkflowStep(request.workflowCode(), step);
        }

        // 导入完成日志。
        log.info("[工作流导入] 导入完成：工作流编码={}，步骤数={}", request.workflowCode(), request.steps().size());

        // 返回给调用方的摘要。
        return Map.of(
                "ok", true,
                "workflowCode", request.workflowCode(),
                "workflowName", request.workflowName(),
                "stepCount", request.steps().size(),
                "sourceFormat", sourceFormat
        );
    }

    private WorkflowImportRequest parse(String sourceFormat, String body) {
        // 空内容无法导入。
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("工作流导入内容不能为空");
        }

        try {
            // YAML 解析器也能兼容 JSON，这里保留 sourceFormat 是为了日志和返回更清晰。
            return yamlMapper.readValue(body, WorkflowImportRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(sourceFormat + " 工作流配置解析失败：" + e.getMessage(), e);
        }
    }

    private String sourceFormat(String contentType, String body) {
        // Content-Type 优先。
        String lowerContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lowerContentType.contains("json")) {
            return "JSON";
        }
        if (lowerContentType.contains("yaml") || lowerContentType.contains("yml")) {
            return "YAML";
        }

        // 没传 Content-Type 时根据首字符兜底判断。
        String text = body == null ? "" : body.stripLeading();
        if (text.startsWith("{") || text.startsWith("[")) {
            return "JSON";
        }
        return "YAML";
    }

    private void validate(WorkflowImportRequest request) {
        // 工作流编码必填并限制格式。
        if (!CODE_PATTERN.matcher(request.workflowCode()).matches()) {
            throw new IllegalArgumentException("workflowCode 格式非法，只允许字母、数字、下划线、短横线和点，长度2到128");
        }

        // 工作流名称必填。
        if (request.workflowName().isBlank()) {
            throw new IllegalArgumentException("workflowName 不能为空");
        }

        // 风险等级必须在白名单内。
        if (!ALLOWED_RISK_LEVELS.contains(riskLevel(request))) {
            throw new IllegalArgumentException("riskLevel 不在白名单：" + request.riskLevel());
        }

        // 至少要有一个步骤。
        if (request.steps().isEmpty()) {
            throw new IllegalArgumentException("steps 不能为空");
        }

        // 校验步骤序号不能重复。
        Set<Integer> stepNumbers = new HashSet<>();
        for (WorkflowStepImportRequest step : request.steps()) {
            validateStep(step);
            if (!stepNumbers.add(step.stepNo())) {
                throw new IllegalArgumentException("stepNo 重复：" + step.stepNo());
            }
        }
    }

    private void validateStep(WorkflowStepImportRequest step) {
        // 步骤序号必须大于0。
        if (step.stepNo() == null || step.stepNo() <= 0) {
            throw new IllegalArgumentException("stepNo 必须大于0");
        }

        // 步骤名称必填。
        if (step.stepName().isBlank()) {
            throw new IllegalArgumentException("stepName 不能为空：stepNo=" + step.stepNo());
        }

        // 执行器类型必须在白名单内。
        String executorType = executorType(step);
        if (!ALLOWED_EXECUTOR_TYPES.contains(executorType)) {
            throw new IllegalArgumentException("executorType 不在白名单：" + step.executorType());
        }

        // 失败策略必须在白名单内。
        String onError = onError(step);
        if (!ALLOWED_ON_ERROR.contains(onError)) {
            throw new IllegalArgumentException("onError 不在白名单：" + step.onError());
        }

        // 输出变量名允许为空，但不为空时必须是安全变量名。
        if (!step.outputKey().isBlank() && !OUTPUT_KEY_PATTERN.matcher(step.outputKey()).matches()) {
            throw new IllegalArgumentException("outputKey 格式非法：" + step.outputKey());
        }

        // 按执行器类型校验工具名。
        validateToolName(step, executorType);
    }

    private void validateToolName(WorkflowStepImportRequest step, String executorType) {
        // LLM 摘要步骤允许 toolName 为空。
        if ("LLM_SUMMARY".equals(executorType)) {
            return;
        }

        // 其它类型都必须明确工具名。
        if (step.toolName().isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空：stepNo=" + step.stepNo());
        }

        // MCP 第一版只允许电商 MCP 工具，避免模型或配置把任意服务塞进来。
        if ("MCP".equals(executorType) && !step.toolName().startsWith("ecommerce.")) {
            throw new IllegalArgumentException("MCP 工具暂只允许 ecommerce.*：" + step.toolName());
        }

        // CLI 第一版只允许飞书统一入口，真实命令仍由 Skill + CLI 层继续校验。
        if ("CLI".equals(executorType) && !"cli.run_skill".equals(step.toolName())) {
            throw new IllegalArgumentException("CLI 工具暂只允许 cli.run_skill：" + step.toolName());
        }

        // Java 内置工具必须在白名单内。
        if ("JAVA_TOOL".equals(executorType) && !ALLOWED_JAVA_TOOLS.contains(step.toolName())) {
            throw new IllegalArgumentException("JAVA_TOOL 工具不在白名单：" + step.toolName());
        }

        // 飞书回复工具必须在白名单内。
        if ("FEISHU_REPLY".equals(executorType) && !ALLOWED_FEISHU_REPLY_TOOLS.contains(step.toolName())) {
            throw new IllegalArgumentException("FEISHU_REPLY 工具不在白名单：" + step.toolName());
        }
    }

    private void upsertWorkflowDefinition(WorkflowImportRequest request, String sourceFormat, String body) {
        // 写入或覆盖工作流定义。
        jdbcTemplate.update("""
                        INSERT INTO workflow_definition (
                            workflow_code, workflow_name, domain, enabled, risk_level,
                            require_user_confirm, intent_keywords, description,
                            source_format, source_content
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            workflow_name = VALUES(workflow_name),
                            domain = VALUES(domain),
                            enabled = VALUES(enabled),
                            risk_level = VALUES(risk_level),
                            require_user_confirm = VALUES(require_user_confirm),
                            intent_keywords = VALUES(intent_keywords),
                            description = VALUES(description),
                            source_format = VALUES(source_format),
                            source_content = VALUES(source_content)
                        """,
                request.workflowCode(),
                request.workflowName(),
                request.domain(),
                enabled(request) ? 1 : 0,
                riskLevel(request),
                requireUserConfirm(request) ? 1 : 0,
                jsonUtils.write(request.intentKeywords()),
                request.description(),
                sourceFormat,
                body);
    }

    private void insertWorkflowStep(String workflowCode, WorkflowStepImportRequest step) {
        // 写入工作流步骤。
        jdbcTemplate.update("""
                        INSERT INTO workflow_step (
                            workflow_code, step_no, step_name, executor_type,
                            tool_name, input_template, output_key, on_error
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                workflowCode,
                step.stepNo(),
                step.stepName(),
                executorType(step),
                step.toolName(),
                jsonUtils.write(step.inputTemplate()),
                step.outputKey(),
                onError(step));
    }

    private boolean enabled(WorkflowImportRequest request) {
        // enabled 默认 true。
        return request.enabled() == null || request.enabled();
    }

    private boolean requireUserConfirm(WorkflowImportRequest request) {
        // requireUserConfirm 默认 false。
        return request.requireUserConfirm() != null && request.requireUserConfirm();
    }

    private String riskLevel(WorkflowImportRequest request) {
        // riskLevel 默认 LOW。
        if (request.riskLevel() == null || request.riskLevel().isBlank()) {
            return "LOW";
        }
        return request.riskLevel().toUpperCase(Locale.ROOT);
    }

    private String executorType(WorkflowStepImportRequest step) {
        // executorType 统一大写。
        return step.executorType().toUpperCase(Locale.ROOT);
    }

    private String onError(WorkflowStepImportRequest step) {
        // onError 默认 STOP。
        if (step.onError() == null || step.onError().isBlank()) {
            return "STOP";
        }
        return step.onError().toUpperCase(Locale.ROOT);
    }
}
