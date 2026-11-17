package com.digitalbank.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.digitalbank.transaction.support.Fingerprints;
import org.junit.jupiter.api.Test;

/** 出账指纹口径（交接文档 7.1）：取款/转账的 accountId 与 payerAccountId 均填付款账户，转账另绑收款账户。 */
class FingerprintContractTest {
    private static final String PAYER = "ACC_S2_001";

    @Test
    void withdrawalFillsPayerInBothAccountFields() {
        String canonical = Fingerprints.requestHash("WITHDRAWAL", PAYER, PAYER, "", "100.00", "CNY", "");
        // 字段错位（accountId 留空、payee 误填付款账户）必须产生不同指纹，防止口径漂移
        assertNotEquals(canonical,
                Fingerprints.requestHash("WITHDRAWAL", "", PAYER, PAYER, "100.00", "CNY", ""));
        assertNotEquals(canonical,
                Fingerprints.requestHash("WITHDRAWAL", PAYER, "", "", "100.00", "CNY", ""));
    }

    @Test
    void transferFingerprintBindsPayee() {
        String withPayeeB = Fingerprints.requestHash("INTERNAL_TRANSFER", PAYER, PAYER,
                "ACC_S2_002", "100.00", "CNY", "");
        assertNotEquals(withPayeeB, Fingerprints.requestHash("INTERNAL_TRANSFER", PAYER, PAYER,
                "ACC_S2_003", "100.00", "CNY", ""));
        assertNotEquals(withPayeeB, Fingerprints.requestHash("WITHDRAWAL", PAYER, PAYER,
                "", "100.00", "CNY", ""));
    }

    @Test
    void purposeIsTrimmedAndAmountStaysNormalized() {
        assertEquals(
                Fingerprints.requestHash("WITHDRAWAL", PAYER, PAYER, "", "100.00", "CNY", "房租"),
                Fingerprints.requestHash("WITHDRAWAL", PAYER, PAYER, "", "100.00", "CNY", " 房租 "));
        assertNotEquals(
                Fingerprints.requestHash("WITHDRAWAL", PAYER, PAYER, "", "100.00", "CNY", ""),
                Fingerprints.requestHash("WITHDRAWAL", PAYER, PAYER, "", "100.0", "CNY", ""));
    }
}
