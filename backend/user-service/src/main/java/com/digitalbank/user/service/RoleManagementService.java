package com.digitalbank.user.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.user.dto.UserDtos.*;
import com.digitalbank.user.repository.UserManagementRepository;
import com.digitalbank.user.support.UserInputs;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class RoleManagementService {
    private final UserManagementRepository repository;
    private final StaffAuthorizationService authorization;

    public RoleManagementService(ObjectProvider<UserManagementRepository> repository,
                                 StaffAuthorizationService authorization) {
        this.repository = repository.getIfAvailable();
        this.authorization = authorization;
    }

    public Authorization get(SessionIdentity actor, String identityId) {
        requireAdmin(actor);
        if (!store().customerExists(identityId)) throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND, "身份不存在");
        return authorization.current(identityId);
    }

    public Authorization replace(SessionIdentity actor, String roleId, RoleRequest request) {
        requireAdmin(actor);
        if (!Set.of("OPERATIONS", "RISK").contains(roleId)) {
            throw UserInputs.invalid("在线授权仅支持 OPERATIONS / RISK，管理员由服务端配置");
        }
        if (request.reason().isBlank() || request.accountScope().stream().anyMatch(id -> id.contains("*"))) {
            throw UserInputs.invalid("原因不能为空，账户范围不能使用通配符");
        }
        if (!request.enabled() && !request.accountScope().isEmpty()) {
            throw UserInputs.invalid("撤销授权时账户范围必须为空");
        }
        if (actor.identityId().equals(request.identityId())
                || authorization.current(request.identityId()).roles().contains("ADMIN")) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能通过业务接口修改管理员授权");
        }
        UserManagementRepository store = store();
        return store.command(actor.identityId(), "ROLE_ASSIGN", request.requestId(),
                List.of(roleId, request.identityId(), request.accountScope().stream().sorted().toList(),
                        request.enabled(), request.reason()), Authorization.class, () -> {
                    boolean exists = request.enabled() ? store.usableCustomerExists(request.identityId())
                            : store.customerExists(request.identityId());
                    if (!exists) {
                        throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND, "目标身份不存在或不可用");
                    }
                    Authorization result = store.replaceAuthorization(request.identityId(),
                            request.enabled() ? roleId : null, request.accountScope());
                    store.audit(actor.identityId(), "ROLE_ASSIGN", "authorization", request.identityId(),
                            request.reason().trim());
                    return result;
                });
    }

    private void requireAdmin(SessionIdentity actor) {
        if (!actor.roles().equals(Set.of("ADMIN"))) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅系统管理员可以管理角色和授权范围");
        }
    }

    private UserManagementRepository store() {
        if (repository == null) throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "在线授权管理需要 MySQL 用户存储");
        return repository;
    }
}
