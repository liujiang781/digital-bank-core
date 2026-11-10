package com.digitalbank.account.security;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Adapter for the authenticated servlet principal. Direct identity headers are deliberately ignored. */
@Component
public class AccountAccess {
    private final JdbcTemplate jdbc;

    public AccountAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Actor requireActor(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null) throw unauthenticated();
        if (principal instanceof Actor actor) return actor;
        if (principal.getName() == null || principal.getName().isBlank()) throw unauthenticated();

        var customers = jdbc.query("SELECT customer_id FROM customer "
                        + "WHERE identity_id = ? AND status = 'NORMAL' AND real_name_status = 'VERIFIED'",
                (row, index) -> row.getString("customer_id"), principal.getName());
        if (customers.size() != 1) throw unauthenticated();

        // Generic principals have no object scope. S1 must supply an Actor for scoped staff actions.
        Set<String> roles = request.isUserInRole("OPERATIONS") ? Set.of("OPERATIONS")
                : request.isUserInRole("RISK") ? Set.of("RISK") : Set.of();
        return new Actor(principal.getName(), customers.getFirst(), roles, Set.of());
    }

    public void requireOwner(Actor actor, String customerId) {
        if (actor.customerId() == null || !actor.customerId().equals(customerId)) {
            throw new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "账户不存在或不在授权范围内");
        }
    }

    public void requireOperator(Actor actor, String accountId, boolean allowRisk) {
        boolean roleAllowed = actor.roles().contains("OPERATIONS")
                || (allowRisk && actor.roles().contains("RISK"));
        if (!roleAllowed || accountId == null || !actor.accountScope().contains(accountId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "没有此账户的操作权限");
        }
    }

    private BizException unauthenticated() {
        return new BizException(ErrorCode.UNAUTHENTICATED, "请先登录或重新认证");
    }
}
