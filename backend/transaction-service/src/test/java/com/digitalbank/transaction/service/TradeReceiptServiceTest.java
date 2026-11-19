package com.digitalbank.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.transaction.client.AccountClient;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import com.digitalbank.transaction.dto.TransactionDtos.ReceiptView;
import com.digitalbank.transaction.security.Actor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 回单下载（UC-TR-07）：仅成功交易、本人或范围内后台、审计留痕、重复下载内容一致。 */
@SpringBootTest
@ActiveProfiles("test")
class TradeReceiptServiceTest {
    private static final String OWNER = "identity-owner";
    private static final String OTHER = "identity-other";
    private static final String ACCOUNT = "ACC_RCPT";
    private static final String TRACE = "trace-receipt";

    @Autowired
    private TradeQueryService service;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private AccountClient account;

    private final Actor owner = new Actor(OWNER, "c-owner", Set.of(), Set.of());
    private final Actor other = new Actor(OTHER, "c-other", Set.of(), Set.of());
    private final Actor scopedOps = new Actor("ops", "c-ops", Set.of("OPERATIONS"), Set.of(ACCOUNT));
    private final Actor outScopeOps = new Actor("ops2", "c-ops2", Set.of("OPERATIONS"), Set.of("ACC_OTHER"));

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("DELETE FROM account");
        jdbc.update("MERGE INTO account (account_id, status) KEY (account_id) VALUES (?, 'ACTIVE')", ACCOUNT);
        jdbc.update("MERGE INTO account (account_id, status) KEY (account_id) VALUES (?, 'ACTIVE')", "ACC_OTHER");
        when(account.outcome(any(), any())).thenAnswer(invocation -> {
            String tradeNo = invocation.getArgument(0);
            List<String> statuses = jdbc.queryForList(
                    "SELECT status FROM `transaction` WHERE trade_no = ?", String.class, tradeNo);
            return new Outcome(tradeNo, statuses.isEmpty() ? "UNKNOWN" : statuses.getFirst(), "NONE", null);
        });
    }

    private void seed(String tradeNo, String status) {
        jdbc.update("MERGE INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash, "
                        + "account_id, amount, currency, status, channel_evidence_id, completed_at) KEY (trade_no) "
                        + "VALUES (?, ?, 'DEPOSIT', ?, ?, ?, 88.50, 'CNY', ?, 'EVD-1', '2026-11-19 10:00:00')",
                tradeNo, OWNER, "req-" + tradeNo, "h-" + tradeNo, ACCOUNT, status);
    }

    @Test
    void ownerDownloadsSuccessfulTradeReceiptWithAudit() {
        seed("TRD-R1", "SUCCESS");
        ReceiptView view = service.receipt(owner, "TRD-R1", "打印回单", TRACE);
        assertEquals("RCPT-TRD-R1", view.receiptNo());
        assertEquals("SUCCESS", view.status());
        assertEquals(new BigDecimal("88.50").toPlainString(), new BigDecimal(view.amount()).toPlainString());
        assertEquals("EVD-1", view.channelEvidenceId());
        assertNotNull(view.issuedAt());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'RECEIPT_DOWNLOAD' AND trade_no = 'TRD-R1'",
                Integer.class));
    }

    @Test
    void repeatedDownloadHasDeterministicContent() {
        seed("TRD-R2", "SUCCESS");
        ReceiptView first = service.receipt(owner, "TRD-R2", null, TRACE);
        ReceiptView second = service.receipt(owner, "TRD-R2", null, TRACE);
        assertEquals(first.receiptNo(), second.receiptNo());
        assertEquals(first.amount(), second.amount());
        assertEquals(first.status(), second.status());
    }

    @Test
    void nonSuccessTradeIsRejected() {
        seed("TRD-R3", "PROCESSING");
        BizException rejected = assertThrows(BizException.class,
                () -> service.receipt(owner, "TRD-R3", null, TRACE));
        assertEquals(ErrorCode.REQUEST_CONFLICT, rejected.getErrorCode());
    }

    @Test
    void scopedOperatorCanDownloadButOtherAndOutOfScopeCannot() {
        seed("TRD-R4", "SUCCESS");
        ReceiptView view = service.receipt(scopedOps, "TRD-R4", "运营核查", TRACE);
        assertEquals("TRD-R4", view.tradeNo());
        assertEquals(ErrorCode.TRADE_NOT_FOUND,
                assertThrows(BizException.class, () -> service.receipt(other, "TRD-R4", null, TRACE)).getErrorCode());
        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(BizException.class, () -> service.receipt(outScopeOps, "TRD-R4", "越权", TRACE))
                        .getErrorCode());
    }

    @Test
    void missingTradeReturnsNotFound() {
        assertEquals(ErrorCode.TRADE_NOT_FOUND,
                assertThrows(BizException.class, () -> service.receipt(owner, "TRD-MISSING", null, TRACE))
                        .getErrorCode());
    }
}
