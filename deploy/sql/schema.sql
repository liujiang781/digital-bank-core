
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS payment_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE payment_db;

DROP TABLE IF EXISTS staff_account_scope;
DROP TABLE IF EXISTS staff_authorization;
DROP TABLE IF EXISTS user_command;
DROP TABLE IF EXISTS mock_channel_deposit;
DROP TABLE IF EXISTS account_reservation;
DROP TABLE IF EXISTS account_command;
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

-- S3 P2/待评审增量表的初始化定义，镜像 backend/risk-service/src/main/resources/db/s3-risk-incremental.sql。
CREATE TABLE IF NOT EXISTS risk_alert_disposal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(32) NOT NULL,
    identity_id VARCHAR(64) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    basis VARCHAR(1000) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    trade_no VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_disposal (event_id, identity_id, request_id),
    KEY idx_alert_disposal_event (event_id),
    CONSTRAINT chk_alert_decision CHECK (decision IN ('CONFIRMED', 'FALSE_POSITIVE', 'ESCALATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风险告警处置留痕表(S3 专用)';

CREATE TABLE IF NOT EXISTS risk_change_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    change_id VARCHAR(64) NOT NULL,
    change_type VARCHAR(24) NOT NULL,
    target_ref VARCHAR(64) NOT NULL,
    request_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    submitted_by VARCHAR(64) NOT NULL,
    submit_reason VARCHAR(255) DEFAULT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    approved_by VARCHAR(64) DEFAULT NULL,
    approval_note VARCHAR(255) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_change_request (change_type, submitted_by, request_id),
    UNIQUE KEY uk_change_id (change_id),
    KEY idx_change_status (status, created_at),
    CONSTRAINT chk_change_type CHECK (change_type IN ('RULE', 'LIST')),
    CONSTRAINT chk_change_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'APPLIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风控变更审批单表(S3 专用)';

CREATE TABLE IF NOT EXISTS risk_change_approval (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    change_id VARCHAR(64) NOT NULL,
    identity_id VARCHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    note VARCHAR(255) DEFAULT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_change_approval (change_id, identity_id, request_id),
    KEY idx_approval_change (change_id),
    CONSTRAINT chk_approval_decision CHECK (decision IN ('APPROVE', 'REJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风控审批留痕表(S3 专用)';

CREATE TABLE IF NOT EXISTS risk_list_entry (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    entry_id VARCHAR(64) NOT NULL,
    list_type VARCHAR(16) NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    reason_code VARCHAR(32) DEFAULT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    rule_version INT NOT NULL DEFAULT 1,
    change_id VARCHAR(64) DEFAULT NULL,
    created_by VARCHAR(64) NOT NULL,
    approved_by VARCHAR(64) DEFAULT NULL COMMENT '审批人身份(risk-service ListEntryView 需要)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_list_entry (entry_id),
    UNIQUE KEY uk_list_subject (list_type, subject_type, subject_id),
    KEY idx_list_subject (subject_type, subject_id),
    CONSTRAINT chk_list_type CHECK (list_type IN ('BLACKLIST', 'WHITELIST')),
    CONSTRAINT chk_list_subject_type CHECK (subject_type IN ('ACCOUNT', 'CUSTOMER')),
    CONSTRAINT chk_list_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风险名单条目表(S3 专用)';

CREATE TABLE audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    operator_identity_id VARCHAR(64) NOT NULL COMMENT '操作者身份',
    `action` VARCHAR(32) NOT NULL COMMENT '动作: LOGIN/ACCOUNT_FREEZE/TRANSFER_REJECT 等',
    target_type VARCHAR(32) NOT NULL COMMENT '操作对象类型: account/transaction 等',
    target_ref VARCHAR(64) DEFAULT NULL COMMENT '操作对象业务标识',
    reason VARCHAR(255) DEFAULT NULL COMMENT '原因',
    result VARCHAR(64) DEFAULT NULL COMMENT '结果: SUCCESS/FAILURE/REJECT 或人工核对结论码',
    trade_no VARCHAR(32) DEFAULT NULL COMMENT '关联交易流水号',
    ip_address VARCHAR(45) DEFAULT NULL COMMENT '客户端IP(兼容IPv6)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间(只追加表不更新)',
    version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '版本号(形式保留)',
    PRIMARY KEY (id),
    KEY idx_audit_log_operator_created (operator_identity_id, created_at),
    KEY idx_audit_log_trade_no (trade_no),
    KEY idx_audit_log_target (target_type, target_ref),
    CONSTRAINT chk_audit_log_result CHECK (result IS NULL OR CHAR_LENGTH(result) <= 64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

CREATE TABLE IF NOT EXISTS account_command (
    identity_id VARCHAR(64) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    result_json TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (identity_id, command_type, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账户命令幂等表(S2)';

CREATE TABLE IF NOT EXISTS account_reservation (
    trade_no VARCHAR(32) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (trade_no),
    KEY idx_reservation_account_day (account_id, created_at, status),
    CONSTRAINT fk_reservation_trade FOREIGN KEY (trade_no) REFERENCES `transaction`(trade_no),
    CONSTRAINT fk_reservation_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT chk_reservation_amount CHECK (amount > 0),
    CONSTRAINT chk_reservation_status CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账户预留表(S2)';

CREATE TABLE IF NOT EXISTS mock_channel_deposit (
    trade_no VARCHAR(32) NOT NULL COMMENT '原交易流水号(一笔交易一条模拟渠道记录)',
    channel_request_id VARCHAR(64) NOT NULL COMMENT '渠道请求标识, 固定为 CHD+trade_no, 重试不更换',
    account_id VARCHAR(32) NOT NULL COMMENT '入金账户',
    amount DECIMAL(18,2) NOT NULL COMMENT '入金金额',
    currency CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    result VARCHAR(16) NOT NULL COMMENT '渠道结果: SUCCESS/FAILED/UNKNOWN, 明确结果只能由 UNKNOWN 收敛, 终态不翻转',
    evidence_id VARCHAR(64) DEFAULT NULL COMMENT '渠道凭证号, 明确结果时必填',
    reason_code VARCHAR(64) DEFAULT NULL COMMENT 'FAILED 必填如 CHANNEL_DECLINED; UNKNOWN 可填 CHANNEL_TIMEOUT',
    observed_at DATETIME DEFAULT NULL COMMENT '取得本次结果的东八区时间',
    reconcile_attempts INT NOT NULL DEFAULT 0 COMMENT '未知结果自动核对次数(持久化, 不因扫描或重启重置)',
    next_check_at DATETIME DEFAULT NULL COMMENT '下次自动核对时间, 空表示暂停自动核对',
    manual_review_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '自动核对耗尽后转人工核对',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (trade_no),
    UNIQUE KEY uk_channel_request (channel_request_id),
    KEY idx_channel_next_check (manual_review_required, next_check_at),
    CONSTRAINT fk_channel_trade FOREIGN KEY (trade_no) REFERENCES `transaction`(trade_no),
    CONSTRAINT fk_channel_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT chk_channel_amount CHECK (amount > 0),
    CONSTRAINT chk_channel_currency CHECK (currency = 'CNY'),
    CONSTRAINT chk_channel_result CHECK (result IN ('SUCCESS', 'FAILED', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟渠道入金记录表(S3)';

-- 运营核对/恢复重试幂等留痕(S3)：同身份+动作+requestId 只执行一次，同键异内容报 409。
CREATE TABLE IF NOT EXISTS trade_manual_action (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    identity_id VARCHAR(64) NOT NULL COMMENT '运营身份(服务端会话核验)',
    action VARCHAR(24) NOT NULL COMMENT '动作: RECONCILE/RECOVER',
    request_id VARCHAR(64) NOT NULL COMMENT '运营填写的 UUID 请求号',
    request_hash CHAR(64) NOT NULL COMMENT '请求内容 SHA-256, 同键异内容报冲突',
    trade_no VARCHAR(32) NOT NULL COMMENT '关联交易流水号',
    result_json TEXT NOT NULL COMMENT '幂等结果快照(JSON)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次执行时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_manual_action (identity_id, action, request_id),
    KEY idx_manual_action_trade (trade_no, action, created_at),
    CONSTRAINT chk_manual_action CHECK (action IN ('RECONCILE', 'RECOVER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易人工操作幂等留痕表(S3)';

SET FOREIGN_KEY_CHECKS = 1;

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
