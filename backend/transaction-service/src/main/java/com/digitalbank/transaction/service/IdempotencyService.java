package com.digitalbank.transaction.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 交易受理幂等：三元组 (identityId, businessType, requestId) 认领原单，
 * 唯一索引 uk_transaction_request 兜底；同键同内容返回原单，同键异内容返回 409（交接文档 7.1）。
 * S3 只写 transaction 的原单字段，推进字段一律由 S2 更新。
 */
@Service
public class IdempotencyService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final RowMapper<TradeRow> ROW_MAPPER = (row, index) -> new TradeRow(
            row.getString("trade_no"),
            row.getString("identity_id"),
            row.getString("business_type"),
            row.getString("status"),
            row.getString("account_id"),
            row.getBigDecimal("amount"),
            row.getString("currency"),
            row.getString("failure_reason"));

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Claim(String tradeNo, boolean isNew, String status) { }

    public record TradeRow(String tradeNo, String identityId, String businessType, String status,
            String accountId, BigDecimal amount, String currency, String failureReason) { }

    @Transactional
    public Claim claim(String identityId, String businessType, String requestId, String requestHash,
            String accountId, BigDecimal amount, String currency) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String tradeNo = newTradeNo();
            try {
                jdbc.update("INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, "
                                + "request_hash, account_id, amount, currency, status) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED')",
                        tradeNo, identityId, businessType, requestId, requestHash, accountId, amount, currency);
                return new Claim(tradeNo, true, "ACCEPTED");
            } catch (DuplicateKeyException duplicate) {
                List<TradeRow> existing = jdbc.query(
                        "SELECT trade_no, identity_id, business_type, status, account_id, amount, currency, "
                                + "failure_reason FROM `transaction` "
                                + "WHERE identity_id = ? AND business_type = ? AND request_id = ?",
                        ROW_MAPPER, identityId, businessType, requestId);
                if (existing.isEmpty()) continue; // 撞的是 trade_no 唯一键，换号重试
                TradeRow row = existing.getFirst();
                String storedHash = jdbc.queryForObject(
                        "SELECT request_hash FROM `transaction` WHERE trade_no = ?", String.class, row.tradeNo());
                if (!storedHash.equals(requestHash)) {
                    throw new BizException(ErrorCode.REQUEST_CONFLICT, "同一请求号的内容与原交易不一致");
                }
                return new Claim(row.tradeNo(), false, row.status());
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR, "交易号生成冲突，请重试");
    }

    public TradeRow readTrade(String tradeNo) {
        List<TradeRow> rows = jdbc.query(
                "SELECT trade_no, identity_id, business_type, status, account_id, amount, currency, failure_reason "
                        + "FROM `transaction` WHERE trade_no = ?", ROW_MAPPER, tradeNo);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String newTradeNo() {
        return "TRD" + LocalDateTime.now(ZONE).format(DAY)
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
