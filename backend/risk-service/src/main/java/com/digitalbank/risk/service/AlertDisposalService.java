package com.digitalbank.risk.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.risk.dto.RiskDtos.AlertDisposalView;
import com.digitalbank.risk.dto.RiskDtos.AlertDisposeRequest;
import com.digitalbank.risk.security.RiskAccess;
import com.digitalbank.risk.support.ChangeIds;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 风险告警处置（UC-RK-03）：对已登记的风险事件记录处置结论，按事件+身份+requestId 幂等。
 * 仅风控专员可处置，且事件关联交易账户必须在会话账户范围内；同键异内容报 409。
 */
@Service
public class AlertDisposalService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> DECISIONS = Set.of("CONFIRMED", "FALSE_POSITIVE", "ESCALATED");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RiskAccess access;

    public AlertDisposalService(JdbcTemplate jdbc, TransactionTemplate transactions, RiskAccess access) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.access = access;
    }

    public AlertDisposalView dispose(SessionIdentity identity, String eventId,
            AlertDisposeRequest body, String traceId) {
        if (body == null || !DECISIONS.contains(body.decision())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "decision 仅支持 CONFIRMED/FALSE_POSITIVE/ESCALATED");
        }
        if (body.basis() == null || body.basis().isBlank() || body.basis().trim().length() > 1000) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请填写处置依据（不超过1000字符）");
        }
        String requestId = ChangeIds.uuid(body.requestId());
        String hash = ChangeIds.disposeHash(eventId, body.decision(), body.basis());
        String basis = body.basis().trim();
        int reasonLength = basis.offsetByCodePoints(0, Math.min(255, basis.codePointCount(0, basis.length())));
        EventRow event = jdbc.query(
                "SELECT e.trade_no, t.account_id FROM risk_event e "
                        + "JOIN `transaction` t ON t.trade_no = e.trade_no WHERE e.event_id = ?",
                (rs, index) -> new EventRow(rs.getString("trade_no"), rs.getString("account_id")),
                eventId).stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_ARGUMENT, "风险事件不存在"));
        access.requireScope(identity, event.accountId());
        try {
            return transactions.execute(status -> {
                jdbc.update("INSERT INTO risk_alert_disposal (event_id, identity_id, decision, basis, request_id, "
                                + "request_hash, trade_no, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        eventId, identity.identityId(), body.decision(), basis, requestId, hash,
                        event.tradeNo(), traceId);
                jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, "
                                + "result, trade_no) VALUES (?, 'RISK_ALERT_DISPOSE', 'risk_event', ?, ?, ?, ?)",
                        identity.identityId(), eventId, basis.substring(0, reasonLength), body.decision(), event.tradeNo());
                return findDisposal(eventId, identity.identityId(), requestId).view();
            });
        } catch (DuplicateKeyException replay) {
            StoredDisposal original = findDisposal(eventId, identity.identityId(), requestId);
            if (!hash.equals(original.requestHash())) {
                throw new BizException(ErrorCode.REQUEST_CONFLICT, "同一请求号的内容与原处置不一致");
            }
            return original.view();
        }
    }

    private StoredDisposal findDisposal(String eventId, String identityId, String requestId) {
        return jdbc.queryForObject(
                "SELECT event_id, trade_no, decision, basis, identity_id, request_id, created_at, trace_id, request_hash "
                        + "FROM risk_alert_disposal WHERE event_id = ? AND identity_id = ? AND request_id = ?",
                (rs, i) -> new StoredDisposal(rs.getString("request_hash"),
                        new AlertDisposalView(rs.getString("event_id"), rs.getString("trade_no"),
                                rs.getString("decision"), rs.getString("basis"), rs.getString("identity_id"),
                                rs.getString("request_id"), format(rs.getObject("created_at", LocalDateTime.class)),
                                rs.getString("trace_id"))),
                eventId, identityId, requestId);
    }

    private record StoredDisposal(String requestHash, AlertDisposalView view) { }
    private record EventRow(String tradeNo, String accountId) { }

    private static String format(LocalDateTime value) {
        return value == null ? OffsetDateTime.now(ZONE).toString() : value.atZone(ZONE).toOffsetDateTime().toString();
    }
}
