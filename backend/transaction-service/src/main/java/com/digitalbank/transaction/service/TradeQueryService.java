package com.digitalbank.transaction.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.transaction.channel.MockChannelService;
import com.digitalbank.transaction.channel.MockChannelService.ChannelRow;
import com.digitalbank.transaction.client.AccountClient;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import com.digitalbank.transaction.dto.TransactionDtos.ReceiptView;
import com.digitalbank.transaction.dto.TransactionDtos.TradeDetailView;
import com.digitalbank.transaction.dto.TransactionDtos.TradePage;
import com.digitalbank.transaction.dto.TransactionDtos.TradeSummary;
import com.digitalbank.transaction.security.Actor;
import com.digitalbank.transaction.security.TransactionAccess;
import com.digitalbank.transaction.support.Money;
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
 * 对外交易查询：先校验用户与原单的访问权限，不把内部查询接口暴露给前端。
 * 待确认（PROCESSING/UNKNOWN）交易触发一次渠道核对；主动查询只沿原单、原渠道请求标识查询一次，
 * 不重启一轮自动任务（交接文档 5.4、8）。
 */
@Service
public class TradeQueryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> STATUSES = Set.of("ACCEPTED", "PROCESSING", "UNKNOWN",
            "SUCCESS", "RECOVERING", "FAILED", "REJECTED");
    /** 待人工处理三态：PENDING 查询别名与具体状态逗号分隔均映射到这组库内字面量。 */
    private static final List<String> PENDING_STATUSES = List.of("UNKNOWN", "PROCESSING", "RECOVERING");

    private final JdbcTemplate jdbc;
    private final TransactionAccess access;
    private final AccountClient account;
    private final MockChannelService channel;
    private final DepositService depositService;
    private final ExternalTransferService externalTransferService;

    public TradeQueryService(JdbcTemplate jdbc, TransactionAccess access, AccountClient account,
            MockChannelService channel, DepositService depositService,
            ExternalTransferService externalTransferService) {
        this.jdbc = jdbc;
        this.access = access;
        this.account = account;
        this.channel = channel;
        this.depositService = depositService;
        this.externalTransferService = externalTransferService;
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

    /**
     * 模拟回单（UC-TR-07）：仅成功交易可生成；客户仅本人、运营/风控按账户范围，管理员由入口拒绝。
     * 回单号由交易号确定性派生，重复下载内容一致；状态以资金最终口径复核，写审计留痕。
     */
    public ReceiptView receipt(Actor actor, String tradeNo, String reason, String traceId) {
        ReceiptRow row = jdbc.query(
                "SELECT trade_no, identity_id, business_type, status, account_id, amount, currency, "
                        + "channel_evidence_id, completed_at FROM `transaction` WHERE trade_no = ?",
                (rs, index) -> new ReceiptRow(rs.getString("trade_no"), rs.getString("identity_id"),
                        rs.getString("business_type"), rs.getString("status"), rs.getString("account_id"),
                        rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("channel_evidence_id"),
                        rs.getObject("completed_at", LocalDateTime.class)), tradeNo).stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.TRADE_NOT_FOUND, "交易不存在"));
        if (!actor.identityId().equals(row.identityId())) {
            // 普通客户按"交易不存在"返回，避免枚举他人交易号；后台身份越账户范围才是 403。
            if (!access.isScopedBackOffice(actor)) {
                throw new BizException(ErrorCode.TRADE_NOT_FOUND, "交易不存在");
            }
            access.requireOperator(actor, row.accountId(), true);
        }
        if (reason != null && reason.trim().length() > 255) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "查询原因不能超过255字符");
        }
        String status = row.status();
        try {
            status = account.outcome(tradeNo, traceId).status();
        } catch (BizException unavailable) {
            // 账户服务暂不可用时按本地原单判定
        }
        if (!"SUCCESS".equals(status)) {
            throw new BizException(ErrorCode.REQUEST_CONFLICT, "仅成功交易可生成回单");
        }
        String issuedAt = offset(LocalDateTime.now(ZONE)).toString();
        jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, "
                        + "result, trade_no) VALUES (?, 'RECEIPT_DOWNLOAD', 'transaction', ?, ?, 'SUCCESS', ?)",
                actor.identityId(), tradeNo, reason == null ? null : reason.trim(), tradeNo);
        return new ReceiptView("RCPT-" + tradeNo, row.tradeNo(), row.businessType(), "SUCCESS",
                Money.format(row.amount()), row.currency(), row.accountId(), row.channelEvidenceId(),
                offset(row.completedAt()) == null ? null : offset(row.completedAt()).toString(), issuedAt,
                "教学模拟回单，非银行真实凭证，仅用于课堂演示");
    }

    private record ReceiptRow(String tradeNo, String identityId, String businessType, String status,
            String accountId, java.math.BigDecimal amount, String currency, String channelEvidenceId,
            LocalDateTime completedAt) { }

    public TradePage list(String actorIdentityId, String businessType, String status,
            int page, int pageSize, String traceId) {
        return list(new Actor(actorIdentityId), businessType, status, page, pageSize, null, traceId);
    }

    /**
     * 查询列表：客户只看本人；运营/风控在账户授权范围内查询并要求查询原因，写入审计。
     * 授权范围与原因审计只对后台角色生效，普通客户路径保持原口径不变。
     */
    public TradePage list(Actor actor, String businessType, String status,
            int page, int pageSize, String reason, String traceId) {
        if (page < 1) throw new BizException(ErrorCode.INVALID_ARGUMENT, "page 从 1 开始");
        if (pageSize < 1 || pageSize > 100) throw new BizException(ErrorCode.INVALID_ARGUMENT, "pageSize 取值 1-100");
        if (businessType != null && businessType.isBlank()) businessType = null;
        List<String> statusValues = expandStatuses(status);
        boolean operator = access.isScopedBackOffice(actor);
        String queryReason = null;
        if (operator) {
            access.requireBackOfficeRole(actor, true);
            if (reason == null || reason.isBlank() || reason.trim().length() > 255) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "后台查询必须填写查询原因");
            }
            queryReason = reason.trim();
        }
        StringBuilder where = operator
                ? new StringBuilder(" WHERE account_id IN ("
                        + String.join(",", Collections.nCopies(actor.accountScope().size(), "?")) + ")")
                : new StringBuilder(" WHERE identity_id = ?");
        List<Object> args = new ArrayList<>(operator ? actor.accountScope() : List.of(actor.identityId()));
        if (businessType != null) {
            where.append(" AND business_type = ?");
            args.add(businessType);
        }
        if (!statusValues.isEmpty()) {
            where.append(" AND status IN ("
                    + String.join(",", Collections.nCopies(statusValues.size(), "?")) + ")");
            args.addAll(statusValues);
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
        if (operator) {
            jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, result) "
                            + "VALUES (?, 'TRANSACTION_LIST', 'transaction_scope', NULL, ?, 'SUCCESS')",
                    actor.identityId(), queryReason);
        }
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
            if ("EXTERNAL_TRANSFER".equals(row.businessType())) {
                externalTransferService.reportExternalResult(row.tradeNo(), row.accountId(), row.amount(),
                        latest, traceId);
            } else {
                depositService.reportChannelResult(row.tradeNo(), row.accountId(), row.amount(), latest, traceId);
            }
        }
    }

    /**
     * 状态筛选解析：字面量保持库内 7 态不变，只在查询层扩展——
     * SUCCEEDED 映射 SUCCESS（旧响应口径），PENDING 映射待处理三态（UC-TR-08 运营清单复用本端点），
     * 逗号分隔的多值取并集。任意非法值仍返回 INVALID_ARGUMENT，不因查询别名放宽写入口径。
     */
    private List<String> expandStatuses(String status) {
        if (status == null || status.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String part : status.split(",")) {
            String token = part == null ? "" : part.trim();
            // 忽略空段：前端拼多值时可能出现尾逗号或连续逗号，不应因此拒绝整个查询
            if (token.isEmpty()) continue;
            if ("PENDING".equals(token)) {
                for (String pending : PENDING_STATUSES) {
                    if (!values.contains(pending)) values.add(pending);
                }
                continue;
            }
            String mapped = "SUCCEEDED".equals(token) ? "SUCCESS" : token;
            if (!STATUSES.contains(mapped)) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "status 不是合法的交易状态");
            }
            if (!values.contains(mapped)) values.add(mapped);
        }
        return values;
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
    }
}
