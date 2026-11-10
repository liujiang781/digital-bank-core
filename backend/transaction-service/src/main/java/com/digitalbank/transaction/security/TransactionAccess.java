package com.digitalbank.transaction.security;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.stereotype.Component;

/** Adapter for the authenticated servlet principal. Direct identity headers are deliberately ignored. */
@Component
public class TransactionAccess {
    public Actor requireActor(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "请先登录或重新认证");
        }
        return new Actor(principal.getName());
    }

    /** 非本人交易一律按不存在返回，避免枚举他人交易号。 */
    public void requireOwner(Actor actor, String identityId) {
        if (!actor.identityId().equals(identityId)) {
            throw new BizException(ErrorCode.TRADE_NOT_FOUND, "交易不存在");
        }
    }
}
