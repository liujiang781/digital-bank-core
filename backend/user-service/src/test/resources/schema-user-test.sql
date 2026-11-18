DROP TABLE IF EXISTS staff_account_scope;
DROP TABLE IF EXISTS staff_authorization;
DROP TABLE IF EXISTS user_command;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS account;
DROP TABLE IF EXISTS credential;
DROP TABLE IF EXISTS customer;

CREATE TABLE customer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id VARCHAR(32) NOT NULL UNIQUE,
    identity_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(20) UNIQUE,
    email VARCHAR(100),
    real_name_status VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL DEFAULT 1
);
CREATE TABLE credential (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id VARCHAR(32) NOT NULL REFERENCES customer(customer_id),
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    credential_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL
);
CREATE TABLE account (
    account_id VARCHAR(32) PRIMARY KEY,
    customer_id VARCHAR(32) NOT NULL REFERENCES customer(customer_id),
    status VARCHAR(16) NOT NULL
);
CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_identity_id VARCHAR(64) NOT NULL,
    `action` VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_ref VARCHAR(64),
    reason VARCHAR(255),
    result VARCHAR(16),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
