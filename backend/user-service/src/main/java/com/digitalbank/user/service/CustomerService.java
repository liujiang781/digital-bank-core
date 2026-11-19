package com.digitalbank.user.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.user.dto.UserDtos.*;
import com.digitalbank.user.repository.UserManagementRepository;
import com.digitalbank.user.support.UserInputs;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {
    private final UserManagementRepository repository;
    private final PasswordEncoder encoder;
    private final StaffAuthorizationService authorization;

    public CustomerService(ObjectProvider<UserManagementRepository> repository, PasswordEncoder encoder,
                           StaffAuthorizationService authorization) {
        this.repository = repository.getIfAvailable();
        this.encoder = encoder;
        this.authorization = authorization;
    }

    public RegisterResult register(RegisterRequest request) {
        UserManagementRepository store = store();
        String name = request.realName().trim();
        int passwordBytes = request.password().getBytes(StandardCharsets.UTF_8).length;
        if (name.length() < 2 || request.password().isBlank() || passwordBytes > 72) {
            throw UserInputs.invalid("姓名或密码格式不合法，密码不得超过 72 个 UTF-8 字节");
        }
        if (authorization.reservedIdentity(request.identityId())) {
            // 防止匿名用户抢注配置中的管理员/员工身份而获得预置权限。
            throw UserManagementRepository.conflict();
        }
        // 不存储密码的无盐摘要；重放时用已存 BCrypt 核对密码是否相同。
        RegisterResult result = store.command(request.identityId(), "REGISTER", request.requestId(),
                List.of(request.identityId(), name), RegisterResult.class, () -> {
                    if (store.customerExists(request.identityId())) throw UserManagementRepository.conflict();
                    return store.register(request.identityId(), name, encoder.encode(request.password()));
                });
        if (!encoder.matches(request.password(), store.passwordHash(request.identityId()))) {
            throw UserManagementRepository.conflict();
        }
        return result;
    }

    public Profile me(SessionIdentity actor) {
        requireCustomer(actor);
        return store().profile(actor.customerId(), actor.identityId());
    }

    public Profile update(SessionIdentity actor, ProfileRequest request) {
        requireCustomer(actor);
        UserManagementRepository store = store();
        return store.command(actor.identityId(), "PROFILE_UPDATE", request.requestId(),
                java.util.Arrays.asList(request.phone(), request.email()), Profile.class,
                () -> store.updateProfile(actor.customerId(), actor.identityId(), request.phone(), request.email()));
    }

    public Page<CustomerSummary> customers(SessionIdentity actor, String status, String reason, int page, int pageSize) {
        if (!actor.roles().contains("OPERATIONS") || actor.accountScope().isEmpty()) throw forbidden();
        if (page < 1 || pageSize < 1 || pageSize > 100) throw UserInputs.invalid("分页参数不合法");
        if (status != null && !Set.of("NORMAL", "FROZEN", "CANCELLED").contains(status)) {
            throw UserInputs.invalid("客户状态不合法");
        }
        if (reason == null || reason.isBlank() || reason.length() > 255) throw UserInputs.invalid("请填写查询原因");
        var result = store().customers(actor.accountScope(), status, page, pageSize);
        store().audit(actor.identityId(), "CUSTOMER_LIST", "customer_scope", null, reason.trim());
        return result;
    }

    private void requireCustomer(SessionIdentity actor) {
        if (actor.roles().contains("ADMIN")) throw forbidden();
    }

    private UserManagementRepository store() {
        if (repository == null) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "该功能需要用户服务使用 MySQL 凭据模式并执行 S1 增量脚本");
        }
        return repository;
    }

    private BizException forbidden() {
        return new BizException(ErrorCode.FORBIDDEN, "没有此功能或数据范围的权限");
    }
}
