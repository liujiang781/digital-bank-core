-- Run once after S4's base schema.sql, against the same payment_db database.
-- Additive migration: this file does not drop or re-create existing banking data.
CREATE TABLE IF NOT EXISTS account_command (
    identity_id VARCHAR(64) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    result_json TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (identity_id, command_type, request_id)
);

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
);
