package com.digitalbank.user.domain;

import com.digitalbank.common.auth.SessionIdentity;

public record LoginCredential(
        String customerId,
        String identityId,
        String passwordHash,
        String customerStatus,
        String credentialStatus
) {
    public boolean isUsable() {
        return "NORMAL".equals(customerStatus) && "NORMAL".equals(credentialStatus);
    }

    public SessionIdentity identity() {
        return new SessionIdentity(customerId, identityId);
    }

    @Override
    public String toString() {
        return "LoginCredential[redacted]";
    }
}
