-- 清理 e2e 测试残留（仅删除测试标记的数据，不动业务演示数据）
-- 用途：e2e-full.ps1 每次运行前后执行，保证名单类用例可重复。
-- 背景：applyList 的 REMOVE 按“本次新生成的 entry_id”删除，无法删除已存在的同名条目（DEF-S4-006），
--       残留条目会使下一轮 UPSERT 触发 uk_list_subject 唯一键冲突（500）。
-- 幂等：可重复执行。
USE payment_db;

DELETE a FROM risk_change_approval a
  JOIN risk_change_request r ON a.change_id = r.change_id
 WHERE r.change_type = 'LIST' OR r.submit_reason LIKE 'e2e%';

DELETE FROM risk_change_request
 WHERE change_type = 'LIST' OR submit_reason LIKE 'e2e%';

DELETE FROM risk_list_entry WHERE reason_code = 'E2E_TEST';

SELECT (SELECT COUNT(*) FROM risk_list_entry WHERE reason_code='E2E_TEST') AS list_residue,
       (SELECT COUNT(*) FROM risk_change_request) AS changes_left;
