package com.digitalbank.risk.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Marks rapid outgoing activity after account acceptance, independently of settlement. */
@Service
public class OutgoingFrequencyMonitor {
    private static final String RULE = "RK-06";
    private static final ZoneId BANK_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String OUTGOING_TYPES = "('WITHDRAWAL', 'INTERNAL_TRANSFER', 'EXTERNAL_TRANSFER')";

    private final JdbcTemplate jdbc;
    private final int threshold;
    private final int windowSeconds;

    public OutgoingFrequencyMonitor(JdbcTemplate jdbc,
            @Value("${risk.frequency.threshold:5}") int threshold,
            @Value("${risk.frequency.window-seconds:60}") int windowSeconds) {
        if (threshold < 2 || windowSeconds < 1 || windowSeconds > 86400) {
            throw new IllegalArgumentException("Frequency threshold must be at least 2 and the window must be 1–86400 seconds");
        }
        this.jdbc = jdbc;
        this.threshold = threshold;
        this.windowSeconds = windowSeconds;
    }

    @Scheduled(initialDelayString = "${risk.frequency.poll-ms:5000}",
            fixedDelayString = "${risk.frequency.poll-ms:5000}")
    public void scan() {
        scan(LocalDateTime.now(BANK_ZONE));
    }

    void scan(LocalDateTime now) {
        var candidates = jdbc.query(
                "SELECT t.trade_no, r.rule_version FROM `transaction` t "
                        + "JOIN risk_rule r ON r.rule_id = ? AND r.enabled = 1 "
                        + "WHERE t.accepted_at >= ? AND t.accepted_at <= ? AND t.business_type IN " + OUTGOING_TYPES
                        + " AND NOT EXISTS (SELECT 1 FROM risk_event e WHERE e.trade_no = t.trade_no AND e.rule_id = ?) "
                        + "AND (SELECT COUNT(*) FROM `transaction` prior WHERE prior.account_id = t.account_id "
                        + "AND prior.business_type IN " + OUTGOING_TYPES
                        + " AND prior.accepted_at >= TIMESTAMPADD(SECOND, ?, t.accepted_at) "
                        + "AND (prior.accepted_at < t.accepted_at OR (prior.accepted_at = t.accepted_at AND prior.id <= t.id))) >= ? "
                        + "ORDER BY t.accepted_at, t.id LIMIT 200",
                (rs, n) -> new Candidate(rs.getString("trade_no"), rs.getInt("rule_version")),
                RULE, now.minusDays(1), now, RULE, -windowSeconds, threshold);
        for (Candidate candidate : candidates) {
            mark(candidate);
        }
    }

    private void mark(Candidate candidate) {
        String eventId = UUID.nameUUIDFromBytes((RULE + ":" + candidate.tradeNo())
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        try {
            jdbc.update("INSERT INTO risk_event (event_id, trade_no, rule_id, rule_version, operator_identity_id, "
                            + "reason_code, result) VALUES (?, ?, ?, ?, 'SYSTEM', 'HIGH_FREQUENCY', 'MARK')",
                    eventId, candidate.tradeNo(), RULE, candidate.ruleVersion());
        } catch (DuplicateKeyException alreadyMarked) {
            // Multiple service instances may scan the same accepted transaction concurrently.
        }
    }

    private record Candidate(String tradeNo, int ruleVersion) { }
}
