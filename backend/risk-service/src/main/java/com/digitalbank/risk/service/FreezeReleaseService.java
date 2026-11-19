package com.digitalbank.risk.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.risk.security.RiskAccess;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FreezeReleaseService {
    private final JdbcTemplate jdbc;
    private final RiskAccess access;

    public FreezeReleaseService(JdbcTemplate jdbc, RiskAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Approval approve(SessionIdentity actor, String accountId, long restrictionId, String basis) {
        access.requireScope(actor, accountId);
        if (restrictionId <= 0 || basis == null || basis.isBlank() || basis.trim().length() > 200) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请填写有效的冻结编号和解除依据（至多200字）");
        }
        String evidence = basis.trim();
        Approval existing = find(accountId, restrictionId);
        if (existing != null) return replay(existing, evidence);

        // A risk restriction is identified by its original status log, including older RISK-prefixed records.
        int inserted;
        try {
            inserted = jdbc.update("INSERT INTO risk_freeze_release (restriction_id, approved_by, basis) "
                            + "SELECT f.id, ?, ? FROM account_status_log f JOIN account a ON a.account_id = f.account_id "
                            + "WHERE f.account_id = ? AND f.id = ? AND a.status = 'FROZEN' AND f.new_status = 'FROZEN' "
                            + "AND f.change_reason LIKE 'RISK: %' AND f.id > COALESCE((SELECT MAX(s.id) "
                            + "FROM account_status_log s WHERE s.account_id = f.account_id AND s.new_status = 'ACTIVE'), 0)",
                    actor.identityId(), evidence, accountId, restrictionId);
        } catch (DuplicateKeyException concurrentApproval) {
            return replay(find(accountId, restrictionId), evidence);
        }
        if (inserted == 0) {
            throw new BizException(ErrorCode.ACCOUNT_STATUS_INVALID, "该账户没有此有效风控冻结原因");
        }
        jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, result) "
                        + "VALUES (?, 'RISK_FREEZE_RELEASE_APPROVE', 'account_restriction', ?, ?, 'SUCCESS')",
                actor.identityId(), Long.toString(restrictionId), evidence);
        return new Approval(accountId, restrictionId, actor.identityId(), evidence);
    }

    private Approval find(String accountId, long restrictionId) {
        return jdbc.query("SELECT r.approved_by, r.basis FROM risk_freeze_release r "
                        + "JOIN account_status_log f ON f.id = r.restriction_id "
                        + "WHERE f.account_id = ? AND r.restriction_id = ?",
                (rs, index) -> new Approval(accountId, restrictionId, rs.getString("approved_by"), rs.getString("basis")),
                accountId, restrictionId).stream().findFirst().orElse(null);
    }

    private Approval replay(Approval approval, String basis) {
        if (approval == null || !approval.basis().equals(basis)) {
            throw new BizException(ErrorCode.REQUEST_CONFLICT, "该冻结原因已有不同的解除结论");
        }
        return approval;
    }

    public record Approval(String accountId, long restrictionId, String approvedBy, String basis) { }
}
