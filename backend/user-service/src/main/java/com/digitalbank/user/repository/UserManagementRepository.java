package com.digitalbank.user.repository;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.user.dto.UserDtos.*;
import com.digitalbank.user.support.UserInputs;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 用户写操作、审计和幂等记录共用一个数据源与本地事务。 */
@Repository
@ConditionalOnProperty(name = "auth.credential-store", havingValue = "mysql")
public class UserManagementRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public UserManagementRepository(JdbcTemplate userJdbcTemplate, TransactionTemplate userTransactions,
                                    ObjectMapper mapper) {
        this.jdbc = userJdbcTemplate;
        this.transactions = userTransactions;
        this.mapper = mapper;
    }

    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法编码用户业务结果", failure);
        }
    }

    public <T> T command(String identityId, String type, String requestId, Object content,
                         Class<T> resultType, Supplier<T> action) {
        String key = UserInputs.requestId(requestId);
        String hash = UserInputs.fingerprint(encode(content));
        try {
            return transactions.execute(status -> {
                T previous = replay(identityId, type, key, hash, resultType);
                if (previous != null) return previous;
                jdbc.update("INSERT INTO user_command (identity_id, business_type, request_id, request_hash) "
                        + "VALUES (?, ?, ?, ?)", identityId, type, key, hash);
                T result = action.get();
                jdbc.update("UPDATE user_command SET response_json = ? "
                                + "WHERE identity_id = ? AND business_type = ? AND request_id = ?",
                        encode(result), identityId, type, key);
                return result;
            });
        } catch (DuplicateKeyException duplicate) {
            // 并发同键请求在唯一键处等待首笔提交；先回滚，再读取已提交结果。
            T previous = replay(identityId, type, key, hash, resultType);
            if (previous != null) return previous;
            throw conflict();
        }
    }

    private <T> T replay(String identityId, String type, String requestId, String hash, Class<T> resultType) {
        var rows = jdbc.queryForList("SELECT request_hash, response_json FROM user_command "
                + "WHERE identity_id = ? AND business_type = ? AND request_id = ?", identityId, type, requestId);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!hash.equals(row.get("request_hash")) || row.get("response_json") == null) throw conflict();
        try {
            return mapper.readValue((String) row.get("response_json"), resultType);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法读取用户业务结果", failure);
        }
    }

    public boolean customerExists(String identityId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE identity_id = ?",
                Integer.class, identityId);
        return count != null && count > 0;
    }

    public boolean usableCustomerExists(String identityId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM customer c JOIN credential cr "
                + "ON cr.customer_id = c.customer_id WHERE c.identity_id = ? AND c.status = 'NORMAL' "
                + "AND cr.status = 'NORMAL' AND cr.credential_type = 'USERNAME'", Integer.class, identityId);
        return count != null && count > 0;
    }

    public String passwordHash(String identityId) {
        return jdbc.queryForObject("SELECT cr.password_hash FROM credential cr JOIN customer c "
                + "ON cr.customer_id = c.customer_id WHERE c.identity_id = ? AND cr.credential_type = 'USERNAME' "
                + "ORDER BY cr.id LIMIT 1", String.class, identityId);
    }

    public RegisterResult register(String identityId, String realName, String passwordHash) {
        String customerId = "CUS" + UUID.randomUUID().toString().replace("-", "").substring(0, 29);
        jdbc.update("INSERT INTO customer (customer_id, identity_id, name, real_name_status, status) "
                        + "VALUES (?, ?, ?, 'VERIFIED', 'NORMAL')", customerId, identityId, realName);
        // username 不复用可能长达 64 位的 identityId，避免超过 credential 的 50 字符限制。
        jdbc.update("INSERT INTO credential (customer_id, username, password_hash, credential_type, status) "
                        + "VALUES (?, ?, ?, 'USERNAME', 'NORMAL')", customerId, customerId, passwordHash);
        audit(identityId, "CUSTOMER_REGISTER", "customer", customerId, "教学模拟实名：格式校验通过");
        return new RegisterResult(customerId, "VERIFIED");
    }

    public Profile profile(String customerId, String identityId) {
        var rows = jdbc.query("SELECT customer_id, identity_id, name, real_name_status, status, phone, email "
                        + "FROM customer WHERE customer_id = ? AND identity_id = ? AND status = 'NORMAL'",
                (row, n) -> new Profile(row.getString("customer_id"), UserInputs.mask(row.getString("identity_id")),
                        row.getString("name"), row.getString("real_name_status"), row.getString("status"),
                        UserInputs.mask(row.getString("phone")), UserInputs.mask(row.getString("email"))),
                customerId, identityId);
        if (rows.isEmpty()) throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND, "客户不存在或不可用");
        return rows.getFirst();
    }

    public Profile updateProfile(String customerId, String identityId, String phone, String email) {
        // PUT 的可修改资料是手机号与邮箱；null 表示清空，实名姓名、身份和状态不可改。
        int updated = jdbc.update("UPDATE customer SET phone = ?, email = ?, version = version + 1 "
                + "WHERE customer_id = ? AND identity_id = ? AND status = 'NORMAL'", phone, email, customerId, identityId);
        if (updated != 1) throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND, "客户不存在或不可用");
        audit(identityId, "CUSTOMER_PROFILE_UPDATE", "customer", customerId, "更新手机号和邮箱");
        return profile(customerId, identityId);
    }

    public Page<CustomerSummary> customers(Set<String> scope, String status, int page, int pageSize) {
        List<String> ids = scope.stream().sorted().toList();
        String slots = String.join(",", Collections.nCopies(ids.size(), "?"));
        String where = " WHERE EXISTS (SELECT 1 FROM account a WHERE a.customer_id = c.customer_id "
                + "AND a.account_id IN (" + slots + "))";
        List<Object> args = new ArrayList<>(ids);
        if (status != null) {
            where += " AND c.status = ?";
            args.add(status);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM customer c" + where, Long.class, args.toArray());
        args.add(pageSize);
        args.add((page - 1L) * pageSize);
        List<CustomerSummary> items = jdbc.query("SELECT c.customer_id, c.identity_id, c.name, c.status "
                        + "FROM customer c" + where + " ORDER BY c.customer_id LIMIT ? OFFSET ?",
                (row, n) -> {
                    List<Object> accountArgs = new ArrayList<>();
                    accountArgs.add(row.getString("customer_id"));
                    accountArgs.addAll(ids);
                    var accounts = jdbc.query("SELECT account_id, status FROM account WHERE customer_id = ? "
                                    + "AND account_id IN (" + slots + ") ORDER BY account_id",
                            (account, index) -> new AccountStatus(account.getString("account_id"), account.getString("status")),
                            accountArgs.toArray());
                    return new CustomerSummary(row.getString("customer_id"), UserInputs.mask(row.getString("identity_id")),
                            UserInputs.mask(row.getString("name")), row.getString("status"), accounts);
                }, args.toArray());
        return new Page<>(total == null ? 0 : total, page, pageSize, items);
    }

    /** null 表示尚无在线授权记录，可回落到部署时的静态授权。撤权后的空记录不能回落。 */
    public Authorization authorization(String identityId) {
        return transactions.execute(status -> readAuthorization(identityId));
    }

    private Authorization readAuthorization(String identityId) {
        // 与范围替换共用行锁，避免读取到新角色搭配旧账户范围。
        var rows = jdbc.query("SELECT role_id, version FROM staff_authorization WHERE identity_id = ? FOR UPDATE",
                (row, n) -> new Authorization(identityId,
                        row.getString("role_id") == null ? Set.of() : Set.of(row.getString("role_id")),
                        Set.of(), row.getLong("version")), identityId);
        if (rows.isEmpty()) return null;
        var grant = rows.getFirst();
        Set<String> scope = Set.copyOf(jdbc.queryForList(
                "SELECT account_id FROM staff_account_scope WHERE identity_id = ?", String.class, identityId));
        return new Authorization(identityId, grant.roles(), scope, grant.authorizationVersion());
    }

    public Authorization replaceAuthorization(String identityId, String roleId, Set<String> scope) {
        List<Long> versions = jdbc.queryForList("SELECT version FROM staff_authorization "
                + "WHERE identity_id = ? FOR UPDATE", Long.class, identityId);
        if (versions.isEmpty()) {
            jdbc.update("INSERT INTO staff_authorization (identity_id, role_id, version) VALUES (?, ?, 1)",
                    identityId, roleId);
        } else {
            jdbc.update("UPDATE staff_authorization SET role_id = ?, version = version + 1 WHERE identity_id = ?",
                    roleId, identityId);
        }
        jdbc.update("DELETE FROM staff_account_scope WHERE identity_id = ?", identityId);
        for (String accountId : scope.stream().sorted().toList()) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE account_id = ? "
                    + "AND status <> 'CLOSED'", Integer.class, accountId);
            if (count == null || count != 1) throw UserInputs.invalid("授权范围包含不存在或已注销的账户");
            jdbc.update("INSERT INTO staff_account_scope (identity_id, account_id) VALUES (?, ?)", identityId, accountId);
        }
        return authorization(identityId);
    }

    public void audit(String identityId, String action, String targetType, String target, String reason) {
        jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, result) "
                + "VALUES (?, ?, ?, ?, ?, 'SUCCESS')", identityId, action, targetType, target, reason);
    }

    public static BizException conflict() {
        return new BizException(ErrorCode.REQUEST_CONFLICT, "请求内容冲突或身份、手机号已被使用");
    }
}
