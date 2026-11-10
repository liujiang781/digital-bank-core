package com.digitalbank.transaction.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class FingerprintsTest {
    @Test
    void producesSha256HexOfCompactJsonArray() throws Exception {
        // 交接文档 7.1 存款示例: ["DEPOSIT","ACC_S2_001","","","100.00","CNY",""]
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("[\"DEPOSIT\",\"ACC_S2_001\",\"\",\"\",\"100.00\",\"CNY\",\"\"]"
                        .getBytes(StandardCharsets.UTF_8)));
        String actual = Fingerprints.requestHash("DEPOSIT", "ACC_S2_001", "", "", "100.00", "CNY", "");
        assertEquals(expected, actual);
        assertEquals(64, actual.length());
    }

    @Test
    void nullFieldsBehaveLikeEmptyStringsAndPurposeIsTrimmed() {
        String withNulls = Fingerprints.requestHash("DEPOSIT", "ACC_S2_001", null, null, "100.00", "CNY", null);
        String withEmpty = Fingerprints.requestHash("DEPOSIT", "ACC_S2_001", "", "", "100.00", "CNY", "");
        assertEquals(withEmpty, withNulls);
        assertEquals(withEmpty, Fingerprints.requestHash("DEPOSIT", "ACC_S2_001", "", "", "100.00", "CNY", "  "));
    }

    @Test
    void differentAmountChangesHash() {
        String base = Fingerprints.requestHash("DEPOSIT", "ACC_S2_001", "", "", "100.00", "CNY", "");
        assertNotEquals(base, Fingerprints.requestHash("DEPOSIT", "ACC_S2_001", "", "", "200.00", "CNY", ""));
    }
}
