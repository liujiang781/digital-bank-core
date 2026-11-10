package com.digitalbank.user.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.auth.SessionVerifier;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.user.domain.LoginCredential;
import com.digitalbank.user.dto.LoginRequest;
import com.digitalbank.user.dto.LoginResponse;
import com.digitalbank.user.repository.CredentialRepository;
import com.digitalbank.user.repository.InMemorySessionStore;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class SessionService implements SessionVerifier {
    private final CredentialRepository credentials;
    private final InMemorySessionStore sessions;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public SessionService(CredentialRepository credentials, InMemorySessionStore sessions,
            PasswordEncoder passwordEncoder) {
        this.credentials = credentials;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-never-used-for-login");
    }

    public LoginResponse login(LoginRequest request) {
        // BCrypt 按字节限制输入，中文密码不能只按 Java 字符数判断。
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "密码长度超过允许范围");
        }
        Optional<LoginCredential> candidate = findCredential(request.identityId());
        String hash = candidate.map(LoginCredential::passwordHash).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (candidate.isEmpty() || !candidate.get().isUsable() || !matches) {
            // 不区分用户不存在、密码错误或账号停用，避免泄露用户状态。
            throw new BizException(ErrorCode.UNAUTHENTICATED, "身份标识或密码错误，或用户不可用");
        }
        return sessions.issue(candidate.get());
    }

    @Override
    public SessionIdentity verify(String token, String traceId) {
        InMemorySessionStore.Session session = sessions.require(token);
        Optional<LoginCredential> candidate = findCredential(session.identity().identityId());
        boolean stillValid = candidate.filter(LoginCredential::isUsable)
                .filter(credential -> credential.identity().equals(session.identity()))
                .filter(credential -> credential.passwordHash().equals(session.passwordHash()))
                .isPresent();
        if (!stillValid) {
            // 账号被锁定/注销或密码改变后，已签发的会话也不再有效。
            sessions.revoke(token);
            throw AuthTokens.unauthenticated();
        }
        return session.identity();
    }

    public void logout(String token) {
        sessions.revoke(token);
    }

    private Optional<LoginCredential> findCredential(String identityId) {
        try {
            return credentials.findByIdentityId(identityId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "用户凭据存储暂时不可用");
        }
    }
}
