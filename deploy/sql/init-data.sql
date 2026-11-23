SET NAMES utf8mb4;
USE payment_db;

DELETE FROM staff_account_scope;
DELETE FROM staff_authorization;
DELETE FROM user_command;
DELETE FROM mock_channel_deposit;
DELETE FROM audit_log;
DELETE FROM account_command;
DELETE FROM risk_event;
DELETE FROM transfer_order;
DELETE FROM transaction_flow;
DELETE FROM account_reservation;
DELETE FROM `transaction`;
DELETE FROM risk_rule;
DELETE FROM risk_freeze_release;
DELETE FROM account_status_log;
DELETE FROM account;
DELETE FROM credential;
DELETE FROM customer;

INSERT INTO customer (customer_id, identity_id, name, phone, email, real_name_status, status) VALUES
  ('CUS-0001', 'UID-000001', '张三', '13800000001', 'zhangsan@demo.com', 'VERIFIED', 'NORMAL'),
  ('CUS-0002', 'UID-000002', '李四', '13800000002', 'lisi@demo.com', 'VERIFIED', 'NORMAL'),
  ('CUS-ADMIN', 'OPS-ADMIN',  'Admin', NULL, NULL, 'VERIFIED', 'NORMAL'),
  ('CUS-RISK',  'OPS-RISK',   '风控管理员', NULL, NULL, 'VERIFIED', 'NORMAL')
ON DUPLICATE KEY UPDATE
  name = VALUES(name), phone = VALUES(phone), email = VALUES(email),
  real_name_status = VALUES(real_name_status), status = VALUES(status);

INSERT INTO credential (customer_id, username, password_hash, credential_type, status) VALUES
  ('CUS-0001', 'zhangsan', '$2a$12$NBP0BKTPbnQ4xufuZWjSKu6mmkiEHFJ2yp.m2ditNCn2Dern93.yy', 'USERNAME', 'NORMAL'),
  ('CUS-0002', 'lisi',     '$2a$12$NBP0BKTPbnQ4xufuZWjSKu6mmkiEHFJ2yp.m2ditNCn2Dern93.yy', 'USERNAME', 'NORMAL'),
  ('CUS-ADMIN','admin',    '$2a$12$RVbq8PQr7RxzHb6FXPaPPuVFOKUZyMpOmjUWhHib90qIyivlDfoKW',  'USERNAME', 'NORMAL'),
  ('CUS-RISK', 'risk01',   '$2a$12$RVbq8PQr7RxzHb6FXPaPPuVFOKUZyMpOmjUWhHib90qIyivlDfoKW',  'USERNAME', 'NORMAL')
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

-- 管理账号角色授权。开头清空了这两张表，这里必须写回，否则账号能登录但没有角色和账户范围，
-- 登录后服务端查不到授权，接口返回 403「没有权限」。
INSERT INTO staff_authorization (identity_id, role_id, version) VALUES
  ('OPS-ADMIN', 'OPERATIONS', 1),
  ('OPS-RISK',  'RISK',       1)
ON DUPLICATE KEY UPDATE
  role_id = VALUES(role_id);

-- 账户范围。兼容两套账户编号：init-data.sql 建的是 ACC_S2_001/002，
-- 本机演示库用的是 ACC-0001/0002（见 restore-demo.sql）。
-- 两套都授权，换电脑初始化后两个管理账号都能查到账户。
INSERT INTO staff_account_scope (identity_id, account_id) VALUES
  ('OPS-ADMIN', 'ACC_S2_001'), ('OPS-ADMIN', 'ACC_S2_002'),
  ('OPS-ADMIN', 'ACC-0001'),   ('OPS-ADMIN', 'ACC-0002'),
  ('OPS-RISK',  'ACC_S2_001'), ('OPS-RISK',  'ACC_S2_002'),
  ('OPS-RISK',  'ACC-0001'),   ('OPS-RISK',  'ACC-0002')
ON DUPLICATE KEY UPDATE
  account_id = VALUES(account_id);
