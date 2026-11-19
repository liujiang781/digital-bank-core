SET NAMES utf8mb4;
USE payment_db;

-- 清理上次验证残留
DELETE FROM mock_channel_deposit WHERE trade_no LIKE 'VERIFY-%';
DELETE FROM account_reservation WHERE trade_no LIKE 'VERIFY-%';
DELETE FROM transaction_flow WHERE trade_no LIKE 'VERIFY-%';
DELETE FROM `transaction` WHERE trade_no LIKE 'VERIFY-%';
UPDATE account SET book_balance = 50000.00 WHERE account_id = 'ACC_S2_001';

SELECT account_id, book_balance, reserved_amount,
  (book_balance - reserved_amount) AS available_balance,
  CASE WHEN book_balance >= reserved_amount THEN 'OK' ELSE 'VIOLATION' END AS check_result
FROM account;

SELECT COUNT(*) AS table_count FROM information_schema.tables
WHERE table_schema = 'payment_db';

SELECT COUNT(*) AS fk_count FROM information_schema.referential_constraints
WHERE constraint_schema = 'payment_db';

SELECT c.identity_id, cr.username, LEFT(cr.password_hash, 7) AS hash_prefix,
  CASE WHEN cr.password_hash LIKE '$2a$%' OR cr.password_hash LIKE '$2b$%'
       THEN 'BCrypt' ELSE 'NOT-BCrypt' END AS hash_algo
FROM customer c
JOIN credential cr ON cr.customer_id = c.customer_id
WHERE cr.credential_type = 'USERNAME'
ORDER BY c.identity_id;

SELECT COUNT(*) AS login_lookup_rows
FROM customer c
JOIN credential cr ON cr.customer_id = c.customer_id
WHERE c.identity_id = 'UID-000001' AND cr.credential_type = 'USERNAME';

INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash,
  account_id, amount, currency, status, accepted_at)
VALUES ('VERIFY-DEMO-0001', 'UID-000001', 'DEPOSIT', 'REQ-DEMO-0001',
  'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789',
  'ACC_S2_001', 100.00, 'CNY', 'ACCEPTED', NOW());

INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash,
  account_id, amount, currency, status, accepted_at)
VALUES ('VERIFY-DEMO-0002', 'UID-000001', 'DEPOSIT', 'REQ-DEMO-0001',
  'deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef',
  'ACC_S2_001', 200.00, 'CNY', 'ACCEPTED', NOW());

DELETE FROM `transaction` WHERE trade_no LIKE 'VERIFY-DEMO-%';

INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash,
  account_id, amount, currency, status, accepted_at)
VALUES ('VERIFY-DEMO-0003', 'UID-000001', 'DEPOSIT', 'REQ-DEMO-NEG',
  '0000000000000000000000000000000000000000000000000000000000000000',
  'ACC_S2_001', -50.00, 'CNY', 'ACCEPTED', NOW());

DELETE FROM `transaction` WHERE trade_no LIKE 'VERIFY-DEMO-%';

-- 验收标准 3: 一次成功存款只增加一次余额、只生成对应流水
SELECT account_id, book_balance AS balance_before
FROM account WHERE account_id = 'ACC_S2_001';

INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash,
  account_id, amount, currency, status, accepted_at, completed_at)
VALUES ('VERIFY-DEPO-0001', 'UID-000001', 'DEPOSIT', 'REQ-VERIFY-DEPO',
  '1111111111111111111111111111111111111111111111111111111111111111',
  'ACC_S2_001', 100.00, 'CNY', 'SUCCESS', NOW(), NOW());

INSERT INTO transaction_flow (entry_id, trade_no, account_id, entry_role, direction,
  amount, book_balance_after)
VALUES ('FLOW-VERIFY-0001', 'VERIFY-DEPO-0001', 'ACC_S2_001', 'DEPOSIT_IN', 'CREDIT',
  100.00, 50100.00);

UPDATE account SET book_balance = book_balance + 100.00
WHERE account_id = 'ACC_S2_001';

SELECT account_id, book_balance AS balance_after,
  (book_balance - 50000.00) AS increased_by
FROM account WHERE account_id = 'ACC_S2_001';

SELECT trade_no, entry_role, direction, amount
FROM transaction_flow WHERE trade_no = 'VERIFY-DEPO-0001';

SELECT COUNT(*) AS flow_count
FROM transaction_flow WHERE trade_no = 'VERIFY-DEPO-0001';

-- 重复插入同一条流水应被 uk_transaction_flow_role 拒绝
INSERT INTO transaction_flow (entry_id, trade_no, account_id, entry_role, direction,
  amount, book_balance_after)
VALUES ('FLOW-VERIFY-0002', 'VERIFY-DEPO-0001', 'ACC_S2_001', 'DEPOSIT_IN', 'CREDIT',
  100.00, 50200.00);

-- 清理验证数据
DELETE FROM transaction_flow WHERE trade_no LIKE 'VERIFY-DEPO-%';
DELETE FROM `transaction` WHERE trade_no LIKE 'VERIFY-DEPO-%';
UPDATE account SET book_balance = 50000.00 WHERE account_id = 'ACC_S2_001';

DELETE FROM `transaction` WHERE trade_no LIKE 'VERIFY-DEMO-%';
