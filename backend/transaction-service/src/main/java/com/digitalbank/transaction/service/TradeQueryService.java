package com.digitalbank.transaction.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.transaction.channel.MockChannelService;
import com.digitalbank.transaction.channel.MockChannelService.ChannelRow;
import com.digitalbank.transaction.client.AccountClient;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import com.digitalbank.transaction.dto.TransactionDtos.TradeDetailView;
import com.digitalbank.transaction.dto.TransactionDtos.TradePage;
import com.digitalbank.transaction.dto.TransactionDtos.TradeSummary;
import com.digitalbank.transaction.security.TransactionAccess;
import com.digitalbank.transaction.support.Money;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 对外交易查询：先校验用户与原单的访问权限，不把内部查询接口暴露给前端。
 * 待确认（PROCESSING/UNKNOWN）交易触发一次渠道核对；主动查询只沿原单、原渠道请求标识查询一次，
 * 不重启一轮自动任务（交接文档 5.4、8）。
 */
@Service
public class TradeQueryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> STATUSES = Set.of("ACCEPTED", "PROCESSING", "UNKNOWN",
            "SUCCESS", "RECOVERING", "FAILED", "REJECTED");

    private final JdbcTemplate jdbc;
    private final TransactionAccess access;
    private final AccountClient account;
    private final MockChannelService channel;
    private final DepositService depositService;

    public TradeQueryService(JdbcTemplate jdbc, TransactionAccess access, AccountClient account,
            MockChannelService channel, DepositService depositService) {
        this.jdbc = jdbc;
        this.access = access;
        this.account = account;
        this.channel = channel;
        this.depositService = depositService;
    }

    public TradeDetailView detail(String actorIdentityId, String tradeNo, String traceId) {
        IdempotencyService.TradeRow row = readOwnedTrade(actorIdentityId, tradeNo);
        if ("PROCESSING".equals(row.status()) || "UNKNOWN".equals(row.status())) {
            reconcileOnce(row, traceId);
        }
        String status = row.status();
        String reason = row.failureReason();
        try {
            Outcome outcome = account.outcome(tradeNo, traceId);
            status = outcome.status();
            reason = outcome.reasonCode() != null ? outcome.reasonCode() : reason;
        } catch (BizException unreachable) {
            // 账户服务暂不可用时按本地原单解释当前状态
        }
        return new TradeDetailView(row.tradeNo(), row.businessType(), status,
                Money.format(row.amount()), reason);
    }

    public TradePage list(String actorIdentityId, String businessType, String status,
            int page, int pageSize, String traceId) {
        if (page < 1) throw new BizException(ErrorCode.INVALID_ARGUMENT, "page 从 1 开始");
        if (pageSize < 1 || pageSize > 100) throw new BizException(ErrorCode.INVALID_ARGUMENT, "pageSize 取值 1-100");
        if (businessType != null && businessType.isBlank()) businessType = null;
        if (status != null && !STATUSES.contains(status)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "status 不是合法的交易状态");
        }
        StringBuilder where = new StringBuilder(" WHERE identity_id = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(actorIdentityId));
        if (businessType != null) {
            where.append(" AND business_type = ?");
            args.add(businessType);
        }
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM `transaction`" + where, Long.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((page - 1) * (long) pageSize);
        List<TradeSummary> items = jdbc.query("SELECT trade_no, business_type, status, amount, created_at "
                        + "FROM `transaction`" + where + " ORDER BY created_at DESC, trade_no DESC LIMIT ? OFFSET ?",
                (rs, index) -> new TradeSummary(rs.getString("trade_no"), rs.getString("business_type"),
                        rs.getString("status"), Money.format(rs.getBigDecimal("amount")),
                        offset(rs.getObject("created_at", LocalDateTime.class)).toString()),
                pageArgs.toArray());
        return new TradePage(total == null ? 0 : total, page, pageSize, items);
    }

    private IdempotencyService.TradeRow readOwnedTrade(String actorIdentityId, String tradeNo) {
        IdempotencyService.TradeRow row = jdbc.query(
                "SELECT trade_no, identity_id, business_type, status, account_id, amount, currency, failure_reason "
                        + "FROM `transaction` WHERE trade_no = ?",
                (rs, index) -> new IdempotencyService.TradeRow(rs.getString("trade_no"),
                        rs.getString("identity_id"), rs.getString("business_type"), rs.getString("status"),
                        rs.getString("account_id"), rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getString("failure_reason")),
                tradeNo).stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.TRADE_NOT_FOUND, "交易不存在"));
        access.requireOwner(new com.digitalbank.transaction.security.Actor(actorIdentityId), row.identityId());
        return row;
    }

    private void reconcileOnce(IdempotencyService.TradeRow row, String traceId) {
        ChannelRow record = channel.find(row.tradeNo());
        if (record == null) return;
        ChannelRow latest = "UNKNOWN".equals(record.result())
                ? channel.queryForReconcile(row.tradeNo()) : record;
        if (!"UNKNOWN".equals(latest.result())) {
            // 沿原单、原渠道请求标识查询一次；明确结果幂等上报，由 S2 推进终态
            depositService.reportChannelResult(row.tradeNo(), row.accountId(), row.amount(), latest, traceId);
        }
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
    }
}
