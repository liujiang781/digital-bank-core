package com.digitalbank.risk.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.risk.dto.RiskDtos.CheckRequest;
import com.digitalbank.risk.dto.RiskDtos.CheckRequest.AccountView;
import com.digitalbank.risk.dto.RiskDtos.Decision;
import com.digitalbank.risk.support.Money;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 风控规则求值与风险事件留痕。短路顺序固定：账户状态 → 单笔限额 → 日累计限额（交接文档与接口清单）。
 * 启用的 LIMIT_POLICY 规则按 rule_version 降序、id 降序取第一条；无启用规则时放行，
 * 由 S2 在最终受理事务内按系统默认限额复核。REJECT 落 risk_event（PASS 不落），同单同规则幂等。
 */
@Service
public class RiskCheckService {
    private static final Set<String> OUTGOING = Set.of("WITHDRAWAL", "INTERNAL_TRANSFER");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbc;

    public RiskCheckService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Decision check(CheckRequest request) {
        if (request.tradeNo() == null || request.tradeNo().isBlank()
                || request.account() == null || request.account().accountId() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "风控检查请求缺少交易号或账户视图");
        }
        BigDecimal amount = Money.parsePositive(request.amount());
        if (!"CNY".equals(request.currency())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "币种固定为 CNY");
        }
        AccountView account = request.account();
        // 1. 账户状态规则（RK-01）
        if (!"ACTIVE".equals(account.status())) {
            return reject(request, "RK-01", "ACCOUNT_STATUS", "ACCOUNT_STATUS_INVALID");
        }
        if (request.payee() != null && !"ACTIVE".equals(request.payee().status())) {
            return reject(request, "RK-01", "ACCOUNT_STATUS", "ACCOUNT_STATUS_INVALID");
        }
        // 2/3. 限额策略（RK-02）
        LimitPolicy policy = latestPolicy();
        if (policy == null) return new Decision(request.tradeNo(), "PASS", null, null);
        boolean outgoing = OUTGOING.contains(request.businessType());
        BigDecimal single = outgoing ? policy.outgoingSingleLimit() : policy.depositSingleLimit();
        if (amount.compareTo(single) > 0) {
            return reject(request, policy.ruleId(), "SINGLE_LIMIT", "SINGLE_LIMIT_EXCEEDED");
        }
        if (outgoing) {
            BigDecimal used = account.dailyUsedAmount() == null
                    ? BigDecimal.ZERO : new BigDecimal(account.dailyUsedAmount());
            if (used.add(amount).compareTo(policy.outgoingDailyLimit()) > 0) {
                return reject(request, policy.ruleId(), "DAILY_LIMIT", "DAILY_LIMIT_EXCEEDED");
            }
        }
        return new Decision(request.tradeNo(), "PASS", null, null);
    }

    private Decision reject(CheckRequest request, String ruleId, String reason, String reasonCode) {
        String eventId = existingEventId(request.tradeNo(), ruleId);
        if (eventId == null) {
            eventId = newEventId();
            int ruleVersion = ruleVersion(ruleId);
            jdbc.update("INSERT INTO risk_event (event_id, trade_no, rule_id, rule_version, "
                            + "operator_identity_id, reason_code, result) VALUES (?, ?, ?, ?, ?, ?, 'REJECT')",
                    eventId, request.tradeNo(), ruleId, ruleVersion,
                    request.identityId() == null || request.identityId().isBlank()
                            ? "SYSTEM" : request.identityId(),
                    reason);
        }
        return new Decision(request.tradeNo(), "REJECT", reasonCode, eventId);
    }

    private String existingEventId(String tradeNo, String ruleId) {
        List<String> ids = jdbc.queryForList(
                "SELECT event_id FROM risk_event WHERE trade_no = ? AND rule_id = ? AND result = 'REJECT' "
                        + "ORDER BY id DESC LIMIT 1", String.class, tradeNo, ruleId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private int ruleVersion(String ruleId) {
        Integer version = jdbc.queryForObject(
                "SELECT rule_version FROM risk_rule WHERE rule_id = ?", Integer.class, ruleId);
        return version == null ? 1 : version;
    }

    private LimitPolicy latestPolicy() {
        List<LimitPolicy> rules = jdbc.query(
                "SELECT rule_id, rule_version, deposit_single_limit, outgoing_single_limit, outgoing_daily_limit "
                        + "FROM risk_rule WHERE rule_type = 'LIMIT_POLICY' AND enabled = 1 "
                        + "ORDER BY rule_version DESC, id DESC LIMIT 1",
                (row, index) -> new LimitPolicy(row.getString("rule_id"), row.getInt("rule_version"),
                        row.getBigDecimal("deposit_single_limit"), row.getBigDecimal("outgoing_single_limit"),
                        row.getBigDecimal("outgoing_daily_limit")));
        return rules.isEmpty() ? null : rules.getFirst();
    }

    private String newEventId() {
        return "RSKEVT" + LocalDateTime.now(ZONE).format(DAY)
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private record LimitPolicy(String ruleId, int ruleVersion, BigDecimal depositSingleLimit,
            BigDecimal outgoingSingleLimit, BigDecimal outgoingDailyLimit) { }
}
