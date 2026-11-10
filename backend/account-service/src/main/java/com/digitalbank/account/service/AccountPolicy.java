package com.digitalbank.account.service;

import com.digitalbank.account.domain.Account;
import com.digitalbank.account.support.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccountPolicy {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final Limits defaults;

    public AccountPolicy(JdbcTemplate jdbc,
            @Value("${account.deposit-single-limit:50000.00}") String deposit,
            @Value("${account.outgoing-single-limit:10000.00}") String single,
            @Value("${account.outgoing-daily-limit:50000.00}") String daily) {
        this.jdbc = jdbc;
        defaults = new Limits(Money.parsePositive(deposit), Money.parsePositive(single),
                Money.parsePositive(daily), 0);
    }

    public Limits limits() {
        var rows = jdbc.query("SELECT deposit_single_limit, outgoing_single_limit, outgoing_daily_limit, rule_version "
                        + "FROM risk_rule WHERE rule_type = 'LIMIT_POLICY' AND enabled = 1 "
                        + "ORDER BY rule_version DESC, id DESC LIMIT 1",
                (rs, n) -> new Limits(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getInt(4)));
        return rows.isEmpty() ? defaults : rows.getFirst();
    }

    public BigDecimal dailyUsed(String accountId) {
        LocalDate day = LocalDate.now(ZONE);
        var start = day.atStartOfDay();
        var end = day.plusDays(1).atStartOfDay();
        BigDecimal successful = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM `transaction` "
                        + "WHERE account_id = ? AND business_type IN ('WITHDRAWAL', 'INTERNAL_TRANSFER') "
                        + "AND status = 'SUCCESS' AND accepted_at >= ? AND accepted_at < ?",
                BigDecimal.class, accountId, start, end);
        BigDecimal reserved = jdbc.queryForObject("SELECT COALESCE(SUM(r.amount), 0) FROM account_reservation r "
                        + "JOIN `transaction` t ON t.trade_no = r.trade_no WHERE r.account_id = ? "
                        + "AND r.status = 'RESERVED' AND t.accepted_at >= ? AND t.accepted_at < ?",
                BigDecimal.class, accountId, start, end);
        return successful.add(reserved);
    }

    public record Limits(BigDecimal deposit, BigDecimal single, BigDecimal daily, int version) {
        public BigDecimal singleFor(Account account) {
            return account.personalSingleLimit() == null ? single : single.min(account.personalSingleLimit());
        }
        public BigDecimal dailyFor(Account account) {
            return account.personalDailyLimit() == null ? daily : daily.min(account.personalDailyLimit());
        }
    }
}
