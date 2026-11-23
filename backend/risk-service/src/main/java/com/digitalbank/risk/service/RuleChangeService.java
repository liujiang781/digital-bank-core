package com.digitalbank.risk.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.risk.dto.RiskDtos.ApprovalRequest;
import com.digitalbank.risk.dto.RiskDtos.ChangePage;
import com.digitalbank.risk.dto.RiskDtos.RuleChangeRequest;
import com.digitalbank.risk.dto.RiskDtos.RuleChangeView;
import com.digitalbank.risk.dto.RiskDtos.RuleView;
import com.digitalbank.risk.security.RiskAccess;
import com.digitalbank.risk.support.ChangeIds;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 风控规则维护（UC-RK-01）：提交变更生成审批单，审批通过后应用并 ruleVersion+1。
 * 提交与审批由不同风控专员执行（职责分离），全过程写审计。账户状态规则不允许关闭（BR-05）。
 */
@Service
public class RuleChangeService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> DECISIONS = Set.of("APPROVE", "REJECT");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RiskAccess access;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public RuleChangeService(JdbcTemplate jdbc, TransactionTemplate transactions, RiskAccess access,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.access = access;
        this.mapper = mapper;
    }

    public List<RuleView> rules() {
        return jdbc.query(
                "SELECT rule_id, rule_type, deposit_single_limit, outgoing_single_limit, outgoing_daily_limit, "
                        + "enabled, rule_version, updated_at FROM risk_rule ORDER BY rule_id",
                (rs, index) -> new RuleView(rs.getString("rule_id"), rs.getString("rule_type"),
                        decimal(rs.getBigDecimal("deposit_single_limit")),
                        decimal(rs.getBigDecimal("outgoing_single_limit")),
                        decimal(rs.getBigDecimal("outgoing_daily_limit")),
                        rs.getBoolean("enabled"), rs.getInt("rule_version"),
                        format(rs.getObject("updated_at", LocalDateTime.class))));
    }

    /** 提交规则变更：落 PENDING 审批单，未审批前不改变 risk_rule。 */
    public RuleChangeView submit(SessionIdentity identity, String ruleId,
            RuleChangeRequest body, String traceId) {
        if (body == null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少变更内容");
        String reason = requireReason(body.reason());
        RuleRow rule = jdbc.query(
                "SELECT rule_id, rule_type, deposit_single_limit, outgoing_single_limit, outgoing_daily_limit, "
                        + "enabled, rule_version FROM risk_rule WHERE rule_id = ?",
                (rs, index) -> new RuleRow(rs.getString("rule_id"), rs.getString("rule_type"),
                        rs.getBigDecimal("deposit_single_limit"), rs.getBigDecimal("outgoing_single_limit"),
                        rs.getBigDecimal("outgoing_daily_limit"), rs.getBoolean("enabled"),
                        rs.getInt("rule_version")), ruleId).stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_ARGUMENT, "规则不存在"));
        if (body.expectedVersion() != null && body.expectedVersion() != rule.ruleVersion()) {
            throw new BizException(ErrorCode.REQUEST_CONFLICT, "规则版本已变化，请刷新后重试");
        }
        String deposit = body.depositSingleLimit() == null ? decimal(rule.deposit()) : positive(body.depositSingleLimit(), "depositSingleLimit");
        String outgoing = body.outgoingSingleLimit() == null ? decimal(rule.outgoing()) : positive(body.outgoingSingleLimit(), "outgoingSingleLimit");
        String daily = body.outgoingDailyLimit() == null ? decimal(rule.daily()) : positive(body.outgoingDailyLimit(), "outgoingDailyLimit");
        boolean enabled = body.enabled() == null ? rule.enabled() : body.enabled();
        if (!"LIMIT_POLICY".equals(rule.ruleType()) && (deposit != null || outgoing != null || daily != null)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "此规则不接受金额限额参数");
        }
        if ("LIMIT_POLICY".equals(rule.ruleType()) && (deposit == null || outgoing == null || daily == null)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "限额策略必须提供三项限额值");
        }
        if (outgoing != null && daily != null
                && new BigDecimal(outgoing).compareTo(new BigDecimal(daily)) > 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "出账单笔限额不能大于日累计限额");
        }
        String changeJson = ChangeIds.json(new RulePatch(ruleId, rule.ruleType(), deposit, outgoing, daily, enabled));
        String requestId = ChangeIds.uuid(body.requestId());
        String hash = ChangeIds.ruleHash(ruleId, changeJson, reason);
        String changeId = changeId("RULE", requestId, identity.identityId());

        boolean created = transactions.execute(status -> {
            List<String> hashes = jdbc.query(
                    "SELECT request_hash FROM risk_change_request WHERE change_type = 'RULE' "
                            + "AND submitted_by = ? AND request_id = ?",
                    (rs, i) -> rs.getString(1), identity.identityId(), requestId);
            if (!hashes.isEmpty()) {
                if (!hash.equals(hashes.getFirst())) {
                    throw new BizException(ErrorCode.REQUEST_CONFLICT, "同一请求号的内容与原提交不一致");
                }
                return false;
            }
            jdbc.update("INSERT INTO risk_change_request (change_id, change_type, target_ref, request_json, "
                            + "status, submitted_by, submit_reason, request_id, request_hash) "
                            + "VALUES (?, 'RULE', ?, ?, 'PENDING', ?, ?, ?, ?)",
                    changeId, ruleId, changeJson, identity.identityId(), reason, requestId, hash);
            return true;
        });
        if (created) {
            audit(identity.identityId(), "RULE_CHANGE_SUBMIT", "risk_rule", ruleId, reason, "PENDING");
        }
        return findChange(changeId);
    }

    /** 查询审批单列表：仅风控专员，支持按状态过滤。 */
    public ChangePage changes(SessionIdentity identity, String status, int page, int pageSize) {
        if (page < 1) throw new BizException(ErrorCode.INVALID_ARGUMENT, "page 从 1 开始");
        if (pageSize < 1 || pageSize > 100) throw new BizException(ErrorCode.INVALID_ARGUMENT, "pageSize 取值 1-100");
        if (status != null && !status.isBlank() && !Set.of("PENDING", "APPROVED", "REJECTED", "APPLIED").contains(status)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "status 不是合法的审批状态");
        }
        String where = status == null || status.isBlank() ? "" : " WHERE status = ?";
        List<Object> args = new ArrayList<>();
        if (!where.isEmpty()) args.add(status);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM risk_change_request" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (page - 1) * pageSize);
        List<RuleChangeView> items = jdbc.query(
                "SELECT change_id FROM risk_change_request" + where
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, index) -> findChange(rs.getString("change_id")), pageArgs.toArray());
        return new ChangePage(total == null ? 0 : total, page, pageSize, items);
    }

    /**
     * 审批：APPROVE 应用变更并将状态置 APPLIED；REJECT 仅记录意见。审批人不得为提交人。
     * 应用与状态更新在同一本地事务，避免"已审批未生效"或"已生效未审批"。
     */
    public RuleChangeView approve(SessionIdentity identity, String changeId,
            ApprovalRequest body, String traceId) {
        if (body == null || !DECISIONS.contains(body.decision())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "decision 仅支持 APPROVE/REJECT");
        }
        String note = body.note() == null ? "" : body.note().trim();
        if (note.length() > 255) throw new BizException(ErrorCode.INVALID_ARGUMENT, "审批意见不能超过255字符");
        String requestId = ChangeIds.uuid(body.requestId());
        String hash = ChangeIds.approveHash(changeId, body.decision(), note);
        ChangeRow change = findChangeRow(changeId);
        if (!"PENDING".equals(change.status())) {
            throw new BizException(ErrorCode.REQUEST_CONFLICT, "审批单已处理，不能重复审批");
        }
        access.requireDifferentApprover(identity, change.submittedBy());
        String ruleType = jdbc.queryForList("SELECT rule_type FROM risk_rule WHERE rule_id = ?", String.class,
                change.targetRef()).stream().findFirst().orElse("LIMIT_POLICY");

        transactions.executeWithoutResult(status -> {
            // 同键同内容重放返回原结果；同键异内容报冲突。
            List<String> hashes = jdbc.query(
                    "SELECT request_hash FROM risk_change_approval WHERE change_id = ? AND identity_id = ? "
                            + "AND request_id = ?",
                    (rs, i) -> rs.getString(1), changeId, identity.identityId(), requestId);
            if (!hashes.isEmpty()) {
                if (!hash.equals(hashes.getFirst())) {
                    throw new BizException(ErrorCode.REQUEST_CONFLICT, "同一请求号的内容与原审批不一致");
                }
                return;
            }
            int updated = jdbc.update("UPDATE risk_change_request SET status = ?, approved_by = ?, "
                            + "approval_note = ?, decided_at = ? WHERE change_id = ? AND status = 'PENDING'",
                    "APPROVE".equals(body.decision()) ? "APPLIED" : "REJECTED", identity.identityId(),
                    note.isEmpty() ? null : note, LocalDateTime.now(ZONE), changeId);
            if (updated == 0) {
                throw new BizException(ErrorCode.REQUEST_CONFLICT, "审批单已处理，不能重复审批");
            }
            jdbc.update("INSERT INTO risk_change_approval (change_id, identity_id, decision, note, "
                            + "request_id, request_hash) VALUES (?, ?, ?, ?, ?, ?)",
                    changeId, identity.identityId(), body.decision(), note.isEmpty() ? null : note,
                    requestId, hash);
            if ("APPROVE".equals(body.decision())) {
                applyRule(change, ruleType);
            }
            audit(identity.identityId(), "RULE_CHANGE_APPROVE", "risk_change_request", changeId, note,
                    "APPROVE".equals(body.decision()) ? "APPLIED" : "REJECTED");
        });
        return findChange(changeId);
    }

    private void applyRule(ChangeRow change, String ruleType) {
        RulePatch patch;
        try {
            patch = mapper.readValue(change.requestJson(), RulePatch.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "无法读取规则变更内容");
        }
        if (patch.deposit() != null && new BigDecimal(patch.deposit()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "限额必须大于 0");
        }
        if (patch.outgoing() != null && new BigDecimal(patch.outgoing()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "限额必须大于 0");
        }
        if (patch.daily() != null && new BigDecimal(patch.daily()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "限额必须大于 0");
        }
        if ("ACCOUNT_STATUS".equals(ruleType) && !patch.enabled()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "账户状态规则不允许关闭（BR-05）");
        }
        jdbc.update("UPDATE risk_rule SET deposit_single_limit = ?, outgoing_single_limit = ?, "
                        + "outgoing_daily_limit = ?, enabled = ?, rule_version = rule_version + 1 "
                        + "WHERE rule_id = ?",
                decimalToDb(patch.deposit()), decimalToDb(patch.outgoing()), decimalToDb(patch.daily()),
                patch.enabled() ? 1 : 0, patch.ruleId());
    }

    private RuleChangeView findChange(String changeId) {
        ChangeRow row = findChangeRow(changeId);
        return new RuleChangeView(row.changeId(), row.targetRef(), ruleType(row.targetRef()),
                row.requestJson(), row.status(), row.submittedBy(), row.submitReason(), row.requestId(),
                row.approvedBy(), row.approvalNote(), format(row.createdAt()), format(row.decidedAt()));
    }

    /** 供审批入口判断变更单类型（RULE/LIST）。 */
    public String changeType(String changeId) {
        List<String> types = jdbc.queryForList(
                "SELECT change_type FROM risk_change_request WHERE change_id = ?", String.class, changeId);
        if (types.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "审批单不存在");
        }
        return types.getFirst();
    }

    private ChangeRow findChangeRow(String changeId) {
        return jdbc.query(
                "SELECT change_id, change_type, target_ref, request_json, status, submitted_by, "
                        + "submit_reason, request_id, approved_by, approval_note, created_at, decided_at "
                        + "FROM risk_change_request WHERE change_id = ?",
                (rs, index) -> new ChangeRow(rs.getString("change_id"), rs.getString("change_type"),
                        rs.getString("target_ref"), rs.getString("request_json"), rs.getString("status"),
                        rs.getString("submitted_by"), rs.getString("submit_reason"), rs.getString("request_id"),
                        rs.getString("approved_by"), rs.getString("approval_note"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("decided_at", LocalDateTime.class)), changeId).stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_ARGUMENT, "审批单不存在"));
    }

    private String ruleType(String ruleId) {
        return jdbc.queryForList("SELECT rule_type FROM risk_rule WHERE rule_id = ?", String.class, ruleId)
                .stream().findFirst().orElse("LIMIT_POLICY");
    }

    private String changeId(String type, String requestId, String identityId) {
        return "CHG-" + type + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private void audit(String operator, String action, String targetType, String targetRef,
            String reason, String result) {
        jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, result) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                operator, action, targetType, targetRef,
                reason == null ? null : (reason.length() > 255 ? reason.substring(0, 255) : reason), result);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 255) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请填写提交原因（不超过255字符）");
        }
        return reason.trim();
    }

    private static String positive(String value, String field) {
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException();
            return amount.setScale(2).toPlainString();
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, field + " 必须大于 0");
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static BigDecimal decimalToDb(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static String format(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime().toString();
    }

    private record RuleRow(String ruleId, String ruleType, BigDecimal deposit, BigDecimal outgoing,
            BigDecimal daily, boolean enabled, int ruleVersion) { }

    private record RulePatch(String ruleId, String ruleType, String deposit, String outgoing,
            String daily, boolean enabled) { }

    private record ChangeRow(String changeId, String changeType, String targetRef, String requestJson,
            String status, String submittedBy, String submitReason, String requestId, String approvedBy,
            String approvalNote, LocalDateTime createdAt, LocalDateTime decidedAt) { }
}
