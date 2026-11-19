-- Run once after S4's base schema.sql and S2's s2-incremental.sql, against the same payment_db database.
-- Additive migration: this file does not drop or re-create existing banking data.
-- 本表由 S3 写入；S2 不创建、不修改。
CREATE TABLE IF NOT EXISTS mock_channel_deposit (
    trade_no VARCHAR(32) NOT NULL COMMENT '原交易流水号(一笔交易一条模拟渠道记录)',
    channel_request_id VARCHAR(64) NOT NULL COMMENT '渠道请求标识, 固定为 CHD+trade_no, 重试不更换',
    account_id VARCHAR(32) NOT NULL COMMENT '入金账户',
    amount DECIMAL(18,2) NOT NULL COMMENT '入金金额',
    currency CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    result VARCHAR(16) NOT NULL COMMENT '渠道结果: SUCCESS/FAILED/UNKNOWN, 明确结果只能由 UNKNOWN 收敛, 终态不翻转',
    evidence_id VARCHAR(64) DEFAULT NULL COMMENT '渠道凭证号, 明确结果时必填',
    reason_code VARCHAR(64) DEFAULT NULL COMMENT 'FAILED 必填如 CHANNEL_DECLINED; UNKNOWN 可填 CHANNEL_TIMEOUT',
    observed_at DATETIME DEFAULT NULL COMMENT '取得本次结果的东八区时间',
    reconcile_attempts INT NOT NULL DEFAULT 0 COMMENT '未知结果自动核对次数(持久化, 不因扫描或重启重置)',
    next_check_at DATETIME DEFAULT NULL COMMENT '下次自动核对时间, 空表示暂停自动核对',
    manual_review_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '自动核对耗尽后转人工核对',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (trade_no),
    UNIQUE KEY uk_channel_request (channel_request_id),
    KEY idx_channel_next_check (manual_review_required, next_check_at),
    CONSTRAINT fk_channel_trade FOREIGN KEY (trade_no) REFERENCES `transaction`(trade_no),
    CONSTRAINT fk_channel_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT chk_channel_amount CHECK (amount > 0),
    CONSTRAINT chk_channel_currency CHECK (currency = 'CNY'),
    CONSTRAINT chk_channel_result CHECK (result IN ('SUCCESS', 'FAILED', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟渠道入金记录表(S3 专用)';

-- 运营人工核对/恢复重试的幂等留痕：同身份+动作+requestId 只执行一次；同键异内容报冲突。
-- 本表由 S3 写入；S4 建库时也应包含，避免新库缺少该表。
CREATE TABLE IF NOT EXISTS trade_manual_action (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    identity_id VARCHAR(64) NOT NULL COMMENT '运营身份(服务端会话核验, 非客户端头)',
    action VARCHAR(24) NOT NULL COMMENT '动作: RECONCILE/RECOVER',
    request_id VARCHAR(64) NOT NULL COMMENT '运营填写的 UUID 请求号',
    request_hash CHAR(64) NOT NULL COMMENT '请求内容 SHA-256, 同键异内容报 409',
    trade_no VARCHAR(32) NOT NULL COMMENT '关联交易流水号',
    result_json TEXT NOT NULL COMMENT '幂等结果快照(JSON)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次执行时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_manual_action (identity_id, action, request_id),
    KEY idx_manual_action_trade (trade_no, action, created_at),
    CONSTRAINT chk_manual_action CHECK (action IN ('RECONCILE', 'RECOVER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易人工操作幂等留痕表(S3 专用)';

-- 人工核对/恢复重试会把详细结论码写入 audit_log.result，原 VARCHAR(16) 不足以容纳，
-- 例如 LOCAL_OUTCOME_FAILED(20)。仅加宽字段，兼容既有 SUCCESS/FAILURE/REJECT 值。
ALTER TABLE audit_log
    MODIFY COLUMN result VARCHAR(64) DEFAULT NULL COMMENT '结果: SUCCESS/FAILURE/REJECT 或人工核对结论码';
-- 原 CHECK 列举仅允许 SUCCESS/FAILURE/REJECT，同样会拒绝结论码；放宽为长度校验。
ALTER TABLE audit_log DROP CHECK chk_audit_log_result;
ALTER TABLE audit_log ADD CONSTRAINT chk_audit_log_result CHECK (result IS NULL OR CHAR_LENGTH(result) <= 64);
