package com.digitalbank.risk.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.risk.dto.RiskDtos.RiskRecordPage;
import com.digitalbank.risk.dto.RiskDtos.RiskRecordView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 风险记录查询（UC-RK-04）：按会话中的账户范围过滤，join 关联交易与命中规则；
 * 查询原因写入审计日志。管理员与无范围身份由 RiskAccess 拒绝。
 */
@Service
public class RiskRecordService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> RESULTS = Set.of("PASS", "REJECT");
    private static final Set<String> REASONS = Set.of("ACCOUNT_STATUS", "SINGLE_LIMIT", "DAILY_LIMIT");

    private final JdbcTemplate jdbc;

    public RiskRecordService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RiskRecordPage query(SessionIdentity identity, String tradeNo, String ruleId, String reasonCode,
            String result, OffsetDateTime startTime, OffsetDateTime endTime, int page, int pageSize,
            String reason, String traceId) {
        if (page < 1) throw new BizException(ErrorCode.INVALID_ARGUMENT, "page 从 1 开始");
        if (pageSize < 1 || pageSize > 100) throw new BizException(ErrorCode.INVALID_ARGUMENT, "pageSize 取值 1-100");
        if (reason == null || reason.isBlank() || reason.trim().length() > 255) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请填写查询原因");
        }
        if (result != null && !result.isBlank() && !RESULTS.contains(result)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "result 仅支持 PASS/REJECT");
        }
        if (reasonCode != null && !reasonCode.isBlank() && !REASONS.contains(reasonCode)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "reasonCode 不是合法的风险原因码");
        }
        List<Object> args = new ArrayList<>(identity.accountScope());
        StringBuilder where = new StringBuilder(" WHERE t.account_id IN ("
                + String.join(",", Collections.nCopies(identity.accountScope().size(), "?")) + ")");
        if (tradeNo != null && !tradeNo.isBlank()) {
            where.append(" AND e.trade_no = ?");
            args.add(tradeNo);
        }
        if (ruleId != null && !ruleId.isBlank()) {
            where.append(" AND e.rule_id = ?");
            args.add(ruleId);
        }
        if (reasonCode != null && !reasonCode.isBlank()) {
            where.append(" AND e.reason_code = ?");
            args.add(reasonCode);
        }
        if (result != null && !result.isBlank()) {
            where.append(" AND e.result = ?");
            args.add(result);
        }
        if (startTime != null) {
            where.append(" AND e.created_at >= ?");
            args.add(LocalDateTime.ofInstant(startTime.toInstant(), ZONE));
        }
        if (endTime != null) {
            where.append(" AND e.created_at <= ?");
            args.add(LocalDateTime.ofInstant(endTime.toInstant(), ZONE));
        }
        String from = " FROM risk_event e JOIN `transaction` t ON t.trade_no = e.trade_no" + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (page - 1) * pageSize);
        List<RiskRecordView> items = jdbc.query(
                "SELECT e.event_id, e.trade_no, e.rule_id, e.rule_version, e.reason_code, e.result, "
                        + "e.operator_identity_id, e.created_at, t.business_type, t.account_id, t.amount, t.status"
                        + from + " ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?",
                (rs, index) -> {
                    LocalDateTime created = rs.getObject("created_at", LocalDateTime.class);
                    java.math.BigDecimal amount = rs.getBigDecimal("amount");
                    return new RiskRecordView(rs.getString("event_id"), rs.getString("trade_no"),
                            rs.getString("rule_id"), rs.getInt("rule_version"), rs.getString("reason_code"),
                            rs.getString("result"), rs.getString("operator_identity_id"),
                            created == null ? null : created.atZone(ZONE).toOffsetDateTime().toString(),
                            rs.getString("business_type"), rs.getString("account_id"),
                            amount == null ? null : amount.toPlainString(), rs.getString("status"));
                }, pageArgs.toArray());
        jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, result) "
                        + "VALUES (?, 'RISK_RECORD_LIST', 'risk_scope', NULL, ?, 'SUCCESS')",
                identity.identityId(), reason.trim());
        return new RiskRecordPage(total == null ? 0 : total, page, pageSize, items);
    }
}
