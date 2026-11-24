-- Existing MySQL 8 databases based on the schema before RK-06. Apply once.
-- New installations receive these constraints and the index directly from schema.sql.
ALTER TABLE risk_rule
    DROP CHECK chk_risk_rule_type,
    DROP CHECK chk_risk_rule_limits_shape,
    ADD CONSTRAINT chk_risk_rule_type
        CHECK (rule_type IN ('LIMIT_POLICY', 'ACCOUNT_STATUS', 'FREQUENCY_MONITOR')),
    ADD CONSTRAINT chk_risk_rule_limits_shape CHECK (
        (rule_type = 'LIMIT_POLICY'
            AND deposit_single_limit IS NOT NULL
            AND outgoing_single_limit IS NOT NULL
            AND outgoing_daily_limit IS NOT NULL)
        OR (rule_type IN ('ACCOUNT_STATUS', 'FREQUENCY_MONITOR')
            AND deposit_single_limit IS NULL
            AND outgoing_single_limit IS NULL
            AND outgoing_daily_limit IS NULL)
    );

ALTER TABLE risk_event
    DROP CHECK chk_risk_event_result,
    DROP CHECK chk_risk_event_reason,
    ADD CONSTRAINT chk_risk_event_result CHECK (result IN ('PASS', 'REJECT', 'MARK')),
    ADD CONSTRAINT chk_risk_event_reason
        CHECK (reason_code IN ('ACCOUNT_STATUS', 'SINGLE_LIMIT', 'DAILY_LIMIT', 'HIGH_FREQUENCY'));

CREATE INDEX idx_transaction_accepted_account ON `transaction` (accepted_at, account_id);
INSERT INTO risk_rule (rule_id, rule_type, enabled, rule_version)
VALUES ('RK-06', 'FREQUENCY_MONITOR', 1, 1);
