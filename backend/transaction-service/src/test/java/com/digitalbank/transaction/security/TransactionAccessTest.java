package com.digitalbank.transaction.security;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionAccessTest {
    @Test
    void administratorCannotEnterTransactionBusinessFunctions() {
        var request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "admin-1");
        request.addUserRole("ADMIN");
        assertThatThrownBy(() -> new TransactionAccess().requireActor(request))
                .isInstanceOfSatisfying(BizException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }
}
