-- =====================================================================
-- payment_db 表级授权（只 GRANT，不改口令、不写仓库文件）
-- 来源：deploy/sql/exp4-grant.sh 第 38-96 行，逐行照抄
-- 与 exp4-grant.sh 的区别：
--   1) 不含 CREATE USER / ALTER USER，不重置服务账号口令；
--   2) 不生成 deploy/.env.local；
--   3) 只对已存在的账号补授权，可重复执行。
-- 用法：
--   mysql -u root -p --default-character-set=utf8mb4 < sql/03-grant-payment-db.sql
-- 前置：账号 bank_user / account_service / transaction_service / risk_service /
--       s2_readonly / s2_exp4_test 已按 deploy/.env.local 建好。
-- =====================================================================

USE payment_db;

-- bank_user
GRANT SELECT, INSERT, UPDATE ON payment_db.customer TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.credential TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.user_command TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_db.staff_authorization TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_db.staff_account_scope TO 'bank_user'@'%';
GRANT SELECT, INSERT ON payment_db.audit_log TO 'bank_user'@'%';
GRANT SELECT ON payment_db.account TO 'bank_user'@'%';

-- account_service
GRANT SELECT, INSERT, UPDATE ON payment_db.account TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.account_command TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.account_reservation TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.transaction_flow TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.`transaction` TO 'account_service'@'%';
GRANT SELECT, INSERT ON payment_db.account_status_log TO 'account_service'@'%';
GRANT SELECT, INSERT ON payment_db.audit_log TO 'account_service'@'%';
GRANT SELECT ON payment_db.customer TO 'account_service'@'%';
GRANT SELECT, UPDATE ON payment_db.transfer_order TO 'account_service'@'%';
GRANT SELECT ON payment_db.risk_rule TO 'account_service'@'%';
GRANT SELECT ON payment_db.risk_event TO 'account_service'@'%';
GRANT SELECT ON payment_db.risk_freeze_release TO 'account_service'@'%';
GRANT LOCK TABLES ON payment_db.* TO 'account_service'@'%';

-- transaction_service
GRANT SELECT, INSERT, UPDATE ON payment_db.`transaction` TO 'transaction_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.mock_channel_deposit TO 'transaction_service'@'%';
GRANT SELECT, INSERT ON payment_db.transfer_order TO 'transaction_service'@'%';
GRANT SELECT ON payment_db.account TO 'transaction_service'@'%';
GRANT SELECT ON payment_db.account_reservation TO 'transaction_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.trade_manual_action TO 'transaction_service'@'%';
GRANT SELECT, INSERT ON payment_db.audit_log TO 'transaction_service'@'%';

-- risk_service
GRANT SELECT, INSERT, UPDATE ON payment_db.risk_rule TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_event TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_alert_disposal TO 'risk_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.risk_change_request TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_change_approval TO 'risk_service'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_db.risk_list_entry TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.audit_log TO 'risk_service'@'%';
GRANT SELECT ON payment_db.account TO 'risk_service'@'%';
GRANT SELECT ON payment_db.account_status_log TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_freeze_release TO 'risk_service'@'%';
GRANT INSERT ON payment_db.audit_log TO 'risk_service'@'%';
GRANT SELECT ON payment_db.`transaction` TO 'risk_service'@'%';

-- s2_readonly
GRANT SELECT ON payment_db.account TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.account_reservation TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.transaction_flow TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.`transaction` TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.customer TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.risk_rule TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.risk_event TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.account_status_log TO 's2_readonly'@'%';

-- s2_exp4_test（隔离库测试账号）
GRANT ALL PRIVILEGES ON s2_exp4_test.* TO 's2_exp4_test'@'%';

FLUSH PRIVILEGES;
