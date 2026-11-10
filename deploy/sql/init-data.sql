SET NAMES utf8mb4;
USE payment_db;

DELETE FROM mock_channel_deposit;
DELETE FROM audit_log;
DELETE FROM account_command;
DELETE FROM risk_event;
DELETE FROM transfer_order;
DELETE FROM transaction_flow;
DELETE FROM account_reservation;
DELETE FROM `transaction`;
DELETE FROM risk_rule;
DELETE FROM account_status_log;
DELETE FROM account;
DELETE FROM credential;
DELETE FROM customer;

INSERT INTO customer (customer_id, identity_id, name, phone, email, real_name_status, status) VALUES
  ('CUS-0001', 'UID-000001', '张三', '13800000001', 'zhangsan@demo.com', 'VERIFIED', 'NORMAL'),
  ('CUS-0002', 'UID-000002', '李四', '13800000002', 'lisi@demo.com', 'VERIFIED', 'NORMAL'),
  ('CUS-ADMIN', 'OPS-ADMIN',  'Admin', NULL, NULL, 'VERIFIED', 'NORMAL')
ON DUPLICATE KEY UPDATE
  name = VALUES(name), phone = VALUES(phone), email = VALUES(email),
  real_name_status = VALUES(real_name_status), status = VALUES(status);

INSERT INTO credential (customer_id, username, password_hash, credential_type, status) VALUES
  ('CUS-0001', 'zhangsan', 'd3ad9315b7be5dd53b31a273b3b3aba5defe700808305aa16a3062b76658a791', 'USERNAME', 'NORMAL'),
  ('CUS-0002', 'lisi',     'd3ad9315b7be5dd53b31a273b3b3aba5defe700808305aa16a3062b76658a791', 'USERNAME', 'NORMAL'),
  ('CUS-ADMIN','admin',    '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9',  'USERNAME', 'NORMAL')
ON DUPLICATE KEY UPDATE
  password_hash = VALUES(password_hash), status = VALUES(status);

INSERT INTO account (account_id, customer_id, currency, status, book_balance, reserved_amount) VALUES
  ('ACC_S2_001', 'CUS-0001', 'CNY', 'ACTIVE', 50000.00, 0.00),
  ('ACC_S2_002', 'CUS-0002', 'CNY', 'ACTIVE', 30000.00, 0.00)
ON DUPLICATE KEY UPDATE
  book_balance = VALUES(book_balance), reserved_amount = VALUES(reserved_amount),
  status = VALUES(status);

INSERT INTO account_status_log (account_id, previous_status, new_status, change_reason, operator_identity_id) VALUES
  ('ACC_S2_001', NULL, 'ACTIVE', 'OPEN', 'SYSTEM'),
  ('ACC_S2_002', NULL, 'ACTIVE', 'OPEN', 'SYSTEM');

INSERT INTO risk_rule (rule_id, rule_type, deposit_single_limit, outgoing_single_limit, outgoing_daily_limit, enabled, rule_version) VALUES
  ('RK-01', 'ACCOUNT_STATUS', NULL,    NULL,    NULL,    1, 1),
  ('RK-02', 'LIMIT_POLICY',   50000.00, 10000.00, 50000.00, 1, 1)
ON DUPLICATE KEY UPDATE
  rule_type = VALUES(rule_type), deposit_single_limit = VALUES(deposit_single_limit),
  outgoing_single_limit = VALUES(outgoing_single_limit), outgoing_daily_limit = VALUES(outgoing_daily_limit),
  enabled = VALUES(enabled), rule_version = VALUES(rule_version);
