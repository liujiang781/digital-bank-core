package com.digitalbank.transaction.security;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.stereotype.Component;

/** Adapter for the authenticated servlet principal. Direct identity headers are deliberately ignored. */
@Component
public class TransactionAccess {
    public Actor requireActor(HttpServletRequest request) {
        if (request.isUserInRole("ADMIN")) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员不能访问交易业务数据");
        }
        Principal principal = request.getUserPrincipal();
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "请先登录或重新认证");
        }
        // S1 过滤器在核验 Bearer 会话后提供类型化身份；不接受客户端角色或范围头。
        if (principal instanceof SessionIdentity identity) {
            return new Actor(identity);
        }
        return new Actor(principal.getName());
    }

    /** 非本人交易一律按不存在返回，避免枚举他人交易号。 */
    public void requireOwner(Actor actor, String identityId) {
        if (!actor.identityId().equals(identityId)) {
            throw new BizException(ErrorCode.TRADE_NOT_FOUND, "交易不存在");
        }
    }

    /** 运营/风控后台接口授权：角色 + 对象级账户范围必须同时满足，管理员一律拒绝。 */
    public void requireOperator(Actor actor, String accountId, boolean allowRisk) {
        boolean roleAllowed = actor.roles().contains("OPERATIONS")
                || (allowRisk && actor.roles().contains("RISK"));
        if (!roleAllowed || accountId == null || !actor.accountScope().contains(accountId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "没有此账户的操作权限");
        }
    }

    /** 角色级校验（用于按范围做列表查询），要求非空账户范围，避免仅凭角色访问全量。 */
    public void requireBackOfficeRole(Actor actor, boolean allowRisk) {
        boolean roleAllowed = actor.roles().contains("OPERATIONS")
                || (allowRisk && actor.roles().contains("RISK"));
        if (!roleAllowed || actor.accountScope().isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN, "没有此功能或数据范围的权限");
        }
    }

    public boolean isBackOffice(Actor actor) {
        return actor.roles().contains("OPERATIONS") || actor.roles().contains("RISK");
    }
}
