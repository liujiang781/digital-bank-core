package com.digitalbank.transaction.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalbank.common.exception.BizException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestIdsTest {
    private static final String UUID_TEXT = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void resolvesBodyRequestId() {
        assertEquals(UUID_TEXT, RequestIds.resolve(UUID_TEXT, null));
    }

    @Test
    void canonicalizesCaseInsensitiveUuid() {
        String upper = UUID_TEXT.toUpperCase();
        assertEquals(UUID.fromString(UUID_TEXT).toString(), RequestIds.resolve(upper, upper));
    }

    @Test
    void matchingHeaderAndBodyPass() {
        assertEquals(UUID_TEXT, RequestIds.resolve(UUID_TEXT, UUID_TEXT));
    }

    @Test
    void mismatchedHeaderAndBodyFail() {
        String other = UUID.randomUUID().toString();
        assertThrows(BizException.class, () -> RequestIds.resolve(UUID_TEXT, other));
    }

    @Test
    void missingBothFail() {
        assertThrows(BizException.class, () -> RequestIds.resolve(null, null));
    }

    @Test
    void nonUuidFails() {
        assertThrows(BizException.class, () -> RequestIds.resolve("req-001", null));
    }
}
