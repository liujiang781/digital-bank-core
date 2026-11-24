package com.digitalbank.transaction.service;

import com.digitalbank.transaction.channel.MockChannelService;
import com.digitalbank.transaction.channel.MockChannelService.ChannelRow;
import com.digitalbank.transaction.client.AccountClient;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 非终态原单恢复扫描（交接文档 7.1/8、跨行交接文档第 3 节）：
 * 进程启动后以及每 10 秒扫描一次，沿原 tradeNo 从未完成步骤恢复。
 * 按业务类型分流：存款、取款和跨行走模拟渠道；本行转账在本地记账。
 * 跨行转账渠道链路（PROCESSING/UNKNOWN/RECOVERING 沿原渠道记录重放回执）。
 * ACCEPTED 继续风控与账户侧受理/预留；PROCESSING 先查资金结果再推进；
 * UNKNOWN/RECOVERING 只在已登记的渠道结果或失败依据上推进：
 * 渠道 UNKNOWN 只在 next_check_at 到期且未转人工时自动核对，最多 6 次，
 * 次数持久化、不因重启重置。恢复任务按 tradeNo 串行处理（单线程扫描，单笔失败不影响后续）。
 */
@Component
public class RecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_AUTO_RECONCILE = 6;

    private final JdbcTemplate jdbc;
    private final AccountClient account;
    private final MockChannelService channel;
    private final DepositService depositService;
    private final WithdrawalService withdrawalService;
    private final TransferService transferService;
    private final ExternalTransferService externalTransferService;

    public RecoveryScheduler(JdbcTemplate jdbc, AccountClient account, MockChannelService channel,
            DepositService depositService, WithdrawalService withdrawalService, TransferService transferService,
            ExternalTransferService externalTransferService) {
        this.jdbc = jdbc;
        this.account = account;
        this.channel = channel;
        this.depositService = depositService;
        this.withdrawalService = withdrawalService;
        this.transferService = transferService;
        this.externalTransferService = externalTransferService;
    }

    @Scheduled(fixedDelayString = "${recovery.interval-millis:10000}",
            initialDelayString = "${recovery.initial-delay-millis:10000}")
    public void recover() {
        List<IdempotencyService.TradeRow> rows = jdbc.query(
                "SELECT trade_no, identity_id, business_type, status, account_id, amount, currency, failure_reason "
                        + "FROM `transaction` WHERE status IN ('ACCEPTED', 'PROCESSING', 'UNKNOWN', 'RECOVERING') "
                        + "ORDER BY trade_no",
                (rs, index) -> new IdempotencyService.TradeRow(rs.getString("trade_no"),
                        rs.getString("identity_id"), rs.getString("business_type"), rs.getString("status"),
                        rs.getString("account_id"), rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getString("failure_reason")));
        for (IdempotencyService.TradeRow row : rows) {
            try {
                switch (row.businessType()) {
                    case "DEPOSIT" -> resumeDeposit(row);
                    case "WITHDRAWAL" -> resumeWithdrawal(row);
                    case "INTERNAL_TRANSFER" -> resumeTransfer(row);
                    case "EXTERNAL_TRANSFER" -> resumeExternal(row);
                    default -> { } // 其他业务类型本轮无编排
                }
            } catch (Exception e) {
                log.warn("恢复扫描单笔失败，下一轮继续: tradeNo={}", row.tradeNo(), e);
            }
        }
    }

    private void resumeDeposit(IdempotencyService.TradeRow row) {
        switch (row.status()) {
            case "ACCEPTED" -> depositService.resume(row, null);
            case "PROCESSING" -> resumeDepositProcessing(row);
            case "UNKNOWN" -> reconcileDepositUnknown(row);
            default -> log.warn("存款原单出现未预期的恢复状态，转人工核对: tradeNo={}, status={}",
                    row.tradeNo(), row.status());
        }
    }

    private void resumeWithdrawal(IdempotencyService.TradeRow row) {
        if ("ACCEPTED".equals(row.status())) {
            withdrawalService.resume(row, null);
            return;
        }
        ChannelRow record = channel.find(row.tradeNo());
        if (record == null && "PROCESSING".equals(row.status())) {
            record = channel.createOrGet(row.tradeNo(), row.accountId(), row.amount(), row.currency());
        }
        if (record == null) return; // Older RECOVERING rows may only have local failure evidence.
        if ("UNKNOWN".equals(row.status()) && MockChannelService.UNKNOWN.equals(record.result())) {
            record = reconcileChannelRecord(row, record);
            if (MockChannelService.UNKNOWN.equals(record.result())) return;
        }
        withdrawalService.reportChannelResult(record, null);
    }

    private void resumeTransfer(IdempotencyService.TradeRow row) {
        switch (row.status()) {
            case "ACCEPTED" -> transferService.resume(row, null);
            case "PROCESSING" -> transferService.resumeProcessing(row, null);
            default -> log.warn("出账原单出现未知或恢复状态，转人工核对，不得改判失败: tradeNo={}, status={}",
                    row.tradeNo(), row.status());
        }
    }

    /** 跨行：ACCEPTED 继续编排；PROCESSING/UNKNOWN/RECOVERING 沿原渠道记录重放回执（幂等）。 */
    private void resumeExternal(IdempotencyService.TradeRow row) {
        switch (row.status()) {
            case "ACCEPTED" -> externalTransferService.resume(row, null);
            case "PROCESSING" -> replayExternalRecord(row);
            case "UNKNOWN", "RECOVERING" -> replayExternalAfterReconcile(row);
            default -> { }
        }
    }

    /** 跨行 PROCESSING：渠道记录已明确则重放明确回执；渠道仍 UNKNOWN 等待核对阶段推进。 */
    private void replayExternalRecord(IdempotencyService.TradeRow row) {
        ChannelRow record = channel.find(row.tradeNo());
        if (record == null) {
            record = channel.createOrGet(row.tradeNo(), row.accountId(), row.amount(), row.currency());
        }
        if (!MockChannelService.UNKNOWN.equals(record.result())) {
            externalTransferService.reportExternalResult(row.tradeNo(), row.accountId(), row.amount(),
                    record, null);
        }
    }

    /** 跨行 UNKNOWN/RECOVERING：先做渠道有界核对（UNKNOWN 时），得到明确结果后重放回执至终态。 */
    private void replayExternalAfterReconcile(IdempotencyService.TradeRow row) {
        ChannelRow record = channel.find(row.tradeNo());
        if (record == null) {
            // RECOVERING 必然已登记渠道明确结果；记录缺失属于数据异常，转人工
            if ("RECOVERING".equals(row.status())) {
                log.warn("跨行恢复中渠道记录缺失，转人工核对: tradeNo={}", row.tradeNo());
                return;
            }
            record = channel.createOrGet(row.tradeNo(), row.accountId(), row.amount(), row.currency());
        }
        if (MockChannelService.UNKNOWN.equals(record.result())) {
            record = reconcileChannelRecord(row, record);
        }
        if (!MockChannelService.UNKNOWN.equals(record.result())) {
            externalTransferService.reportExternalResult(row.tradeNo(), row.accountId(), row.amount(),
                    record, null);
        }
    }

    private void resumeDepositProcessing(IdempotencyService.TradeRow row) {
        Outcome outcome = null;
        try {
            outcome = account.outcome(row.tradeNo(), null);
        } catch (Exception e) {
            log.warn("查询资金结果失败，等待下一轮: tradeNo={}", row.tradeNo(), e);
            return;
        }
        if (DepositService.isTerminal(outcome.status())) return;
        ChannelRow record = channel.find(row.tradeNo());
        if (record == null) {
            record = channel.createOrGet(row.tradeNo(), row.accountId(), row.amount(), row.currency());
        }
        if (!MockChannelService.UNKNOWN.equals(record.result())) {
            // 已有明确渠道结果但交易未终态：幂等重报，由 S2 推进
            depositService.reportChannelResult(row.tradeNo(), row.accountId(), row.amount(), record, null);
        }
    }

    private void reconcileDepositUnknown(IdempotencyService.TradeRow row) {
        ChannelRow record = channel.find(row.tradeNo());
        if (record == null) {
            record = channel.createOrGet(row.tradeNo(), row.accountId(), row.amount(), row.currency());
        }
        ChannelRow latest = MockChannelService.UNKNOWN.equals(record.result())
                ? reconcileChannelRecord(row, record) : record;
        if (!MockChannelService.UNKNOWN.equals(latest.result())) {
            depositService.reportChannelResult(row.tradeNo(), row.accountId(), row.amount(), latest, null);
        }
    }

    /**
     * 渠道层有界核对：UNKNOWN 且到期才查询一次渠道，进度持久化、不因扫描或重启重置；
     * 已转人工或未到期原样返回。返回核对后的最新渠道记录。
     */
    private ChannelRow reconcileChannelRecord(IdempotencyService.TradeRow row, ChannelRow record) {
        if (record.manualReviewRequired() || record.nextCheckAt() == null) return record; // 已转人工或等待人工查询
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (record.nextCheckAt().isAfter(now)) return record; // 未到期
        int attempts = record.reconcileAttempts() + 1;
        ChannelRow latest = channel.queryForReconcile(row.tradeNo());
        if (!MockChannelService.UNKNOWN.equals(latest.result())) {
            channel.markReconcileProgress(row.tradeNo(), attempts, null, false);
            return latest;
        }
        boolean exhausted = attempts >= MAX_AUTO_RECONCILE;
        channel.markReconcileProgress(row.tradeNo(), attempts,
                exhausted ? null : now.plusSeconds(10), exhausted);
        if (exhausted) {
            log.warn("UNKNOWN 自动核对次数耗尽，转人工核对，不得改判失败: tradeNo={}", row.tradeNo());
        }
        return latest;
    }
}
