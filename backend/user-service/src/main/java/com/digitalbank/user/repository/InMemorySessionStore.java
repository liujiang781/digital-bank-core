package com.digitalbank.user.repository;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.user.domain.LoginCredential;
import com.digitalbank.user.dto.LoginResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

@Repository
public class InMemorySessionStore {
    private static final int TOKEN_BYTES = 32;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration ttl;
    private final int maxSessions;

    public InMemorySessionStore(Clock clock,
            @Value("${auth.session-ttl:30m}") Duration ttl,
            @Value("${auth.max-sessions:10000}") int maxSessions) {
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("会话有效期必须大于零且不超过一天");
        }
        if (maxSessions < 1) {
            throw new IllegalArgumentException("会话容量必须为正数");
        }
        this.clock = clock;
        this.ttl = ttl;
        this.maxSessions = maxSessions;
    }

    public synchronized LoginResponse issue(LoginCredential credential) {
        removeExpired();
        if (sessions.size() >= maxSessions) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "当前登录会话过多，请稍后重试");
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = clock.instant().plus(ttl);
        Session session = new Session(credential.identity(), credential.passwordHash(), expiresAt);
        // 服务端只保留令牌的 SHA-256 指纹，原始令牌只在登录响应中返回。
        sessions.put(hash(token), session);
        return new LoginResponse(token, "Bearer", expiresAt, credential.identity());
    }

    public Session require(String token) {
        String key = hash(token);
        Session session = sessions.get(key);
        if (session == null) {
            throw AuthTokens.unauthenticated();
        }
        if (!clock.instant().isBefore(session.expiresAt())) {
            sessions.remove(key, session);
            throw AuthTokens.unauthenticated();
        }
        return session;
    }

    public void revoke(String token) {
        sessions.remove(hash(token));
    }

    @Scheduled(fixedDelay = 60000)
    public void removeExpired() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }

    public record Session(SessionIdentity identity, String passwordHash, Instant expiresAt) {
        @Override
        public String toString() {
            return "Session[redacted]";
        }
    }
}
