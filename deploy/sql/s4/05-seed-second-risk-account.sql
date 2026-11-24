-- 补种第二个 RISK 账号：RISK-APPROVER（口令与 OPS-RISK 相同）
-- 原因：风控变更（名单/规则）要求「提交人 ≠ 审批人」双人复核，
--       种子中只有 OPS-RISK 一个 RISK 账号，导致所有变更永远停在 PENDING。
-- 幂等：可重复执行。
USE payment_db;

INSERT INTO customer (customer_id, identity_id, name, phone, email, real_name_status, status)
SELECT 'CUS-RISK-APPR', 'RISK-APPROVER', 'Risk Approver', '13900000010', 'risk.approver@demo.local', real_name_status, status
  FROM customer WHERE identity_id = 'OPS-RISK'
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO credential (customer_id, username, password_hash, credential_type, status)
SELECT 'CUS-RISK-APPR', 'RISK-APPROVER', password_hash, 'USERNAME', 'NORMAL'
  FROM credential WHERE customer_id = 'CUS-RISK'
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash);

INSERT INTO staff_authorization (identity_id, role_id) VALUES ('RISK-APPROVER', 'RISK')
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO staff_account_scope (identity_id, account_id) VALUES
  ('RISK-APPROVER', 'ACC_S2_001'),
  ('RISK-APPROVER', 'ACC_S2_002')
ON DUPLICATE KEY UPDATE account_id = VALUES(account_id);

SELECT c.identity_id, c.customer_id, c.name, s.role_id
  FROM customer c LEFT JOIN staff_authorization s ON s.identity_id = c.identity_id
 WHERE c.identity_id IN ('OPS-RISK', 'RISK-APPROVER');
