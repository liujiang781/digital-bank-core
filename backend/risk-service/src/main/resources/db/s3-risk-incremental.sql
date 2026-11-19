-- Run once against payment_db after base schema.sql.
-- S3 P2/additional features: alert disposal, rule change approval, risk list maintenance.
-- Additive only: no existing table or data is dropped.

-- 告警处置幂等留痕：同一事件 + 同一 requestId 只处置一次；同键异内容报 409。
CREATE TABLE IF NOT EXISTS risk_alert_disposal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    event_id VARCHAR(32) NOT NULL COMMENT '风险事件标识',
    identity_id VARCHAR(64) NOT NULL COMMENT '处置人身份(服务端会话)',
    decision VARCHAR(24) NOT NULL COMMENT '处置结论: CONFIRMED/FALSE_POSITIVE/ESCALATED',
    basis VARCHAR(1000) NOT NULL COMMENT '处置依据',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号(UUID)',
    request_hash CHAR(64) NOT NULL COMMENT '请求内容摘要, 同键异内容报冲突',
    trade_no VARCHAR(32) NOT NULL COMMENT '关联交易流水号',
    trace_id VARCHAR(64) DEFAULT NULL COMMENT '链路追踪号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '处置时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_disposal (event_id, identity_id, request_id),
    KEY idx_alert_disposal_event (event_id),
    CONSTRAINT chk_alert_decision CHECK (decision IN ('CONFIRMED', 'FALSE_POSITIVE', 'ESCALATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风险告警处置留痕表(S3 专用)';

-- 规则与名单变更审批单：提交与审批分离，审批人不得为提交人。
CREATE TABLE IF NOT EXISTS risk_change_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    change_id VARCHAR(64) NOT NULL COMMENT '变更单号(全局唯一)',
    change_type VARCHAR(24) NOT NULL COMMENT '变更类型: RULE/LIST',
    target_ref VARCHAR(64) NOT NULL COMMENT '规则ID或名单条目ID',
    request_json TEXT NOT NULL COMMENT '拟变更内容快照',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED/APPLIED',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人身份',
    submit_reason VARCHAR(255) DEFAULT NULL COMMENT '提交原因',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号(UUID)',
    request_hash CHAR(64) NOT NULL COMMENT '请求内容摘要',
    approved_by VARCHAR(64) DEFAULT NULL COMMENT '审批人身份',
    approval_note VARCHAR(255) DEFAULT NULL COMMENT '审批意见',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    decided_at DATETIME DEFAULT NULL COMMENT '审批时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_change_request (change_type, submitted_by, request_id),
    UNIQUE KEY uk_change_id (change_id),
    KEY idx_change_status (status, created_at),
    CONSTRAINT chk_change_type CHECK (change_type IN ('RULE', 'LIST')),
    CONSTRAINT chk_change_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'APPLIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风控变更审批单表(S3 专用)';

-- 审批留痕：审批请求号独立幂等，避免覆盖变更单自身的提交指纹。
CREATE TABLE IF NOT EXISTS risk_change_approval (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    change_id VARCHAR(64) NOT NULL COMMENT '审批单号',
    identity_id VARCHAR(64) NOT NULL COMMENT '审批人身份',
    decision VARCHAR(16) NOT NULL COMMENT '审批结论: APPROVE/REJECT',
    note VARCHAR(255) DEFAULT NULL COMMENT '审批意见',
    request_id VARCHAR(64) NOT NULL COMMENT '审批幂等请求号(UUID)',
    request_hash CHAR(64) NOT NULL COMMENT '审批内容摘要',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审批时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_change_approval (change_id, identity_id, request_id),
    KEY idx_approval_change (change_id),
    CONSTRAINT chk_approval_decision CHECK (decision IN ('APPROVE', 'REJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风控审批留痕表(S3 专用)';

-- 风险名单：账户/客户维度的黑白名单，变更需经审批单生效。
CREATE TABLE IF NOT EXISTS risk_list_entry (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    entry_id VARCHAR(64) NOT NULL COMMENT '名单条目ID(全局唯一)',
    list_type VARCHAR(16) NOT NULL COMMENT '名单类型: BLACKLIST/WHITELIST',
    subject_type VARCHAR(16) NOT NULL COMMENT '对象类型: ACCOUNT/CUSTOMER',
    subject_id VARCHAR(64) NOT NULL COMMENT '对象标识',
    reason_code VARCHAR(32) DEFAULT NULL COMMENT '登记原因码',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否生效: 0=否, 1=是',
    rule_version INT NOT NULL DEFAULT 1 COMMENT '条目版本',
    change_id VARCHAR(64) DEFAULT NULL COMMENT '最近一次生效的变更单号',
    created_by VARCHAR(64) NOT NULL COMMENT '创建人身份',
    approved_by VARCHAR(64) DEFAULT NULL COMMENT '审批人身份(RiskListService.list 查询需要)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_list_entry (entry_id),
    UNIQUE KEY uk_list_subject (list_type, subject_type, subject_id),
    KEY idx_list_subject (subject_type, subject_id),
    CONSTRAINT chk_list_type CHECK (list_type IN ('BLACKLIST', 'WHITELIST')),
    CONSTRAINT chk_list_subject_type CHECK (subject_type IN ('ACCOUNT', 'CUSTOMER')),
    CONSTRAINT chk_list_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风险名单条目表(S3 专用)';

-- audit_log.result 已由 s3-incremental.sql 加宽并放宽 CHECK；风控审批/处置写入的结论码同样受益。
