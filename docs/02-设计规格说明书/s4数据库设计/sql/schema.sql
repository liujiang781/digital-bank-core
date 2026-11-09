-- =============================================================
-- 数字银行原型 · 数据库建表脚本（MySQL 8.0.16+）
-- 依据：docs/02-设计规格说明书/数据库设计.md（3.1 映射规则、表 2-2、表 2-3、索引说明）
-- 约定：utf8mb4 / InnoDB / snake_case / 表名不加前缀 / 金额 DECIMAL(18,2) / 时间 DATETIME
-- 说明：每表保留代理主键 id，业务标识（customer_id、account_id、trade_no 等）
--       建唯一约束并作为外键引用目标，跨服务只传业务标识。
-- 执行：mysql -u root -p < docs/02-设计规格说明书/sql/schema.sql
-- 枚举口径（2026-10-27 与 C 侧字面量对齐）：账户状态 ACTIVE/FROZEN/CLOSED；
--   交易状态 ACCEPTED/PROCESSING/UNKNOWN/SUCCESS/RECOVERING/FAILED/REJECTED（7 态）；
--   业务类型 DEPOSIT/WITHDRAWAL/INTERNAL_TRANSFER/EXTERNAL_TRANSFER/ACCOUNT_OPEN。
-- =============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS payment_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE payment_db;

-- 按外键依赖逆序删除，保证脚本可重复执行
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS risk_event;
DROP TABLE IF EXISTS risk_rule;
DROP TABLE IF EXISTS transfer_order;
DROP TABLE IF EXISTS transaction_flow;
DROP TABLE IF EXISTS `transaction`;
DROP TABLE IF EXISTS account_status_log;
DROP TABLE IF EXISTS account;
DROP TABLE IF EXISTS credential;
DROP TABLE IF EXISTS customer;

-- =============================================================
-- 1. customer 客户表
-- =============================================================
CREATE TABLE customer (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    customer_id VARCHAR(32) NOT NULL COMMENT '客户业务标识(表间外键口径)',
    identity_id VARCHAR(64) NOT NULL COMMENT '身份标识(对外交互/幂等登记口径), 与 customer_id 是不同的东西',
    name VARCHAR(100) NOT NULL COMMENT '客户名称',
    phone VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    email VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    real_name_status VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED' COMMENT '实名状态: UNVERIFIED=未实名, VERIFIED=已实名, REJECTED=实名未通过',
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '客户状态: NORMAL=正常, FROZEN=冻结(待B确认客户级冻结是否存在), CANCELLED=注销',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_customer_id (customer_id),
    UNIQUE KEY uk_customer_identity_id (identity_id),
    UNIQUE KEY uk_customer_phone (phone),
    KEY idx_customer_status (status),
    CONSTRAINT chk_customer_status CHECK (status IN ('NORMAL', 'FROZEN', 'CANCELLED')),
    CONSTRAINT chk_customer_real_name_status CHECK (real_name_status IN ('UNVERIFIED', 'VERIFIED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户表';

-- =============================================================
-- 2. credential 登录凭证表
-- =============================================================
CREATE TABLE credential (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    customer_id VARCHAR(32) NOT NULL COMMENT '所属客户业务标识',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希(不可逆)',
    credential_type VARCHAR(16) NOT NULL DEFAULT 'USERNAME' COMMENT '凭证类型: USERNAME=用户名, MOBILE=手机号, EMAIL=邮箱',
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '凭证状态: NORMAL=正常, LOCKED=锁定, CANCELLED=注销',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_credential_username (username),
    KEY idx_credential_customer (customer_id),
    CONSTRAINT fk_credential_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id),
    CONSTRAINT chk_credential_type CHECK (credential_type IN ('USERNAME', 'MOBILE', 'EMAIL')),
    CONSTRAINT chk_credential_status CHECK (status IN ('NORMAL', 'LOCKED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录凭证表';

-- =============================================================
-- 3. account 账户表（BR-03 状态、BR-04 余额不变式）
-- =============================================================
CREATE TABLE account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    account_id VARCHAR(32) NOT NULL COMMENT '账户业务标识',
    customer_id VARCHAR(32) NOT NULL COMMENT '所属客户业务标识',
    currency CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '币种(课程模拟仅人民币)',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '账户状态: ACTIVE=正常, FROZEN=冻结, CLOSED=注销',
    book_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '账面余额 B',
    reserved_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '预留金额 R',
    personal_single_limit DECIMAL(18,2) DEFAULT NULL COMMENT '个人单笔出账限额(NULL=沿用规则)',
    personal_daily_limit DECIMAL(18,2) DEFAULT NULL COMMENT '个人日累计限额(NULL=沿用规则)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_account_id (account_id),
    KEY idx_account_customer (customer_id),
    KEY idx_account_status (status),
    CONSTRAINT fk_account_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id),
    CONSTRAINT chk_account_currency CHECK (currency = 'CNY'),
    CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT chk_account_book_balance CHECK (book_balance >= 0),
    CONSTRAINT chk_account_reserved CHECK (reserved_amount >= 0 AND reserved_amount <= book_balance),
    CONSTRAINT chk_account_personal_single_limit CHECK (personal_single_limit IS NULL OR personal_single_limit > 0),
    CONSTRAINT chk_account_personal_daily_limit CHECK (personal_daily_limit IS NULL OR personal_daily_limit > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账户表';

-- =============================================================
-- 4. account_status_log 账户状态变更记录表（只追加）
-- =============================================================
CREATE TABLE account_status_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    account_id VARCHAR(32) NOT NULL COMMENT '账户业务标识',
    previous_status VARCHAR(16) DEFAULT NULL COMMENT '变更前状态',
    new_status VARCHAR(16) NOT NULL COMMENT '变更后状态',
    change_reason VARCHAR(255) DEFAULT NULL COMMENT '变更原因',
    operator_identity_id VARCHAR(64) DEFAULT NULL COMMENT '操作人身份(系统或人工)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间(只追加表不更新)',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '版本号(形式保留)',
    PRIMARY KEY (id),
    KEY idx_account_status_log_account_created (account_id, created_at),
    CONSTRAINT fk_account_status_log_account FOREIGN KEY (account_id) REFERENCES account (account_id),
    CONSTRAINT chk_account_status_log_new CHECK (new_status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT chk_account_status_log_prev CHECK (previous_status IS NULL OR previous_status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账户状态变更记录表';

-- =============================================================
-- 5. transaction 交易表（BR-01 金额、BR-11/12 幂等）
-- =============================================================
CREATE TABLE `transaction` (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    trade_no VARCHAR(32) NOT NULL COMMENT '交易流水号(全局唯一)',
    identity_id VARCHAR(64) NOT NULL COMMENT '发起身份(幂等键首维), 多态引用: 客户=值域同 customer.identity_id, 运营/系统为各自身份标识, 故不建外键',
    business_type VARCHAR(24) NOT NULL COMMENT '业务类型: DEPOSIT/WITHDRAWAL/INTERNAL_TRANSFER/EXTERNAL_TRANSFER/ACCOUNT_OPEN(与C基线一致)',
    request_id VARCHAR(64) NOT NULL COMMENT '请求号(客户端生成)',
    request_hash CHAR(64) NOT NULL COMMENT '请求内容摘要(SHA-256), 同键异内容判冲突',
    account_id VARCHAR(32) NOT NULL COMMENT '付款方主账户(ACCOUNT_OPEN 时为本次新开账户标识, 同事务内先建账户)',
    amount DECIMAL(18,2) NOT NULL COMMENT '交易金额',
    currency CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED' COMMENT '状态: ACCEPTED/PROCESSING/UNKNOWN/SUCCESS/RECOVERING/FAILED/REJECTED(7态, 字面量与图2-8/2-12一致)',
    channel_evidence_id VARCHAR(64) DEFAULT NULL COMMENT '渠道凭证号(模拟外部回执)',
    last_channel_result VARCHAR(32) DEFAULT NULL COMMENT '最近渠道结果',
    last_checked_at DATETIME DEFAULT NULL COMMENT '最近查询时间',
    failure_reason VARCHAR(255) DEFAULT NULL COMMENT '失败或拒绝原因',
    accepted_at DATETIME DEFAULT NULL COMMENT '受理时间',
    completed_at DATETIME DEFAULT NULL COMMENT '完成时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction_trade_no (trade_no),
    UNIQUE KEY uk_transaction_request (identity_id, business_type, request_id),
    KEY idx_transaction_request_lookup (request_id),
    KEY idx_transaction_account_created (account_id, created_at),
    KEY idx_transaction_status (status),
    CONSTRAINT fk_transaction_account FOREIGN KEY (account_id) REFERENCES account (account_id),
    CONSTRAINT chk_transaction_amount CHECK (amount > 0 OR (business_type = 'ACCOUNT_OPEN' AND amount = 0)),
    CONSTRAINT chk_transaction_currency CHECK (currency = 'CNY'),
    CONSTRAINT chk_transaction_business_type CHECK (business_type IN ('DEPOSIT', 'WITHDRAWAL', 'INTERNAL_TRANSFER', 'EXTERNAL_TRANSFER', 'ACCOUNT_OPEN')),
    CONSTRAINT chk_transaction_status CHECK (status IN ('ACCEPTED', 'PROCESSING', 'UNKNOWN', 'SUCCESS', 'RECOVERING', 'FAILED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易表';

-- =============================================================
-- 6. transaction_flow 资金流水表（BR-19，只写实际资金事件，只追加）
-- =============================================================
CREATE TABLE transaction_flow (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    entry_id VARCHAR(32) NOT NULL COMMENT '流水标识(全局唯一)',
    trade_no VARCHAR(32) NOT NULL COMMENT '交易流水号(双方流水同一 trade_no)',
    account_id VARCHAR(32) NOT NULL COMMENT '账户业务标识',
    entry_role VARCHAR(24) NOT NULL COMMENT '流水角色: TRANSFER_OUT/TRANSFER_IN/DEPOSIT_IN/WITHDRAW_OUT',
    direction VARCHAR(8) NOT NULL COMMENT '资金方向: DEBIT=出账, CREDIT=入账',
    amount DECIMAL(18,2) NOT NULL COMMENT '变动金额',
    book_balance_after DECIMAL(18,2) NOT NULL COMMENT '记账后账面余额',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '资金发生时间(对应 occurred_at)',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间(只追加表不更新)',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '版本号(形式保留)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction_flow_entry_id (entry_id),
    UNIQUE KEY uk_transaction_flow_role (trade_no, account_id, entry_role),
    KEY idx_transaction_flow_account_created (account_id, created_at),
    KEY idx_transaction_flow_trade_no (trade_no),
    CONSTRAINT fk_transaction_flow_transaction FOREIGN KEY (trade_no) REFERENCES `transaction` (trade_no),
    CONSTRAINT fk_transaction_flow_account FOREIGN KEY (account_id) REFERENCES account (account_id),
    CONSTRAINT chk_transaction_flow_amount CHECK (amount > 0),
    CONSTRAINT chk_transaction_flow_balance CHECK (book_balance_after >= 0),
    CONSTRAINT chk_transaction_flow_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_transaction_flow_role CHECK (entry_role IN ('TRANSFER_OUT', 'TRANSFER_IN', 'DEPOSIT_IN', 'WITHDRAW_OUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资金流水表';

-- =============================================================
-- 7. transfer_order 转账单表（BR-15 本行一致性前提）
-- =============================================================
CREATE TABLE transfer_order (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    transfer_id VARCHAR(32) NOT NULL COMMENT '转账单标识(全局唯一)',
    trade_no VARCHAR(32) NOT NULL COMMENT '交易流水号(一交易一转账单)',
    transfer_type VARCHAR(16) NOT NULL COMMENT '转账类型: INTERNAL=本行, EXTERNAL=跨行',
    payer_account_id VARCHAR(32) NOT NULL COMMENT '付款方账户',
    payee_account_id VARCHAR(32) DEFAULT NULL COMMENT '收款方账户(本行必填, 跨行为空)',
    external_bank_code VARCHAR(32) DEFAULT NULL COMMENT '外部银行代码(跨行必填)',
    external_payee_ref VARCHAR(64) DEFAULT NULL COMMENT '外部收款方引用(跨行必填, 脱敏)',
    amount DECIMAL(18,2) NOT NULL COMMENT '转账金额',
    currency CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    clearing_status VARCHAR(24) NOT NULL DEFAULT 'NOT_APPLICABLE' COMMENT '清算状态: NOT_APPLICABLE/SUBMITTED/UNKNOWN/CONFIRMED_SUCCESS/CONFIRMED_FAILURE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_transfer_order_transfer_id (transfer_id),
    UNIQUE KEY uk_transfer_order_trade_no (trade_no),
    KEY idx_transfer_order_payer (payer_account_id),
    KEY idx_transfer_order_payee (payee_account_id),
    KEY idx_transfer_order_clearing (clearing_status),
    CONSTRAINT fk_transfer_order_transaction FOREIGN KEY (trade_no) REFERENCES `transaction` (trade_no),
    CONSTRAINT fk_transfer_order_payer FOREIGN KEY (payer_account_id) REFERENCES account (account_id),
    CONSTRAINT fk_transfer_order_payee FOREIGN KEY (payee_account_id) REFERENCES account (account_id),
    CONSTRAINT chk_transfer_order_amount CHECK (amount > 0),
    CONSTRAINT chk_transfer_order_currency CHECK (currency = 'CNY'),
    CONSTRAINT chk_transfer_order_type CHECK (transfer_type IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT chk_transfer_order_accounts CHECK (payee_account_id IS NULL OR payer_account_id <> payee_account_id),
    CONSTRAINT chk_transfer_order_conditional CHECK (
        (transfer_type = 'INTERNAL'
            AND payee_account_id IS NOT NULL
            AND external_bank_code IS NULL
            AND external_payee_ref IS NULL
            AND clearing_status = 'NOT_APPLICABLE')
        OR
        (transfer_type = 'EXTERNAL'
            AND payee_account_id IS NULL
            AND external_bank_code IS NOT NULL
            AND external_payee_ref IS NOT NULL
            AND clearing_status IN ('SUBMITTED', 'UNKNOWN', 'CONFIRMED_SUCCESS', 'CONFIRMED_FAILURE'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='转账单表';

-- =============================================================
-- 8. risk_rule 风控规则表（BR-05 限额）
-- =============================================================
CREATE TABLE risk_rule (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    rule_id VARCHAR(32) NOT NULL COMMENT '规则标识(RK-01 账户状态, RK-02 限额策略)',
    rule_type VARCHAR(24) NOT NULL COMMENT '规则类型: LIMIT_POLICY/ACCOUNT_STATUS',
    deposit_single_limit DECIMAL(18,2) DEFAULT NULL COMMENT '存款单笔限额',
    outgoing_single_limit DECIMAL(18,2) DEFAULT NULL COMMENT '出账单笔限额',
    outgoing_daily_limit DECIMAL(18,2) DEFAULT NULL COMMENT '出账日累计限额',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 0=否, 1=是',
    rule_version INT NOT NULL DEFAULT 1 COMMENT '规则版本',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_rule_rule_id (rule_id),
    KEY idx_risk_rule_type_enabled (rule_type, enabled),
    CONSTRAINT chk_risk_rule_type CHECK (rule_type IN ('LIMIT_POLICY', 'ACCOUNT_STATUS')),
    CONSTRAINT chk_risk_rule_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT chk_risk_rule_version CHECK (rule_version > 0),
    CONSTRAINT chk_risk_rule_limits_positive CHECK (
        (deposit_single_limit IS NULL OR deposit_single_limit > 0)
        AND (outgoing_single_limit IS NULL OR outgoing_single_limit > 0)
        AND (outgoing_daily_limit IS NULL OR outgoing_daily_limit > 0)
    ),
    CONSTRAINT chk_risk_rule_limits_shape CHECK (
        (rule_type = 'LIMIT_POLICY'
            AND deposit_single_limit IS NOT NULL
            AND outgoing_single_limit IS NOT NULL
            AND outgoing_daily_limit IS NOT NULL)
        OR
        (rule_type = 'ACCOUNT_STATUS'
            AND deposit_single_limit IS NULL
            AND outgoing_single_limit IS NULL
            AND outgoing_daily_limit IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风控规则表';

-- =============================================================
-- 9. risk_event 风险事件表（拒绝只写事件，不写流水）
-- =============================================================
CREATE TABLE risk_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    event_id VARCHAR(32) NOT NULL COMMENT '事件标识(全局唯一)',
    trade_no VARCHAR(32) NOT NULL COMMENT '被拦截交易流水号',
    rule_id VARCHAR(32) NOT NULL COMMENT '命中规则标识',
    rule_version INT NOT NULL COMMENT '命中时的规则版本',
    operator_identity_id VARCHAR(64) NOT NULL COMMENT '操作身份(SYSTEM 或人工)',
    reason_code VARCHAR(32) NOT NULL COMMENT '原因码: ACCOUNT_STATUS/SINGLE_LIMIT/DAILY_LIMIT',
    result VARCHAR(16) NOT NULL COMMENT '判定结果: PASS/REJECT',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '命中时间(对应 occurred_at)',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间(只追加表不更新)',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '版本号(形式保留)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_event_event_id (event_id),
    KEY idx_risk_event_trade_no (trade_no),
    KEY idx_risk_event_rule_created (rule_id, created_at),
    CONSTRAINT fk_risk_event_transaction FOREIGN KEY (trade_no) REFERENCES `transaction` (trade_no),
    CONSTRAINT fk_risk_event_rule FOREIGN KEY (rule_id) REFERENCES risk_rule (rule_id),
    CONSTRAINT chk_risk_event_result CHECK (result IN ('PASS', 'REJECT')),
    CONSTRAINT chk_risk_event_reason CHECK (reason_code IN ('ACCOUNT_STATUS', 'SINGLE_LIMIT', 'DAILY_LIMIT')),
    CONSTRAINT chk_risk_event_rule_version CHECK (rule_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风险事件表';

-- =============================================================
-- 10. audit_log 审计日志表（NFR-05，只追加；不建外键）
-- =============================================================
CREATE TABLE audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    operator_identity_id VARCHAR(64) NOT NULL COMMENT '操作者身份',
    `action` VARCHAR(32) NOT NULL COMMENT '动作: LOGIN/ACCOUNT_FREEZE/TRANSFER_REJECT 等',
    target_type VARCHAR(32) NOT NULL COMMENT '操作对象类型: account/transaction 等',
    target_ref VARCHAR(64) DEFAULT NULL COMMENT '操作对象业务标识',
    reason VARCHAR(255) DEFAULT NULL COMMENT '原因',
    result VARCHAR(16) DEFAULT NULL COMMENT '结果: SUCCESS/FAILURE/REJECT',
    trade_no VARCHAR(32) DEFAULT NULL COMMENT '关联交易流水号',
    ip_address VARCHAR(45) DEFAULT NULL COMMENT '客户端IP(兼容IPv6)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间(只追加表不更新)',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '版本号(形式保留)',
    PRIMARY KEY (id),
    KEY idx_audit_log_operator_created (operator_identity_id, created_at),
    KEY idx_audit_log_trade_no (trade_no),
    KEY idx_audit_log_target (target_type, target_ref),
    CONSTRAINT chk_audit_log_result CHECK (result IS NULL OR result IN ('SUCCESS', 'FAILURE', 'REJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

SET FOREIGN_KEY_CHECKS = 1;
