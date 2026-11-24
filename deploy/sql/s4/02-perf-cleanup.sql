-- =====================================================================
-- S4 性能测试数据复位（写操作，执行前请先看预览段）
-- 用途：删除 perf-test.ps1 产生的演示存款，并把账户余额减回原值。
-- 用法：
--   mysql -u <user> -p --default-character-set=utf8mb4 -t < sql/02-perf-cleanup.sql
-- 判定条件（三者同时满足才删除）：
--   账户 = @account_id、业务类型 = DEPOSIT、金额 = @amount、状态 = SUCCESS、
--   创建时间落在 [@since, @until]
-- 注意：
--   1) audit_log 属于审计留痕，本脚本不删除（会保留已删交易的审计行）；
--   2) 若窗口内有真实同额存款，请先把 @since 收窄到压测开始时间。
-- =====================================================================

-- 会话排序规则与库表保持一致，避免 utf8mb4_0900_ai_ci / utf8mb4_unicode_ci 混用报 1267
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE payment_db;

SET @db         := 'payment_db';
SET @account_id := 'ACC_S2_001';
SET @amount     := 1.00;
SET @since      := CURDATE();
SET @until      := NOW();

-- ---------- 1. 预览：本次将被清理的记录 ----------
SELECT 'preview' AS stage, trade_no, amount, status, created_at
FROM payment_db.transaction
WHERE account_id = @account_id
  AND business_type = 'DEPOSIT'
  AND status = 'SUCCESS'
  AND amount = @amount
  AND created_at BETWEEN @since AND @until
ORDER BY created_at;

SELECT COUNT(*) AS rows_to_delete, COALESCE(SUM(amount), 0) AS amount_to_rollback
FROM payment_db.transaction
WHERE account_id = @account_id
  AND business_type = 'DEPOSIT'
  AND status = 'SUCCESS'
  AND amount = @amount
  AND created_at BETWEEN @since AND @until;

-- ---------- 2. 记录余额基线 ----------
SELECT account_id, book_balance AS balance_before, reserved_amount, version
FROM payment_db.account WHERE account_id = @account_id;

-- ---------- 3. 清理 ----------
START TRANSACTION;

-- 临时表显式使用库表同款排序规则，避免 utf8mb4_0900_ai_ci 与 utf8mb4_unicode_ci 比较报 1267
CREATE TEMPORARY TABLE tmp_perf_txn (
  trade_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci PRIMARY KEY
);

INSERT INTO tmp_perf_txn (trade_no)
SELECT trade_no
FROM payment_db.transaction
WHERE account_id = @account_id
  AND business_type = 'DEPOSIT'
  AND status = 'SUCCESS'
  AND amount = @amount
  AND created_at BETWEEN @since AND @until;

SET @sum := (SELECT COALESCE(SUM(amount), 0) FROM payment_db.transaction
             WHERE trade_no IN (SELECT trade_no FROM tmp_perf_txn));

DELETE FROM payment_db.mock_channel_deposit
 WHERE trade_no IN (SELECT trade_no FROM tmp_perf_txn);

DELETE FROM payment_db.transaction_flow
 WHERE trade_no IN (SELECT trade_no FROM tmp_perf_txn);

DELETE FROM payment_db.transaction
 WHERE trade_no IN (SELECT trade_no FROM tmp_perf_txn);

UPDATE payment_db.account
   SET book_balance = book_balance - @sum,
       version = version + 1
 WHERE account_id = @account_id;

COMMIT;

DROP TEMPORARY TABLE tmp_perf_txn;

-- ---------- 4. 清理后校验 ----------
SELECT 'after' AS stage, account_id, book_balance AS balance_after, reserved_amount, version
FROM payment_db.account WHERE account_id = @account_id;

SELECT COUNT(*) AS remaining_perf_rows
FROM payment_db.transaction
WHERE account_id = @account_id
  AND business_type = 'DEPOSIT'
  AND status = 'SUCCESS'
  AND amount = @amount
  AND created_at BETWEEN @since AND @until;
-- remaining_perf_rows 应为 0
