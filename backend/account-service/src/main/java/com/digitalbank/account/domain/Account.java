package com.digitalbank.account.domain;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** A single database snapshot. Available balance is derived, never independently stored. */
public record Account(String accountId, String customerId, String currency, String status,
        BigDecimal bookBalance, BigDecimal reservedAmount, BigDecimal personalSingleLimit,
        BigDecimal personalDailyLimit, LocalDateTime createdAt, int version) {

    public BigDecimal availableBalance() {
        return bookBalance.subtract(reservedAmount);
    }

    public static Account fromRow(ResultSet rs, int row) throws SQLException {
        return new Account(rs.getString("account_id"), rs.getString("customer_id"),
                rs.getString("currency"), rs.getString("status"), rs.getBigDecimal("book_balance"),
                rs.getBigDecimal("reserved_amount"), rs.getBigDecimal("personal_single_limit"),
                rs.getBigDecimal("personal_daily_limit"),
                rs.getObject("created_at", LocalDateTime.class), rs.getInt("version"));
    }
}
