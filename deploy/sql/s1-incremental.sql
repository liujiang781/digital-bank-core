-- S1 用户注册、资料维护和在线授权增量；先执行基础 schema.sql。
-- 只新增表，不清空已有客户、账户、交易或审计数据。
CREATE TABLE IF NOT EXISTS user_command (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    identity_id VARCHAR(64) NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    request_id CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json TEXT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_command_request (identity_id, business_type, request_id)
);

-- role_id 为 NULL 的记录表示显式撤权；不能回落到静态授权配置。
CREATE TABLE IF NOT EXISTS staff_authorization (
    identity_id VARCHAR(64) NOT NULL,
    role_id VARCHAR(16) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (identity_id),
    CONSTRAINT chk_staff_role CHECK (role_id IS NULL OR role_id IN ('OPERATIONS', 'RISK'))
);

CREATE TABLE IF NOT EXISTS staff_account_scope (
    identity_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    PRIMARY KEY (identity_id, account_id),
    CONSTRAINT fk_staff_scope_authorization FOREIGN KEY (identity_id)
        REFERENCES staff_authorization (identity_id)
);
