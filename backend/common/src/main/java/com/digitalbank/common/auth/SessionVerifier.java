package com.digitalbank.common.auth;

public interface SessionVerifier {
    SessionIdentity verify(String token, String traceId);
}
