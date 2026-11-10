-- H2 (MySQL compatibility mode) schema for transaction-service tests only.
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
    channel_evidence_id VARCHAR(64),
    last_channel_result VARCHAR(32),
    last_checked_at DATETIME,
    failure_reason VARCHAR(255),
    accepted_at DATETIME,
    completed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_transaction_trade_no UNIQUE (trade_no),
    CONSTRAINT uk_transaction_request UNIQUE (identity_id, business_type, request_id),
    CONSTRAINT fk_transaction_account FOREIGN KEY (account_id) REFERENCES account (account_id)
);

CREATE TABLE IF NOT EXISTS mock_channel_deposit (
    trade_no VARCHAR(32) NOT NULL,
    channel_request_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    result VARCHAR(16) NOT NULL,
    evidence_id VARCHAR(64),
    reason_code VARCHAR(64),
    observed_at DATETIME,
    reconcile_attempts INT NOT NULL DEFAULT 0,
    next_check_at DATETIME,
    manual_review_required TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (trade_no),
    CONSTRAINT uk_channel_request UNIQUE (channel_request_id),
    CONSTRAINT fk_channel_trade FOREIGN KEY (trade_no) REFERENCES `transaction`(trade_no),
    CONSTRAINT fk_channel_account FOREIGN KEY (account_id) REFERENCES account(account_id)
);
