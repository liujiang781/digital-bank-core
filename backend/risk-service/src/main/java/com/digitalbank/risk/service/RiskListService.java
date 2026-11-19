package com.digitalbank.risk.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.risk.dto.RiskDtos.ApprovalRequest;
import com.digitalbank.risk.dto.RiskDtos.ListChangeRequest;
import com.digitalbank.risk.dto.RiskDtos.ListEntryView;
import com.digitalbank.risk.dto.RiskDtos.ListPage;
import com.digitalbank.risk.security.RiskAccess;
import com.digitalbank.risk.support.ChangeIds;
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
 * 风险名单维护（UC-RK-02，待评审项）：提交变更 → 审批 → 生效。
 * 名单按账户范围查询与提交，审批与提交分离；ACCOUNT 类型对象必须在会话账户范围内。
 */
@Service
public class RiskListService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> LIST_TYPES = Set.of("BLACKLIST", "WHITELIST");
    private static final Set<String> SUBJECT_TYPES = Set.of("ACCOUNT", "CUSTOMER");
    private static final Set<String> OPS = Set.of("UPSERT", "REMOVE");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RiskAccess access;

    public RiskListService(JdbcTemplate jdbc, TransactionTemplate transactions, RiskAccess access) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.access = access;
    }

    /** 名单查询：仅返回会话账户范围内的 ACCOUNT 对象；CUSTOMER 对象需要其关联账户在范围内。 */
    public ListPage list(SessionIdentity identity, String listType, String subjectType,
            int page, int pageSize) {
        if (page < 1) throw new BizException(ErrorCode.INVALID_ARGUMENT, "page 从 1 开始");
        if (pageSize < 1 || pageSize > 100) throw new BizException(ErrorCode.INVALID_ARGUMENT, "pageSize 取值 1-100");
        if (listType != null && !listType.isBlank() && !LIST_TYPES.contains(listType)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "listType 仅支持 BLACKLIST/WHITELIST");
        }
        List<Object> args = new ArrayList<>(identity.accountScope());
        StringBuilder where = new StringBuilder(" WHERE subject_type = 'ACCOUNT' AND subject_id IN ("
                + String.join(",", java.util.Collections.nCopies(identity.accountScope().size(), "?")) + ")");
        if (listType != null && !listType.isBlank()) {
            where.append(" AND list_type = ?");
            args.add(listType);
        }
        if (subjectType != null && !subjectType.isBlank()) {
            if (!SUBJECT_TYPES.contains(subjectType)) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "subjectType 仅支持 ACCOUNT/CUSTOMER");
            }
            if ("CUSTOMER".equals(subjectType)) return new ListPage(0, page, pageSize, List.of());
            where.append(" AND subject_type = ?");
            args.add(subjectType);
        }
        String from = " FROM risk_list_entry" + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (page - 1) * pageSize);
        List<ListEntryView> items = jdbc.query(
                "SELECT entry_id, list_type, subject_type, subject_id, reason_code, enabled, rule_version, "
                        + "created_by, approved_by, created_at, updated_at" + from
                        + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, index) -> new ListEntryView(rs.getString("entry_id"), rs.getString("list_type"),
                        rs.getString("subject_type"), rs.getString("subject_id"), rs.getString("reason_code"),
                        rs.getBoolean("enabled"), rs.getInt("rule_version"), rs.getString("created_by"),
                        rs.getString("approved_by"), format(rs.getObject("created_at", LocalDateTime.class)),
                        format(rs.getObject("updated_at", LocalDateTime.class))), pageArgs.toArray());
        return new ListPage(total == null ? 0 : total, page, pageSize, items);
    }

    /** 提交名单变更：落 PENDING 审批单，未审批前不改变名单。 */
    public ListEntryView submit(SessionIdentity identity, ListChangeRequest body, String traceId) {
        if (body == null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少变更内容");
        if (!LIST_TYPES.contains(body.listType()) || !SUBJECT_TYPES.contains(body.subjectType())
                || !OPS.contains(body.op())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "名单类型/对象类型/操作不合法");
        }
        if (body.subjectId() == null || body.subjectId().isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请填写对象标识");
        }
        String reason = body.reason();
        if (reason == null || reason.isBlank() || reason.trim().length() > 255) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请填写提交原因（不超过255字符）");
        }
        if ("ACCOUNT".equals(body.subjectType())) {
            access.requireScope(identity, body.subjectId());
        }
        boolean enabled = body.enabled() == null || body.enabled();
        String entryId = "RLE-" + body.listType() + "-" + body.subjectType() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        ListPatch patch = new ListPatch(entryId, body.listType(), body.subjectType(), body.subjectId(),
                body.reasonCode(), enabled, body.op());
        String changeJson = ChangeIds.json(patch);
        String requestId = ChangeIds.uuid(body.requestId());
        String hash = ChangeIds.listHash(body.listType(), body.subjectType(), body.subjectId(),
                body.op(), reason);
        String changeId = "CHG-LIST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        transactions.executeWithoutResult(status -> {
            List<String> hashes = jdbc.query(
                    "SELECT request_hash FROM risk_change_request WHERE change_type = 'LIST' "
                            + "AND submitted_by = ? AND request_id = ?",
                    (rs, i) -> rs.getString(1), identity.identityId(), requestId);
            if (!hashes.isEmpty()) {
                if (!hash.equals(hashes.getFirst())) {
                    throw new BizException(ErrorCode.REQUEST_CONFLICT, "同一请求号的内容与原提交不一致");
                }
                return;
            }
            jdbc.update("INSERT INTO risk_change_request (change_id, change_type, target_ref, request_json, "
                            + "status, submitted_by, submit_reason, request_id, request_hash) "
                            + "VALUES (?, 'LIST', ?, ?, 'PENDING', ?, ?, ?, ?)",
                    changeId, entryId, changeJson, identity.identityId(), reason.trim(), requestId, hash);
            audit(identity.identityId(), "RISK_LIST_SUBMIT", "risk_list_entry", entryId, reason, "PENDING");
        });
        return new ListEntryView(entryId, body.listType(), body.subjectType(), body.subjectId(),
                body.reasonCode(), enabled, 1, identity.identityId(), null,
                format(LocalDateTime.now(ZONE)), null);
    }

    /** 审批名单变更单：审批通过后应用 UPSERT/REMOVE，审批人不得为提交人。 */
    public ListEntryView approve(SessionIdentity identity, String changeId,
            ApprovalRequest body, String traceId) {
        if (body == null || !Set.of("APPROVE", "REJECT").contains(body.decision())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "decision 仅支持 APPROVE/REJECT");
        }
        String note = body.note() == null ? "" : body.note().trim();
        if (note.length() > 255) throw new BizException(ErrorCode.INVALID_ARGUMENT, "审批意见不能超过255字符");
        String requestId = ChangeIds.uuid(body.requestId());
        String hash = ChangeIds.approveHash(changeId, body.decision(), note);
        ChangeRow change = jdbc.query(
                "SELECT change_id, target_ref, request_json, status, submitted_by FROM risk_change_request "
                        + "WHERE change_id = ? AND change_type = 'LIST'",
                (rs, index) -> new ChangeRow(rs.getString("change_id"), rs.getString("target_ref"),
                        rs.getString("request_json"), rs.getString("status"), rs.getString("submitted_by")),
                changeId).stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_ARGUMENT, "名单审批单不存在"));
        if (!"PENDING".equals(change.status())) {
            throw new BizException(ErrorCode.REQUEST_CONFLICT, "审批单已处理，不能重复审批");
        }
        access.requireDifferentApprover(identity, change.submittedBy());
        ListPatch patch;
        try {
            patch = new com.fasterxml.jackson.databind.ObjectMapper().readValue(change.requestJson(), ListPatch.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "无法读取名单变更内容");
        }
        if ("ACCOUNT".equals(patch.subjectType())) access.requireScope(identity, patch.subjectId());

        final ListPatch finalPatch = patch;
        transactions.executeWithoutResult(status -> {
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
            if (updated == 0) throw new BizException(ErrorCode.REQUEST_CONFLICT, "审批单已处理，不能重复审批");
            jdbc.update("INSERT INTO risk_change_approval (change_id, identity_id, decision, note, "
                            + "request_id, request_hash) VALUES (?, ?, ?, ?, ?, ?)",
                    changeId, identity.identityId(), body.decision(), note.isEmpty() ? null : note,
                    requestId, hash);
            if ("APPROVE".equals(body.decision())) applyList(finalPatch, changeId, identity.identityId());
            audit(identity.identityId(), "RISK_LIST_APPROVE", "risk_change_request", changeId, note,
                    "APPROVE".equals(body.decision()) ? "APPLIED" : "REJECTED");
        });
        Integer version = jdbc.query("SELECT rule_version FROM risk_list_entry WHERE entry_id = ?",
                (rs, i) -> rs.getInt(1), patch.entryId()).stream().findFirst().orElse(null);
        return new ListEntryView(patch.entryId(), patch.listType(), patch.subjectType(), patch.subjectId(),
                patch.reasonCode(), patch.enabled(), version == null ? 0 : version, identity.identityId(),
                "APPROVE".equals(body.decision()) ? identity.identityId() : null,
                null, format(LocalDateTime.now(ZONE)));
    }

    private void applyList(ListPatch patch, String changeId, String approver) {
        if ("REMOVE".equals(patch.op())) {
            jdbc.update("DELETE FROM risk_list_entry WHERE entry_id = ?", patch.entryId());
            return;
        }
        Integer version = jdbc.query("SELECT rule_version FROM risk_list_entry WHERE entry_id = ?",
                (rs, i) -> rs.getInt(1), patch.entryId()).stream().findFirst().orElse(null);
        if (version == null) {
            jdbc.update("INSERT INTO risk_list_entry (entry_id, list_type, subject_type, subject_id, reason_code, "
                            + "enabled, rule_version, change_id, created_by, approved_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)",
                    patch.entryId(), patch.listType(), patch.subjectType(), patch.subjectId(), patch.reasonCode(),
                    patch.enabled() ? 1 : 0, changeId, approver, approver);
        } else {
            jdbc.update("UPDATE risk_list_entry SET list_type = ?, subject_type = ?, subject_id = ?, "
                            + "reason_code = ?, enabled = ?, rule_version = rule_version + 1, change_id = ?, "
                            + "approved_by = ? WHERE entry_id = ?",
                    patch.listType(), patch.subjectType(), patch.subjectId(), patch.reasonCode(),
                    patch.enabled() ? 1 : 0, changeId, approver, patch.entryId());
        }
    }

    private void audit(String operator, String action, String targetType, String targetRef,
            String reason, String result) {
        jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, result) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                operator, action, targetType, targetRef,
                reason == null ? null : (reason.length() > 255 ? reason.substring(0, 255) : reason), result);
    }

    private static String format(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime().toString();
    }

    private record ListPatch(String entryId, String listType, String subjectType, String subjectId,
            String reasonCode, boolean enabled, String op) { }

    private record ChangeRow(String changeId, String targetRef, String requestJson,
            String status, String submittedBy) { }
}
