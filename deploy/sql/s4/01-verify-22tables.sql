-- =====================================================================
-- S4 结构验收（只读）
-- 用法：
--   mysql -u <user> -p --default-character-set=utf8mb4 -t < sql/01-verify-22tables.sql
-- 说明：
--   1) 只查询 information_schema 与演示数据，不做任何写操作；
--   2) 每行输出 check_name / actual / expected / verdict，verdict=FAIL 即不通过；
--   3) 期望值口径：22 表 / 17 外键 / 48 CHECK / 216 列 / 50 二级索引。
-- =====================================================================

SET @db := 'payment_db';

SELECT 'server_version' AS check_name, VERSION() AS actual, '>=8.0.16' AS expected,
       IF(VERSION() >= '8.0.16', 'PASS', 'FAIL') AS verdict;

SELECT 'table_count' AS check_name,
       (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema=@db AND table_type='BASE TABLE') AS actual,
       22 AS expected,
       IF((SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema=@db AND table_type='BASE TABLE')=22, 'PASS', 'FAIL') AS verdict;

SELECT 'fk_count' AS check_name,
       (SELECT COUNT(*) FROM information_schema.table_constraints
         WHERE constraint_schema=@db AND constraint_type='FOREIGN KEY') AS actual,
       17 AS expected,
       IF((SELECT COUNT(*) FROM information_schema.table_constraints
            WHERE constraint_schema=@db AND constraint_type='FOREIGN KEY')=17, 'PASS', 'FAIL') AS verdict;

SELECT 'check_count' AS check_name,
       (SELECT COUNT(*) FROM information_schema.table_constraints
         WHERE constraint_schema=@db AND constraint_type='CHECK') AS actual,
       48 AS expected,
       IF((SELECT COUNT(*) FROM information_schema.table_constraints
            WHERE constraint_schema=@db AND constraint_type='CHECK')=48, 'PASS', 'FAIL') AS verdict;

SELECT 'column_count' AS check_name,
       (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db) AS actual,
       216 AS expected,
       IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db)=216, 'PASS', 'FAIL') AS verdict;

SELECT 'secondary_index_count' AS check_name,
       (SELECT COUNT(DISTINCT table_name, index_name) FROM information_schema.statistics
         WHERE table_schema=@db AND index_name <> 'PRIMARY') AS actual,
       50 AS expected,
       IF((SELECT COUNT(DISTINCT table_name, index_name) FROM information_schema.statistics
            WHERE table_schema=@db AND index_name <> 'PRIMARY')=50, 'PASS', 'FAIL') AS verdict;

-- 22 张表是否齐全（缺失表列出）
SELECT m.t AS missing_table
FROM (
  SELECT 'customer' AS t UNION ALL SELECT 'credential' UNION ALL SELECT 'user_command'
  UNION ALL SELECT 'staff_authorization' UNION ALL SELECT 'staff_account_scope'
  UNION ALL SELECT 'account' UNION ALL SELECT 'account_status_log' UNION ALL SELECT 'account_command'
  UNION ALL SELECT 'account_reservation' UNION ALL SELECT 'risk_freeze_release'
  UNION ALL SELECT 'transaction' UNION ALL SELECT 'transaction_flow' UNION ALL SELECT 'transfer_order'
  UNION ALL SELECT 'mock_channel_deposit' UNION ALL SELECT 'trade_manual_action'
  UNION ALL SELECT 'risk_rule' UNION ALL SELECT 'risk_event' UNION ALL SELECT 'risk_alert_disposal'
  UNION ALL SELECT 'risk_change_request' UNION ALL SELECT 'risk_change_approval'
  UNION ALL SELECT 'risk_list_entry' UNION ALL SELECT 'audit_log'
) m
LEFT JOIN information_schema.tables t
       ON t.table_schema=@db AND t.table_name=m.t
WHERE t.table_name IS NULL;
-- 上面查询无行 = 22 张表齐全

-- 多余表（不在 S4 口径内的表）
SELECT t.table_name AS unexpected_table
FROM information_schema.tables t
WHERE t.table_schema=@db AND t.table_type='BASE TABLE'
  AND t.table_name NOT IN (
    'customer','credential','user_command','staff_authorization','staff_account_scope',
    'account','account_status_log','account_command','account_reservation','risk_freeze_release',
    'transaction','transaction_flow','transfer_order','mock_channel_deposit','trade_manual_action',
    'risk_rule','risk_event','risk_alert_disposal','risk_change_request','risk_change_approval',
    'risk_list_entry','audit_log');

-- risk_freeze_release 关键结构（22 表相对 21 表的唯一增量）
SELECT 'risk_freeze_release.columns' AS check_name,
       GROUP_CONCAT(column_name ORDER BY ordinal_position) AS actual,
       'restriction_id,approved_by,basis,approved_at' AS expected,
       IF(GROUP_CONCAT(column_name ORDER BY ordinal_position)='restriction_id,approved_by,basis,approved_at',
          'PASS','FAIL') AS verdict
FROM information_schema.columns
WHERE table_schema=@db AND table_name='risk_freeze_release';

SELECT 'risk_freeze_release.fk' AS check_name,
       GROUP_CONCAT(k.constraint_name) AS actual,
       'fk_risk_freeze_release_restriction' AS expected,
       IF(GROUP_CONCAT(k.constraint_name)='fk_risk_freeze_release_restriction','PASS','FAIL') AS verdict
FROM information_schema.table_constraints k
WHERE k.constraint_schema=@db AND k.table_name='risk_freeze_release' AND k.constraint_type='FOREIGN KEY';

-- 幂等键是否就位（identity_id + business_type + request_id 唯一）
SELECT 'uk_transaction_request' AS check_name,
       COUNT(DISTINCT index_name) AS actual, '1' AS expected,
       IF(COUNT(DISTINCT index_name)=1,'PASS','FAIL') AS verdict
FROM information_schema.statistics
WHERE table_schema=@db AND table_name='transaction' AND index_name='uk_transaction_request';

-- 演示数据基线（init-data.sql 4 客户 + sql/05 补种的 RISK-APPROVER = 5 客户 / 2 账户）
SELECT 'demo_customer_count' AS check_name, COUNT(*) AS actual, 5 AS expected,
       IF(COUNT(*)=5,'PASS','WARN') AS verdict
FROM payment_db.customer;

SELECT 'demo_account_count' AS check_name, COUNT(*) AS actual, 2 AS expected,
       IF(COUNT(*)=2,'PASS','WARN') AS verdict
FROM payment_db.account;
