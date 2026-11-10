package com.digitalbank.common.auth;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;

/** 供 S2/S3 在查出资源所有者后执行对象级授权。 */
public final class AccessService {
    public static final String IDENTITY_ATTRIBUTE = AccessService.class.getName() + ".identity";

    public SessionIdentity currentIdentity(HttpServletRequest request) {
        Object identity = request.getAttribute(IDENTITY_ATTRIBUTE);
        if (identity instanceof SessionIdentity sessionIdentity) {
            return sessionIdentity;
        }
        throw AuthTokens.unauthenticated();
    }

    public void requireOwner(HttpServletRequest request, String ownerCustomerId) {
        SessionIdentity identity = currentIdentity(request);
        if (!identity.customerId().equals(ownerCustomerId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该资源");
        }
    }
}
