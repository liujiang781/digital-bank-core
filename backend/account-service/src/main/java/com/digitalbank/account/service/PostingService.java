package com.digitalbank.account.service;

import com.digitalbank.account.domain.Account;
import com.digitalbank.account.dto.PostingDtos.*;
import com.digitalbank.account.support.Money;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** All balance, reservation, flow and financial-state writes share this local transaction boundary. */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = PostingService.AuditedConflict.class)
public class PostingService {
    private static final Set<String> TERMINAL = Set.of("SUCCESS", "FAILED", "REJECTED");
    private static final Set<String> OUTGOING = Set.of("WITHDRAWAL", "INTERNAL_TRANSFER");
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");
    private final JdbcTemplate jdbc;
    private final AccountPolicy policy;

    public PostingService(JdbcTemplate jdbc, AccountPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    public Outcome acceptDeposit(Command command) {
        Trade trade = trade(command.tradeNo(), true);
        match(trade, "DEPOSIT", command.accountId(), command.amount(), command.currency());
        if (TERMINAL.contains(trade.status())) return result(trade);
        if (!"ACCEPTED".equals(trade.status())) {
            requireAccepted(trade);
            return result(trade);
        }
        Account account = account(trade.accountId(), true);
        owner(trade.identityId(), account);
        if (!"ACTIVE".equals(account.status())) return reject(trade, "ACCOUNT_STATUS_INVALID");
        if (trade.amount().compareTo(policy.limits().deposit()) > 0) return reject(trade, "SINGLE_LIMIT_EXCEEDED");
        jdbc.update("UPDATE `transaction` SET status = 'PROCESSING', accepted_at = CURRENT_TIMESTAMP, "
                + "failure_reason = NULL, version = version + 1 WHERE trade_no = ?", trade.tradeNo());
        return fresh(trade.tradeNo());
    }

    public Outcome completeDeposit(DepositResult receipt) {
        if (!"SUCCESS".equals(receipt.result())) throw invalid("成功回执入口只接受 SUCCESS");
        return depositResult(receipt);
    }

    public Outcome depositResult(DepositResult receipt) {
        validateReceipt(receipt);
        Trade trade = trade(receipt.tradeNo(), true);
        match(trade, "DEPOSIT", receipt.accountId(), receipt.amount(), receipt.currency());
        if (TERMINAL.contains(trade.status())) {
            if ("UNKNOWN".equals(receipt.result())) return result(trade);
            if (trade.status().equals(receipt.result())) return result(trade);
            throw conflict(trade, "渠道结果与既有终态矛盾，保留终态并转人工核对");
        }
        requireAccepted(trade);
        Account account = account(trade.accountId(), true);
        // Frozen after acceptance may settle; closing is blocked by the lifecycle service while this trade is pending.
        if ("CLOSED".equals(account.status())) throw conflicting("已受理账户不能处于注销状态");
        if ("UNKNOWN".equals(receipt.result())) {
            jdbc.update("UPDATE `transaction` SET status = 'UNKNOWN', last_channel_result = 'UNKNOWN', "
                            + "last_checked_at = ?, failure_reason = ?, version = version + 1 WHERE trade_no = ?",
                    observed(receipt), receipt.reasonCode(), trade.tradeNo());
        } else if ("FAILED".equals(receipt.result())) {
            jdbc.update("UPDATE `transaction` SET status = 'FAILED', last_channel_result = 'FAILED', "
                            + "last_checked_at = ?, channel_evidence_id = ?, failure_reason = ?, "
                            + "completed_at = CURRENT_TIMESTAMP, version = version + 1 WHERE trade_no = ?",
                    observed(receipt), receipt.evidenceId(), receipt.reasonCode(), trade.tradeNo());
        } else {
            BigDecimal balance = checkedCredit(account.bookBalance(), trade.amount());
            jdbc.update("UPDATE account SET book_balance = ?, version = version + 1 WHERE account_id = ?",
                    balance, account.accountId());
            flow(trade, account.accountId(), "DEPOSIT_IN", "CREDIT", balance);
            jdbc.update("UPDATE `transaction` SET status = 'SUCCESS', last_channel_result = 'SUCCESS', "
                            + "last_checked_at = ?, channel_evidence_id = ?, failure_reason = NULL, "
                            + "completed_at = CURRENT_TIMESTAMP, version = version + 1 WHERE trade_no = ?",
                    observed(receipt), receipt.evidenceId(), trade.tradeNo());
        }
        return fresh(trade.tradeNo());
    }

    public Outcome reserve(Command command) {
        Trade trade = trade(command.tradeNo(), true);
        outgoing(trade, command);
        Transfer transfer = transfer(trade, command.payeeAccountId());
        if (TERMINAL.contains(trade.status())) return result(trade);
        Reservation existing = reservation(trade.tradeNo());
        if (existing != null) {
            matchReservation(trade, existing);
            return result(trade);
        }
        if (!"ACCEPTED".equals(trade.status())) throw conflicting("当前交易不允许首次预留");
        Map<String, Account> accounts = lockAccounts(trade.accountId(), transfer == null ? null : transfer.payee());
        Account payer = accounts.get(trade.accountId());
        owner(trade.identityId(), payer);
        if (accounts.values().stream().anyMatch(a -> !"ACTIVE".equals(a.status()))) {
            return reject(trade, "ACCOUNT_STATUS_INVALID");
        }
        if (payer.availableBalance().compareTo(trade.amount()) < 0) {
            return reject(trade, "INSUFFICIENT_AVAILABLE_BALANCE");
        }
        var limits = policy.limits();
        if (trade.amount().compareTo(limits.singleFor(payer)) > 0) return reject(trade, "SINGLE_LIMIT_EXCEEDED");
        if (policy.dailyUsed(payer.accountId()).add(trade.amount()).compareTo(limits.dailyFor(payer)) > 0) {
            return reject(trade, "DAILY_LIMIT_EXCEEDED");
        }
        jdbc.update("INSERT INTO account_reservation (trade_no, account_id, amount, status) VALUES (?, ?, ?, 'RESERVED')",
                trade.tradeNo(), payer.accountId(), trade.amount());
        jdbc.update("UPDATE account SET reserved_amount = reserved_amount + ?, version = version + 1 WHERE account_id = ?",
                trade.amount(), payer.accountId());
        jdbc.update("UPDATE `transaction` SET status = 'PROCESSING', accepted_at = CURRENT_TIMESTAMP, "
                + "failure_reason = NULL, version = version + 1 WHERE trade_no = ?", trade.tradeNo());
        return fresh(trade.tradeNo());
    }

    public Outcome completeWithdrawal(Command command) {
        Trade trade = trade(command.tradeNo(), true);
        match(trade, "WITHDRAWAL", command.payerAccountId(), command.amount(), command.currency());
        if (TERMINAL.contains(trade.status())) return result(trade);
        Reservation reserved = requireReservation(trade);
        Account payer = account(trade.accountId(), true);
        debit(trade, payer, reserved);
        flow(trade, payer.accountId(), "WITHDRAW_OUT", "DEBIT", payer.bookBalance().subtract(trade.amount()));
        consumed(trade);
        return fresh(trade.tradeNo());
    }

    public Outcome postInternalTransfer(Command command) {
        Trade trade = trade(command.tradeNo(), true);
        match(trade, "INTERNAL_TRANSFER", command.payerAccountId(), command.amount(), command.currency());
        Transfer transfer = transfer(trade, command.payeeAccountId());
        if (TERMINAL.contains(trade.status())) return result(trade);
        Reservation reserved = requireReservation(trade);
        Map<String, Account> accounts = lockAccounts(trade.accountId(), transfer.payee());
        Account payer = accounts.get(trade.accountId());
        Account payee = accounts.get(transfer.payee());
        if ("CLOSED".equals(payee.status())) throw conflicting("已受理转账的收款账户不能注销");
        BigDecimal credited = checkedCredit(payee.bookBalance(), trade.amount());
        debit(trade, payer, reserved);
        jdbc.update("UPDATE account SET book_balance = ?, version = version + 1 WHERE account_id = ?",
                credited, payee.accountId());
        flow(trade, payer.accountId(), "TRANSFER_OUT", "DEBIT", payer.bookBalance().subtract(trade.amount()));
        flow(trade, payee.accountId(), "TRANSFER_IN", "CREDIT", credited);
        consumed(trade);
        return fresh(trade.tradeNo());
    }

    public Outcome release(Release command) {
        Trade trade = trade(command.tradeNo(), true);
        if (!OUTGOING.contains(trade.type())) throw conflicting("释放仅适用于本轮出账业务");
        match(trade, trade.type(), command.payerAccountId(), command.amount(), command.currency());
        return failure(trade, new Failure(command.tradeNo(), "FAILED", command.reasonCode(),
                command.evidenceType(), command.evidenceId()));
    }

    public Outcome recordFailure(Failure command) {
        return failure(trade(command.tradeNo(), true), command);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Outcome outcome(String tradeNo) {
        return result(trade(tradeNo, false));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(SnapshotRequest request) {
        text(request.identityId(), 64, "identityId");
        if (!Set.of("DEPOSIT", "WITHDRAWAL", "INTERNAL_TRANSFER").contains(
                Objects.toString(request.businessType(), ""))) throw invalid("不支持的业务类型");
        boolean deposit = "DEPOSIT".equals(request.businessType());
        Account payer = account(deposit ? request.accountId() : request.payerAccountId(), false);
        owner(request.identityId(), payer);
        var limits = policy.limits();
        AccountSnapshot data = new AccountSnapshot(payer.accountId(), payer.currency(), payer.status(),
                Money.format(payer.bookBalance()), Money.format(payer.reservedAmount()),
                Money.format(payer.availableBalance()), Money.format(deposit ? limits.deposit() : limits.singleFor(payer)),
                Money.format(limits.dailyFor(payer)), Money.format(policy.dailyUsed(payer.accountId())), limits.version());
        PayeeSnapshot payee = null;
        if ("INTERNAL_TRANSFER".equals(request.businessType())) {
            if (payer.accountId().equals(request.payeeAccountId())) throw invalid("不能向同一账户转账");
            Account target = account(request.payeeAccountId(), false);
            payee = new PayeeSnapshot(target.accountId(), target.currency(), target.status());
        }
        return new Snapshot(data, payee);
    }

    private Outcome failure(Trade trade, Failure command) {
        reasonCode(command.reasonCode(), true);
        text(command.evidenceId(), 64, "evidenceId");
        boolean rejection = "REJECTED".equals(command.decision());
        if (rejection) {
            if (!"RISK_EVENT".equals(command.evidenceType())) throw invalid("风险拒绝必须引用风险事件");
            var events = jdbc.queryForList("SELECT reason_code FROM risk_event WHERE event_id = ? "
                            + "AND trade_no = ? AND result = 'REJECT'", command.evidenceId(), trade.tradeNo());
            if (events.size() != 1) throw conflicting("拒绝依据与原单不匹配");
            String expected = switch (events.getFirst().get("reason_code").toString()) {
                case "ACCOUNT_STATUS" -> "ACCOUNT_STATUS_INVALID";
                case "SINGLE_LIMIT" -> "SINGLE_LIMIT_EXCEEDED";
                case "DAILY_LIMIT" -> "DAILY_LIMIT_EXCEEDED";
                default -> "";
            };
            if (!expected.equals(command.reasonCode())) throw conflicting("风险拒绝原因与事件不匹配");
        } else {
            if (!"FAILED".equals(command.decision()) || !"LOCAL_FAILURE".equals(command.evidenceType())) {
                throw invalid("仅接受 REJECTED 风险拒绝或 FAILED 本地明确失败");
            }
            if (!OUTGOING.contains(trade.type())) throw invalid("存款渠道结果必须使用 deposit-results");
            if (uncertain(command.reasonCode())) {
                throw invalid("超时或未知不能作为释放和失败依据");
            }
        }
        if (TERMINAL.contains(trade.status())) {
            if (trade.status().equals(command.decision()) && Objects.equals(trade.reason(), command.reasonCode())
                    && Objects.equals(trade.evidence(), command.evidenceId())) return result(trade);
            throw conflict(trade, "失败或拒绝命令与既有终态不一致");
        }
        if ("RECOVERING".equals(trade.status()) && (!Objects.equals(trade.reason(), command.reasonCode())
                || !Objects.equals(trade.evidence(), command.evidenceId()))) {
            throw conflict(trade, "恢复必须沿用已登记的明确失败依据");
        }
        Reservation reservation = reservation(trade.tradeNo());
        if (rejection) {
            if (!"ACCEPTED".equals(trade.status()) || trade.acceptedAt() != null || reservation != null) {
                throw conflicting("已经账户侧受理的原单不能按受理前拒绝处理");
            }
        } else {
            if (reservation == null) {
                if (!"ACCEPTED".equals(trade.status())) throw conflicting("受理后的失败缺少逐笔预留记录");
            } else {
                matchReservation(trade, reservation);
                if (!"RESERVED".equals(reservation.status())) throw conflicting("预留已被消耗或释放");
                Account payer = account(trade.accountId(), true);
                if (payer.reservedAmount().compareTo(reservation.amount()) < 0) {
                    throw new IllegalStateException("Reservation exceeds account reserved balance");
                }
                jdbc.update("UPDATE account SET reserved_amount = reserved_amount - ?, version = version + 1 "
                        + "WHERE account_id = ?", reservation.amount(), payer.accountId());
                jdbc.update("UPDATE account_reservation SET status = 'RELEASED', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE trade_no = ?", trade.tradeNo());
            }
        }
        jdbc.update("UPDATE `transaction` SET status = ?, failure_reason = ?, channel_evidence_id = ?, "
                        + "last_channel_result = ?, last_checked_at = CURRENT_TIMESTAMP, completed_at = CURRENT_TIMESTAMP, "
                        + "version = version + 1 WHERE trade_no = ?", command.decision(), command.reasonCode(),
                command.evidenceId(), command.evidenceType(), trade.tradeNo());
        audit(trade, rejection ? "POSTING_REJECT" : "POSTING_FAILURE", command.reasonCode());
        return fresh(trade.tradeNo());
    }

    private void validateReceipt(DepositResult receipt) {
        text(receipt.tradeNo(), 32, "tradeNo");
        if (!Set.of("SUCCESS", "FAILED", "UNKNOWN").contains(Objects.toString(receipt.result(), ""))) {
            throw invalid("渠道结果只能为 SUCCESS/FAILED/UNKNOWN");
        }
        if (!("CHD" + receipt.tradeNo()).equals(receipt.channelRequestId())) throw conflicting("渠道请求号与交易号不匹配");
        if (receipt.observedAt() == null || !ZoneOffset.ofHours(8).equals(receipt.observedAt().getOffset())) {
            throw invalid("observedAt 必须为东八区时间");
        }
        if (!"UNKNOWN".equals(receipt.result())) text(receipt.evidenceId(), 64, "evidenceId");
        if (receipt.evidenceId() != null && receipt.evidenceId().length() > 64) throw invalid("evidenceId 过长");
        reasonCode(receipt.reasonCode(), "FAILED".equals(receipt.result()));
        if ("FAILED".equals(receipt.result()) && uncertain(receipt.reasonCode())) {
            throw invalid("超时或未知结果不能作为明确失败回执");
        }
    }

    private void outgoing(Trade trade, Command command) {
        if (!OUTGOING.contains(trade.type())) throw conflicting("本轮预留仅用于取款和本行转账");
        match(trade, trade.type(), command.payerAccountId(), command.amount(), command.currency());
    }

    private void match(Trade trade, String type, String accountId, String amount, String currency) {
        text(accountId, 32, "accountId");
        BigDecimal parsed = Money.parsePositive(amount);
        if (!"CNY".equals(currency)) throw invalid("仅支持 CNY");
        if (!trade.type().equals(type) || !trade.accountId().equals(accountId)
                || parsed.compareTo(trade.amount()) != 0 || !trade.currency().equals(currency)) {
            throw conflicting("命令类型、账户、金额或币种与原单不匹配");
        }
    }

    private Transfer transfer(Trade trade, String payeeId) {
        if (!"INTERNAL_TRANSFER".equals(trade.type())) return null;
        text(payeeId, 32, "payeeAccountId");
        var rows = jdbc.query("SELECT payer_account_id, payee_account_id, amount, currency, transfer_type "
                        + "FROM transfer_order WHERE trade_no = ? FOR UPDATE",
                (rs, n) -> new Transfer(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5)),
                trade.tradeNo());
        if (rows.size() != 1) throw conflicting("本行转账缺少原转账单");
        Transfer transfer = rows.getFirst();
        if (!"INTERNAL".equals(transfer.type()) || !trade.accountId().equals(transfer.payer())
                || !payeeId.equals(transfer.payee()) || payeeId.equals(trade.accountId())
                || transfer.amount().compareTo(trade.amount()) != 0 || !trade.currency().equals(transfer.currency())) {
            throw conflicting("转账双方、金额或币种与原转账单不一致");
        }
        return transfer;
    }

    private Map<String, Account> lockAccounts(String payer, String payee) {
        var ids = payee == null ? List.of(payer) : List.of(payer, payee).stream().distinct().sorted().toList();
        Map<String, Account> result = new HashMap<>();
        for (String id : ids) result.put(id, account(id, true));
        return result;
    }

    private Account account(String accountId, boolean lock) {
        text(accountId, 32, "accountId");
        var rows = jdbc.query("SELECT * FROM account WHERE account_id = ?" + (lock ? " FOR UPDATE" : ""),
                Account::fromRow, accountId);
        if (rows.isEmpty()) throw new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "账户不存在或不在授权范围");
        return rows.getFirst();
    }

    private void owner(String identityId, Account account) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE customer_id = ? AND identity_id = ? "
                        + "AND status = 'NORMAL' AND real_name_status = 'VERIFIED'", Integer.class, account.customerId(), identityId);
        if (count == null || count != 1) throw new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "账户不存在或不在授权范围");
    }

    private Trade trade(String tradeNo, boolean lock) {
        text(tradeNo, 32, "tradeNo");
        var rows = jdbc.query("SELECT trade_no, identity_id, business_type, account_id, amount, currency, status, "
                        + "accepted_at, channel_evidence_id, failure_reason FROM `transaction` WHERE trade_no = ?"
                        + (lock ? " FOR UPDATE" : ""), (rs, n) -> {
                    return new Trade(rs.getString("trade_no"), rs.getString("identity_id"), rs.getString("business_type"),
                            rs.getString("account_id"), rs.getBigDecimal("amount"), rs.getString("currency"),
                            rs.getString("status"), rs.getObject("accepted_at", LocalDateTime.class),
                            rs.getString("channel_evidence_id"), rs.getString("failure_reason"));
                }, tradeNo);
        if (rows.isEmpty()) throw new BizException(ErrorCode.TRADE_NOT_FOUND, "原交易不存在");
        return rows.getFirst();
    }

    private Reservation reservation(String tradeNo) {
        var rows = jdbc.query("SELECT account_id, amount, status FROM account_reservation WHERE trade_no = ? FOR UPDATE",
                (rs, n) -> new Reservation(rs.getString(1), rs.getBigDecimal(2), rs.getString(3)), tradeNo);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Reservation requireReservation(Trade trade) {
        requireAccepted(trade);
        Reservation reservation = reservation(trade.tradeNo());
        if (reservation == null || !"RESERVED".equals(reservation.status())) throw conflicting("提交前必须存在原交易预留");
        matchReservation(trade, reservation);
        return reservation;
    }

    private void matchReservation(Trade trade, Reservation reservation) {
        if (!trade.accountId().equals(reservation.accountId()) || trade.amount().compareTo(reservation.amount()) != 0) {
            throw new IllegalStateException("Reservation does not match immutable transaction");
        }
    }

    private void requireAccepted(Trade trade) {
        if (trade.acceptedAt() == null || !Set.of("PROCESSING", "UNKNOWN").contains(trade.status())) {
            throw conflicting("原单尚未账户侧受理，或当前状态不允许执行该动作");
        }
    }

    private void debit(Trade trade, Account account, Reservation reservation) {
        if ("CLOSED".equals(account.status()) || account.bookBalance().compareTo(trade.amount()) < 0
                || account.reservedAmount().compareTo(reservation.amount()) < 0) {
            throw new IllegalStateException("Accepted reservation cannot be consumed from this account snapshot");
        }
        jdbc.update("UPDATE account SET book_balance = book_balance - ?, reserved_amount = reserved_amount - ?, "
                        + "version = version + 1 WHERE account_id = ?", trade.amount(), reservation.amount(), account.accountId());
    }

    private void consumed(Trade trade) {
        jdbc.update("UPDATE account_reservation SET status = 'CONSUMED', updated_at = CURRENT_TIMESTAMP WHERE trade_no = ?",
                trade.tradeNo());
        jdbc.update("UPDATE `transaction` SET status = 'SUCCESS', failure_reason = NULL, "
                + "completed_at = CURRENT_TIMESTAMP, version = version + 1 WHERE trade_no = ?", trade.tradeNo());
    }

    private void flow(Trade trade, String accountId, String role, String direction, BigDecimal balance) {
        jdbc.update("INSERT INTO transaction_flow (entry_id, trade_no, account_id, entry_role, direction, amount, book_balance_after) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString().replace("-", ""),
                trade.tradeNo(), accountId, role, direction, trade.amount(), balance);
    }

    private Outcome reject(Trade trade, String reason) {
        jdbc.update("UPDATE `transaction` SET status = 'REJECTED', failure_reason = ?, "
                + "completed_at = CURRENT_TIMESTAMP, version = version + 1 WHERE trade_no = ?", reason, trade.tradeNo());
        audit(trade, "POSTING_REJECT", reason);
        return fresh(trade.tradeNo());
    }

    private Outcome fresh(String tradeNo) { return result(trade(tradeNo, false)); }

    private Outcome result(Trade trade) {
        // No lock here: the caller either holds the transaction row or performs a read-only snapshot.
        var rows = jdbc.query("SELECT status FROM account_reservation WHERE trade_no = ?",
                (rs, n) -> rs.getString(1), trade.tradeNo());
        return new Outcome(trade.tradeNo(), trade.status(), rows.isEmpty() ? "NONE" : rows.getFirst(), trade.reason());
    }

    private AuditedConflict conflict(Trade trade, String reason) {
        audit(trade, "CHANNEL_CONFLICT", reason);
        return new AuditedConflict(reason);
    }

    private void audit(Trade trade, String action, String reason) {
        jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, result, trade_no) "
                + "VALUES ('SYSTEM', ?, 'transaction', ?, ?, ?, ?)", action, trade.tradeNo(), reason,
                "POSTING_FAILURE".equals(action) ? "FAILURE" : "REJECT", trade.tradeNo());
    }

    private static BigDecimal checkedCredit(BigDecimal balance, BigDecimal amount) {
        BigDecimal next = balance.add(amount);
        if (next.compareTo(MAX_MONEY) > 0) throw invalid("入账后余额超出 DECIMAL(18,2) 范围");
        return next;
    }

    private static LocalDateTime observed(DepositResult result) {
        return result.observedAt().toLocalDateTime();
    }

    private static void text(String value, int limit, String field) {
        if (value == null || value.isBlank() || value.length() > limit) throw invalid(field + " 为空或长度超限");
    }

    private static void reasonCode(String value, boolean required) {
        if (value == null && !required) return;
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw invalid("reasonCode 必须为大写字母、数字、下划线组成的机器码");
        }
    }

    private static boolean uncertain(String reason) {
        return reason.contains("TIMEOUT") || reason.contains("UNKNOWN") || reason.contains("RESPONSE_LOST")
                || reason.contains("CONNECTION_LOST") || reason.contains("NETWORK_ERROR");
    }

    private static BizException invalid(String reason) { return new BizException(ErrorCode.INVALID_ARGUMENT, reason); }
    private static BizException conflicting(String reason) { return new BizException(ErrorCode.REQUEST_CONFLICT, reason); }

    /** Only thrown before any money mutation, after appending an audit entry that must survive HTTP 409. */
    public static class AuditedConflict extends BizException {
        public AuditedConflict(String reason) { super(ErrorCode.REQUEST_CONFLICT, reason); }
    }

    private record Trade(String tradeNo, String identityId, String type, String accountId, BigDecimal amount,
            String currency, String status, LocalDateTime acceptedAt, String evidence, String reason) { }
    private record Reservation(String accountId, BigDecimal amount, String status) { }
    private record Transfer(String payer, String payee, BigDecimal amount, String currency, String type) { }
}
