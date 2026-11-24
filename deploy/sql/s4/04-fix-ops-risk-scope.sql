-- 补齐 OPS-RISK 的账户数据范围
-- 原因：s4-staff-seed.sql 只写了 OPS-ADMIN / UID-000002 的范围，
--       OPS-RISK（RISK 角色）在 staff_account_scope 中为空，
--       导致风控写操作返回 403「没有此账户的风控数据权限」。
-- 幂等：可重复执行。
USE payment_db;

INSERT INTO staff_account_scope (identity_id, account_id) VALUES
  ('OPS-RISK', 'ACC_S2_001'),
  ('OPS-RISK', 'ACC_S2_002')
ON DUPLICATE KEY UPDATE account_id = VALUES(account_id);

SELECT identity_id, GROUP_CONCAT(account_id ORDER BY account_id) AS scope
  FROM staff_account_scope GROUP BY identity_id;
