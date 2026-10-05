CREATE TABLE IF NOT EXISTS agent_conversation_memory (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    memory_scope VARCHAR(32) NOT NULL COMMENT '记忆范围：GROUP=群聊，PRIVATE=私聊',
    memory_key VARCHAR(255) NOT NULL COMMENT '记忆键：当前使用chat_id，一条真实消息只保存一行',
    chat_id VARCHAR(128) NOT NULL COMMENT '飞书会话ID',
    user_open_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '发送人open_id',
    user_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '发送人user_id',
    role VARCHAR(32) NOT NULL COMMENT '消息角色：user=用户，assistant=机器人',
    content TEXT NOT NULL COMMENT '消息内容',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_memory_scope_key_id (memory_scope, memory_key, id),
    KEY idx_memory_chat_id (chat_id),
    KEY idx_memory_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent会话记忆表';

CREATE TABLE IF NOT EXISTS agent_conversation_summary (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    memory_scope VARCHAR(32) NOT NULL COMMENT '记忆范围：GROUP=群聊，PRIVATE=私聊',
    memory_key VARCHAR(255) NOT NULL COMMENT '记忆键：当前使用chat_id',
    chat_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '飞书会话ID',
    summary MEDIUMTEXT NOT NULL COMMENT '压缩后的历史上下文摘要',
    compressed_message_count INT NOT NULL DEFAULT 0 COMMENT '累计压缩消息条数',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_scope_key (memory_scope, memory_key),
    KEY idx_summary_chat_id (chat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent会话压缩摘要表';

CREATE TABLE IF NOT EXISTS feishu_user_oauth_token (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    app_id VARCHAR(128) NOT NULL COMMENT '飞书应用ID',
    user_open_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '授权用户open_id',
    user_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '授权用户user_id',
    scope_text TEXT NOT NULL COMMENT '用户已授权scope，空格分隔',
    access_token TEXT NOT NULL COMMENT '用户access_token，按需求不加密保存',
    refresh_token TEXT NOT NULL COMMENT '用户refresh_token，按需求不加密保存',
    expires_at TIMESTAMP NULL DEFAULT NULL COMMENT 'access_token过期时间',
    refresh_expires_at TIMESTAMP NULL DEFAULT NULL COMMENT 'refresh_token过期时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_open_id (app_id, user_open_id),
    KEY idx_oauth_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='飞书用户OAuth token表';

CREATE TABLE IF NOT EXISTS feishu_oauth_state (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    state VARCHAR(128) NOT NULL COMMENT 'OAuth state随机串',
    app_id VARCHAR(128) NOT NULL COMMENT '飞书应用ID',
    user_open_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '触发授权的用户open_id',
    user_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '触发授权的用户user_id',
    chat_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '触发授权的会话ID',
    message_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '触发授权的消息ID',
    scope_text TEXT NOT NULL COMMENT '本次申请的scope，空格分隔',
    used TINYINT NOT NULL DEFAULT 0 COMMENT '是否已使用：0=未使用，1=已使用',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_oauth_state (state),
    KEY idx_oauth_state_user (app_id, user_open_id),
    KEY idx_oauth_state_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='飞书OAuth授权状态表';

CREATE TABLE IF NOT EXISTS workflow_definition (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    workflow_code VARCHAR(128) NOT NULL COMMENT '工作流编码，业务唯一',
    workflow_name VARCHAR(255) NOT NULL COMMENT '工作流名称',
    domain VARCHAR(64) NOT NULL DEFAULT '' COMMENT '业务域：feishu/ecommerce/mixed等',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0=停用，1=启用',
    risk_level VARCHAR(32) NOT NULL DEFAULT 'LOW' COMMENT '风险等级：LOW/MEDIUM/HIGH',
    require_user_confirm TINYINT NOT NULL DEFAULT 0 COMMENT '是否需要用户二次确认：0=否，1=是',
    intent_keywords TEXT NOT NULL COMMENT '触发关键词，JSON数组',
    description TEXT NOT NULL COMMENT '工作流说明',
    source_format VARCHAR(32) NOT NULL DEFAULT 'YAML' COMMENT '导入来源格式：YAML/JSON',
    source_content MEDIUMTEXT NOT NULL COMMENT '原始导入内容，便于排查和回放',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_code (workflow_code),
    KEY idx_workflow_enabled_domain (enabled, domain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可配置工作流定义表';

CREATE TABLE IF NOT EXISTS workflow_step (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    workflow_code VARCHAR(128) NOT NULL COMMENT '工作流编码',
    step_no INT NOT NULL COMMENT '步骤序号，从1开始',
    step_name VARCHAR(255) NOT NULL COMMENT '步骤名称',
    executor_type VARCHAR(64) NOT NULL COMMENT '执行器类型：MCP/CLI/JAVA_TOOL/LLM_SUMMARY/FEISHU_REPLY',
    tool_name VARCHAR(255) NOT NULL DEFAULT '' COMMENT '工具名称，例如 ecommerce.query_customer_orders',
    input_template MEDIUMTEXT NOT NULL COMMENT '入参模板，JSON对象',
    output_key VARCHAR(128) NOT NULL DEFAULT '' COMMENT '本步骤输出变量名',
    on_error VARCHAR(32) NOT NULL DEFAULT 'STOP' COMMENT '失败处理策略：STOP/RETRY/FALLBACK',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_step_no (workflow_code, step_no),
    KEY idx_workflow_step_tool (executor_type, tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可配置工作流步骤表';
