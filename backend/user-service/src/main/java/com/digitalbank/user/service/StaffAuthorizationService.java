package com.digitalbank.user.service;

import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.user.config.StaffAuthorizationProperties;
import com.digitalbank.user.repository.UserManagementRepository;
import com.digitalbank.user.dto.UserDtos.Authorization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import org.springframework.stereotype.Service;

@Service
public final class StaffAuthorizationService {
    private final StaffAuthorizationProperties properties;
    private UserManagementRepository repository;

    public StaffAuthorizationService(StaffAuthorizationProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public StaffAuthorizationService(StaffAuthorizationProperties properties,
                                     ObjectProvider<UserManagementRepository> repository) {
        this(properties);
        this.repository = repository.getIfAvailable();
    }

    public Authorization current(String identityId) {
        if (repository != null) {
            try {
                Authorization stored = repository.authorization(identityId);
                if (stored != null) return stored;
            } catch (DataAccessException failure) {
                // 过滤器调用发生在 ControllerAdvice 之外，必须转换为可安全返回的业务异常。
                throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "授权存储暂时不可用");
            }
        }
        var grant = properties.grants().get(identityId);
        return grant == null ? new Authorization(identityId, java.util.Set.of(), java.util.Set.of(), 0)
                : new Authorization(identityId, grant.roles(), grant.accountScope(), 0);
    }

    public boolean reservedIdentity(String identityId) {
        return properties.grants().keySet().stream().anyMatch(id -> id.equalsIgnoreCase(identityId))
                || (repository != null && repository.authorization(identityId) != null);
    }

    public SessionIdentity authorize(SessionIdentity identity) {
        var grant = current(identity.identityId());
        return new SessionIdentity(identity.customerId(), identity.identityId(),
                grant.roles(), grant.accountScope(), grant.authorizationVersion());
    }
}
