package com.digitalbank.transaction.channel;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 模拟渠道：成功、失败、超时三种结果可由模式控制演示。
 * channelRequestId 固定为 CHD+tradeNo，重试不生成第二个逻辑渠道请求；
 * 明确结果只能由 UNKNOWN 收敛为 SUCCESS 或 FAILED，终态结果不翻转（交接文档第 3、7 节）。
 */
@Service
public class MockChannelService {
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";
    private static final List<String> MODES = List.of(SUCCESS, FAILED, UNKNOWN);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final RowMapper<ChannelRow> ROW_MAPPER = (row, index) -> new ChannelRow(
            row.getString("trade_no"),
            row.getString("channel_request_id"),
            row.getString("account_id"),
            row.getBigDecimal("amount"),
            row.getString("currency"),
            row.getString("result"),
            row.getString("evidence_id"),
            row.getString("reason_code"),
            row.getObject("observed_at", LocalDateTime.class),
            row.getInt("reconcile_attempts"),
            row.getObject("next_check_at", LocalDateTime.class),
            row.getBoolean("manual_review_required"));

    private final JdbcTemplate jdbc;
    private final AtomicReference<String> mode;

    public MockChannelService(JdbcTemplate jdbc,
            @Value("${mock-channel.result:SUCCESS}") String initialResult) {
        this.jdbc = jdbc;
        this.mode = new AtomicReference<>(normalize(initialResult));
    }

    public String mode() {
        return mode.get();
    }

    public void setMode(String result) {
        mode.set(normalize(result));
    }

    /** 以固定 CHD+tradeNo 原子创建或读取唯一渠道记录；重复调用只查询和复用。 */
    public ChannelRow createOrGet(String tradeNo, String accountId, BigDecimal amount, String currency) {
        String result = mode.get();
        LocalDateTime now = LocalDateTime.now(ZONE);
        try {
            jdbc.update("INSERT INTO mock_channel_deposit (trade_no, channel_request_id, account_id, amount, "
                            + "currency, result, evidence_id, reason_code, observed_at, reconcile_attempts, next_check_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)",
                    tradeNo, channelRequestId(tradeNo), accountId, amount, currency, result,
                    evidenceFor(tradeNo, result), reasonFor(result),
                    "UNKNOWN".equals(result) ? null : now,
                    "UNKNOWN".equals(result) ? now.plusSeconds(10) : null);
        } catch (DuplicateKeyException alreadyExists) {
            // 已有记录：只查询和复用，不生成第二个逻辑渠道请求
        }
        ChannelRow row = find(tradeNo);
        if (row == null) throw new BizException(ErrorCode.INTERNAL_ERROR, "模拟渠道记录创建失败: " + tradeNo);
        return row;
    }

    public ChannelRow find(String tradeNo) {
        List<ChannelRow> rows = jdbc.query(
                "SELECT trade_no, channel_request_id, account_id, amount, currency, result, evidence_id, "
                        + "reason_code, observed_at, reconcile_attempts, next_check_at, manual_review_required "
                        + "FROM mock_channel_deposit WHERE trade_no = ?", ROW_MAPPER, tradeNo);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 渠道结果查询：UNKNOWN 且当前模式已明确时按条件收敛，终态不翻转。 */
    public ChannelRow queryForReconcile(String tradeNo) {
        ChannelRow row = find(tradeNo);
        if (row == null || !UNKNOWN.equals(row.result())) return row;
        String current = mode.get();
        if (UNKNOWN.equals(current)) return row;
        jdbc.update("UPDATE mock_channel_deposit SET result = ?, evidence_id = ?, reason_code = ?, observed_at = ? "
                        + "WHERE trade_no = ? AND result = 'UNKNOWN'",
                current, evidenceFor(tradeNo, current), reasonFor(current), LocalDateTime.now(ZONE), tradeNo);
        return find(tradeNo);
    }

    /** 持久化有界核对进度；扫描和进程重启都不重置已持久化次数。 */
    public void markReconcileProgress(String tradeNo, int attempts, LocalDateTime nextCheckAt, boolean manualReview) {
        jdbc.update("UPDATE mock_channel_deposit SET reconcile_attempts = ?, next_check_at = ?, "
                        + "manual_review_required = ? WHERE trade_no = ?",
                attempts, nextCheckAt, manualReview, tradeNo);
    }

    public static String channelRequestId(String tradeNo) {
        return "CHD" + tradeNo;
    }

    private static String evidenceFor(String tradeNo, String result) {
        return UNKNOWN.equals(result) ? null : "EVD" + tradeNo;
    }

    private static String reasonFor(String result) {
        if (FAILED.equals(result)) return "CHANNEL_DECLINED";
        return UNKNOWN.equals(result) ? "CHANNEL_TIMEOUT" : null;
    }

    private String normalize(String result) {
        if (result == null || !MODES.contains(result)) {
            throw new IllegalArgumentException("mock-channel.result 仅允许 SUCCESS/FAILED/UNKNOWN");
        }
        return result;
    }

    public record ChannelRow(String tradeNo, String channelRequestId, String accountId, BigDecimal amount,
            String currency, String result, String evidenceId, String reasonCode, LocalDateTime observedAt,
            int reconcileAttempts, LocalDateTime nextCheckAt, boolean manualReviewRequired) { }
}
