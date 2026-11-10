package com.digitalbank.user.repository;

import com.digitalbank.user.domain.LoginCredential;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 仅用于显式启用的本地联调；不提供默认密码，不替代 MySQL 持久化。 */
public final class DemoCredentialRepository implements CredentialRepository {
    private final Map<String, LoginCredential> credentials;

    public DemoCredentialRepository(boolean enabled, String identityId, String password,
            PasswordEncoder passwordEncoder) {
        if (!enabled) {
            credentials = Map.of();
            return;
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (identityId.isBlank() || identityId.length() > 64
                || password.isBlank() || passwordBytes < 12 || passwordBytes > 72) {
            throw new IllegalArgumentException("演示用户需要有效身份标识和 12—72 字节密码");
        }
        LoginCredential credential = new LoginCredential(
                "DEMO-CUSTOMER-001", identityId, passwordEncoder.encode(password), "NORMAL", "NORMAL");
        credentials = Map.of(identityId, credential);
    }

    @Override
    public Optional<LoginCredential> findByIdentityId(String identityId) {
        return Optional.ofNullable(credentials.get(identityId));
    }
}
