package com.digitalbank.account.support;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputContractTest {
    private static final String REQUEST = "a38a6d7f-7c5d-4a18-8b96-f88442285f32";

    @Test
    void amountRetainsCentsAtBothDecimalBoundaries() {
        assertThat(Money.parsePositive("0.01")).isEqualByComparingTo("0.01");
        assertThat(Money.format(Money.parsePositive("9999999999999999.99")))
                .isEqualTo("9999999999999999.99");
        assertThat(Money.format(new BigDecimal("12"))).isEqualTo("12.00");
    }

    @Test
    void invalidAmountCannotBeRoundedOrOverflowDatabasePrecision() {
        for (String value : new String[]{"0.00", "-1.00", "1.001", "1", "1.0", "1e2", "+1.00",
                "10000000000000000.00", " 1.00", "NaN"}) {
            assertInvalid(() -> Money.parsePositive(value));
        }
        assertInvalid(() -> Money.parsePositive(null));
    }

    @Test
    void repeatedRequestIdCanBeSuppliedInBodyHeaderOrBoth() {
        assertThat(RequestIds.resolve(REQUEST, null)).isEqualTo(REQUEST);
        assertThat(RequestIds.resolve(null, REQUEST)).isEqualTo(REQUEST);
        assertThat(RequestIds.resolve(REQUEST.toUpperCase(), REQUEST)).isEqualTo(REQUEST);
    }

    @Test
    void conflictingMissingOrAbbreviatedRequestIdsAreRejected() {
        assertInvalid(() -> RequestIds.resolve(REQUEST, "b38a6d7f-7c5d-4a18-8b96-f88442285f32"));
        assertInvalid(() -> RequestIds.resolve(null, null));
        assertInvalid(() -> RequestIds.resolve("1-1-1-1-1", null));
        assertInvalid(() -> RequestIds.resolve(REQUEST, ""));
    }

    private void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }
}
