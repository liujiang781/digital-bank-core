package com.digitalbank.user.service;

import com.digitalbank.common.exception.BizException;
import com.digitalbank.user.domain.LoginCredential;
import com.digitalbank.user.dto.LoginRequest;
import com.digitalbank.user.repository.CredentialRepository;
import com.digitalbank.user.repository.InMemorySessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;

class SessionServiceTest {
    private final Map<String, LoginCredential> users = new HashMap<>();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private MutableClock clock;
    private SessionService service;
    private String passwordHash;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        passwordHash = encoder.encode("test-password");
        users.put("user-1", credential("NORMAL", "NORMAL", passwordHash));
        CredentialRepository repository = identity -> Optional.ofNullable(users.get(identity));
        var store = new InMemorySessionStore(clock, Duration.ofSeconds(10), 2);
        service = new SessionService(repository, store, encoder);
    }

    @Test
    void sessionExpiresAtExactDeadline() {
        String token = login();
        clock.now = clock.now.plusSeconds(9);
        assertThat(service.verify(token, "trace").identityId()).isEqualTo("user-1");
        clock.now = clock.now.plusSeconds(1);
        assertUnauthorized(token);
    }

    @Test
    void customerOrCredentialLockInvalidatesExistingSession() {
        String token = login();
        users.put("user-1", credential("FROZEN", "NORMAL", passwordHash));
        assertUnauthorized(token);
        users.put("user-1", credential("NORMAL", "NORMAL", passwordHash));
        String second = login();
        users.put("user-1", credential("NORMAL", "LOCKED", passwordHash));
        assertUnauthorized(second);
    }

    @Test
    void passwordChangeAndDeletedUserInvalidateExistingSession() {
        String token = login();
        users.put("user-1", credential("NORMAL", "NORMAL", encoder.encode("new-password")));
        assertUnauthorized(token);
        users.put("user-1", credential("NORMAL", "NORMAL", passwordHash));
        String second = login();
        users.clear();
        assertUnauthorized(second);
    }

    @Test
    void tokensAreDistinctAndRevocationOnlyAffectsSelectedSession() {
        String first = login();
        String second = login();
        assertThat(first).isNotEqualTo(second);
        service.logout(first);
        assertUnauthorized(first);
        assertThat(service.verify(second, "trace").identityId()).isEqualTo("user-1");
    }

    @Test
    void capacityIsBoundedAndExpiredEntriesAreReclaimed() {
        login();
        login();
        assertThatThrownBy(this::login).isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode().status()).isEqualTo(503));
        clock.now = clock.now.plusSeconds(10);
        assertThat(login()).isNotBlank();
    }

    @Test
    void disabledCredentialCannotLogin() {
        users.put("user-1", credential("NORMAL", "LOCKED", passwordHash));
        assertThatThrownBy(this::login).isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode().status()).isEqualTo(401));
    }

    private String login() {
        return service.login(new LoginRequest("user-1", "test-password")).token();
    }

    private void assertUnauthorized(String token) {
        assertThatThrownBy(() -> service.verify(token, "trace")).isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode().status()).isEqualTo(401));
    }

    private LoginCredential credential(String customerStatus, String credentialStatus, String hash) {
        return new LoginCredential("customer-1", "user-1", hash, customerStatus, credentialStatus);
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-10T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
