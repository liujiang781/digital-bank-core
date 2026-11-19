package com.digitalbank.transaction.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void parsesTwoDecimalPositiveAmounts() {
        assertEquals(new BigDecimal("100.00"), Money.parsePositive("100.00"));
        assertEquals(new BigDecimal("0.01"), Money.parsePositive("0.01"));
        assertEquals("50000.00", Money.format(new BigDecimal("50000")));
    }

    @Test
    void rejectsMalformedAmounts() {
        assertThrows(BizException.class, () -> Money.parsePositive(null));
        assertThrows(BizException.class, () -> Money.parsePositive("100"));
        assertThrows(BizException.class, () -> Money.parsePositive("100.0"));
        assertThrows(BizException.class, () -> Money.parsePositive("100.000"));
        assertThrows(BizException.class, () -> Money.parsePositive("-1.00"));
        assertThrows(BizException.class, () -> Money.parsePositive("0.00"));
        assertThrows(BizException.class, () -> Money.parsePositive("abc"));
    }

    @Test
    void invalidAmountUsesInvalidArgumentCode() {
        BizException exception = assertThrows(BizException.class, () -> Money.parsePositive("1.5"));
        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
    }
}
