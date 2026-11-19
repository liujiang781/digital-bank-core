package com.digitalbank.risk.security;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.AccessService;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 风控对外接口授权：角色与账户范围只取服务端核验的会话，不接受客户端身份头。
 * ADMIN 一律拒绝访问风控业务数据；写操作仅 RISK，查询允许 RISK/OPERATIONS/AUDIT 只读身份。
 */
@Component
public class RiskAccess {
    private final AccessService access;

    public RiskAccess(AccessService access) {
        this.access = access;
    }

    public SessionIdentity current(HttpServletRequest request) {
        return access.currentIdentity(request);
    }

    /** 查询风险记录：风控专员、运营或审计只读身份，且必须持有非空账户范围。 */
    public SessionIdentity requireRiskReader(HttpServletRequest request) {
        SessionIdentity identity = current(request);
        if (identity.roles().contains("ADMIN")) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员不能访问风控业务数据");
        }
        boolean allowed = hasAny(identity, "RISK", "OPERATIONS", "AUDIT");
        if (!allowed || identity.accountScope().isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN, "没有此功能或数据范围的权限");
        }
        return identity;
    }

    /** 规则配置查询：风控专员、运营或审计只读；规则是全局参数，不按账户范围过滤。 */
    public SessionIdentity requireRuleReader(HttpServletRequest request) {
        SessionIdentity identity = current(request);
        if (identity.roles().contains("ADMIN")) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员不能访问风控业务数据");
        }
        if (!hasAny(identity, "RISK", "OPERATIONS", "AUDIT")) {
            throw new BizException(ErrorCode.FORBIDDEN, "没有查看风控规则的权限");
        }
        return identity;
    }

    /** 规则/名单变更提交与审批、告警处置：仅风控专员，且必须持有非空账户范围。 */
    public SessionIdentity requireRiskWriter(HttpServletRequest request) {
        SessionIdentity identity = current(request);
        if (identity.roles().contains("ADMIN") || !identity.roles().contains("RISK")
                || identity.accountScope().isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN, "没有此风控操作权限或数据范围");
        }
        return identity;
    }

    /** 对象级范围校验：名单/事件关联账户必须在会话账户范围内。 */
    public void requireScope(SessionIdentity identity, String accountId) {
        if (accountId == null || !identity.accountScope().contains(accountId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "没有此账户的风控数据权限");
        }
    }

    /** 审批人不得是提交人本人（职责分离）。 */
    public void requireDifferentApprover(SessionIdentity identity, String submittedBy) {
        if (submittedBy != null && submittedBy.equals(identity.identityId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "审批人不能是提交人本人");
        }
    }

    private static boolean hasAny(SessionIdentity identity, String... roles) {
        Set<String> owned = identity.roles();
        for (String role : roles) {
            if (owned.contains(role)) return true;
        }
        return false;
    }
}
