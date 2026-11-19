-- H2 (MySQL compatibility mode) schema for risk-service tests only.
CREATE TABLE IF NOT EXISTS account (
    account_id VARCHAR(32) PRIMARY KEY,
    book_balance DECIMAL(18,2) NOT NULL DEFAULT 0,
    reserved_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS `transaction` (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_no VARCHAR(32) NOT NULL,
    identity_id VARCHAR(64) NOT NULL,
    business_type VARCHAR(24) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_transaction_trade_no UNIQUE (trade_no),
    CONSTRAINT fk_transaction_account FOREIGN KEY (account_id) REFERENCES account (account_id)
);

CREATE TABLE IF NOT EXISTS risk_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id VARCHAR(32) NOT NULL,
    rule_type VARCHAR(24) NOT NULL,
    deposit_single_limit DECIMAL(18,2),
    outgoing_single_limit DECIMAL(18,2),
    outgoing_daily_limit DECIMAL(18,2),
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    rule_version INT NOT NULL DEFAULT 1,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_risk_rule_rule_id UNIQUE (rule_id)
);

CREATE TABLE IF NOT EXISTS risk_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(32) NOT NULL,
    trade_no VARCHAR(32) NOT NULL,
    rule_id VARCHAR(32) NOT NULL,
    rule_version INT NOT NULL,
    operator_identity_id VARCHAR(64) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    result VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_risk_event_event_id UNIQUE (event_id),
    CONSTRAINT fk_risk_event_transaction FOREIGN KEY (trade_no) REFERENCES `transaction`(trade_no),
    CONSTRAINT fk_risk_event_rule FOREIGN KEY (rule_id) REFERENCES risk_rule(rule_id)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_identity_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_ref VARCHAR(64),
    reason VARCHAR(255),
    result VARCHAR(64),
    trade_no VARCHAR(32),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_alert_disposal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(32) NOT NULL,
    identity_id VARCHAR(64) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    basis VARCHAR(1000) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    trade_no VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_alert_disposal UNIQUE (event_id, identity_id, request_id)
);

CREATE TABLE IF NOT EXISTS risk_change_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    change_id VARCHAR(64) NOT NULL,
    change_type VARCHAR(24) NOT NULL,
    target_ref VARCHAR(64) NOT NULL,
    request_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    submitted_by VARCHAR(64) NOT NULL,
    submit_reason VARCHAR(255),
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    approved_by VARCHAR(64),
    approval_note VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at DATETIME,
    CONSTRAINT uk_change_request UNIQUE (change_type, submitted_by, request_id),
    CONSTRAINT uk_change_id UNIQUE (change_id)
);

CREATE TABLE IF NOT EXISTS risk_change_approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    change_id VARCHAR(64) NOT NULL,
    identity_id VARCHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    note VARCHAR(255),
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_change_approval UNIQUE (change_id, identity_id, request_id)
);

CREATE TABLE IF NOT EXISTS risk_list_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_id VARCHAR(64) NOT NULL,
    list_type VARCHAR(16) NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    reason_code VARCHAR(32),
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    rule_version INT NOT NULL DEFAULT 1,
    change_id VARCHAR(64),
    created_by VARCHAR(64) NOT NULL,
    approved_by VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_list_entry UNIQUE (entry_id),
    CONSTRAINT uk_list_subject UNIQUE (list_type, subject_type, subject_id)
);
