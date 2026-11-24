-- 冒烟 / 验收后把演示库复位到 init-data.sql 的基线状态。
-- 与 init-data.sql 的清理口径一致：先删子表再删 transaction（15 个外键，顺序不能反）。
-- 只清交易类数据，保留 customer / credential / account / risk_rule，所以不需要重设口令。
SET NAMES utf8mb4;
USE payment_db;

DELETE FROM mock_channel_deposit;
DELETE FROM risk_event;
DELETE FROM account_reservation;
DELETE FROM transfer_order;
DELETE FROM transaction_flow;
DELETE FROM audit_log;
DELETE FROM account_command;
DELETE FROM `transaction`;

-- 兼容两套账户编号：init-data.sql 使用 ACC_S2_001/002，当前演示库使用 ACC-0001/0002。
-- 两者都属于 CUS-0001（50000.00）和 CUS-0002（30000.00），所以一起复位。
UPDATE account SET book_balance = 50000.00, reserved_amount = 0.00, version = version + 1 WHERE account_id IN ('ACC_S2_001', 'ACC-0001');
UPDATE account SET book_balance = 30000.00, reserved_amount = 0.00, version = version + 1 WHERE account_id IN ('ACC_S2_002', 'ACC-0002');

SELECT account_id, book_balance, reserved_amount, status FROM account WHERE account_id IN ('ACC_S2_001', 'ACC_S2_002', 'ACC-0001', 'ACC-0002');
SELECT COUNT(*) AS remaining_transactions FROM `transaction`;
SELECT COUNT(*) AS remaining_flows FROM transaction_flow;
SELECT COUNT(*) AS remaining_channel_rows FROM mock_channel_deposit;
