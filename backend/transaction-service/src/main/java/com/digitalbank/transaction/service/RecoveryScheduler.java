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
 * 非终态原单恢复扫描（交接文档 7.1/8）：
 * 进程启动后以及每 10 秒扫描一次，沿原 tradeNo 从未完成步骤恢复。
 * ACCEPTED 继续风控与账户侧受理；PROCESSING 先查资金结果再复用唯一渠道记录；
 * UNKNOWN 只在 next_check_at 到期且未转人工时自动核对，最多 6 次，次数持久化、不因重启重置。
 * 恢复任务按 tradeNo 串行处理（单线程扫描，单笔失败不影响后续）。
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

    public RecoveryScheduler(JdbcTemplate jdbc, AccountClient account, MockChannelService channel,
            DepositService depositService) {
        this.jdbc = jdbc;
        this.account = account;
        this.channel = channel;
        this.depositService = depositService;
    }

    @Scheduled(fixedDelayString = "${recovery.interval-millis:10000}",
            initialDelayString = "${recovery.initial-delay-millis:10000}")
    public void recover() {
        List<IdempotencyService.TradeRow> rows = jdbc.query(
                "SELECT trade_no, identity_id, business_type, status, account_id, amount, currency, failure_reason "
                        + "FROM `transaction` WHERE status IN ('ACCEPTED', 'PROCESSING', 'UNKNOWN') ORDER BY trade_no",
                (rs, index) -> new IdempotencyService.TradeRow(rs.getString("trade_no"),
                        rs.getString("identity_id"), rs.getString("business_type"), rs.getString("status"),
                        rs.getString("account_id"), rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getString("failure_reason")));
        for (IdempotencyService.TradeRow row : rows) {
            try {
                switch (row.status()) {
                    case "ACCEPTED" -> depositService.resume(row, null);
                    case "PROCESSING" -> resumeProcessing(row);
                    case "UNKNOWN" -> reconcileUnknown(row);
                    default -> { }
                }
            } catch (Exception e) {
                log.warn("恢复扫描单笔失败，下一轮继续: tradeNo={}", row.tradeNo(), e);
            }
        }
    }

    private void resumeProcessing(IdempotencyService.TradeRow row) {
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

    private void reconcileUnknown(IdempotencyService.TradeRow row) {
        ChannelRow record = channel.find(row.tradeNo());
        if (record == null) {
            record = channel.createOrGet(row.tradeNo(), row.accountId(), row.amount(), row.currency());
        }
        if (record.manualReviewRequired() || record.nextCheckAt() == null) return; // 已转人工或等待人工查询
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (record.nextCheckAt().isAfter(now)) return; // 未到期
        int attempts = record.reconcileAttempts() + 1;
        ChannelRow latest = channel.queryForReconcile(row.tradeNo());
        if (!MockChannelService.UNKNOWN.equals(latest.result())) {
            channel.markReconcileProgress(row.tradeNo(), attempts, null, false);
            depositService.reportChannelResult(row.tradeNo(), row.accountId(), row.amount(), latest, null);
            return;
        }
        boolean exhausted = attempts >= MAX_AUTO_RECONCILE;
        channel.markReconcileProgress(row.tradeNo(), attempts,
                exhausted ? null : now.plusSeconds(10), exhausted);
        if (exhausted) {
            log.warn("UNKNOWN 自动核对次数耗尽，转人工核对，不得改判失败: tradeNo={}", row.tradeNo());
        }
    }
}
