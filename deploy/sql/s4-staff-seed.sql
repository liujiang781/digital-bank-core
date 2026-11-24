SET NAMES utf8mb4;
USE payment_db;

-- S4 联调种子：运营/风控测试身份与账户范围
-- 依赖 init-data.sql 已灌入 customer(OPS-ADMIN / UID-000002)、account(ACC_S2_001/002)
-- 幂等：可重复执行
-- 口令：OPS-ADMIN = admin123，UID-000002 = demo123

INSERT INTO staff_authorization (identity_id, role_id, version) VALUES
  ('OPS-ADMIN',  'OPERATIONS', 1),
  ('UID-000002', 'RISK',       1)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO staff_account_scope (identity_id, account_id) VALUES
  ('OPS-ADMIN',  'ACC_S2_001'),
  ('OPS-ADMIN',  'ACC_S2_002'),
  ('UID-000002', 'ACC_S2_001'),
  ('UID-000002', 'ACC_S2_002')
ON DUPLICATE KEY UPDATE account_id = VALUES(account_id);

SELECT a.identity_id, a.role_id, GROUP_CONCAT(s.account_id ORDER BY s.account_id) AS scope
  FROM staff_authorization a
  LEFT JOIN staff_account_scope s ON s.identity_id = a.identity_id
 GROUP BY a.identity_id, a.role_id;
