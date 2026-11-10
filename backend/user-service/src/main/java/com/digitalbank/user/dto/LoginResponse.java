package com.digitalbank.user.dto;

import com.digitalbank.common.auth.SessionIdentity;
import java.time.Instant;

public record LoginResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        SessionIdentity customer
) {
    @Override
    public String toString() {
        return "LoginResponse[token=redacted, expiresAt=" + expiresAt + "]";
    }
}
