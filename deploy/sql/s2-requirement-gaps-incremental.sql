-- Existing payment_db installations only need this additive migration.
USE payment_db;

CREATE TABLE IF NOT EXISTS risk_freeze_release (
    restriction_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    approved_by VARCHAR(64) NOT NULL,
    basis VARCHAR(200) NOT NULL,
    approved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_risk_freeze_release_restriction FOREIGN KEY (restriction_id) REFERENCES account_status_log(id)
);
