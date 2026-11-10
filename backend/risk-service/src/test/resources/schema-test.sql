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
