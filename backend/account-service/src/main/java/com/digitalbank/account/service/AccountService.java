package com.digitalbank.account.service;

import com.digitalbank.account.domain.Account;
import com.digitalbank.account.dto.AccountDtos.*;
import com.digitalbank.account.security.AccountAccess;
import com.digitalbank.account.security.Actor;
import com.digitalbank.account.support.Money;
import com.digitalbank.account.support.RequestIds;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> BACK_OFFICE = Set.of("OPERATIONS", "RISK");
    private final JdbcTemplate jdbc;
    private final AccountAccess access;
    private final ObjectMapper json;
    private final BigDecimal defaultSingleLimit;
    private final BigDecimal defaultDailyLimit;

    public AccountService(JdbcTemplate jdbc, AccountAccess access, ObjectMapper json,
            @Value("${account.outgoing-single-limit:10000.00}") BigDecimal defaultSingleLimit,
            @Value("${account.outgoing-daily-limit:50000.00}") BigDecimal defaultDailyLimit) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
        this.defaultSingleLimit = defaultSingleLimit;
        this.defaultDailyLimit = defaultDailyLimit;
    }

    @Transactional
    public AccountView open(Actor actor, OpenRequest request, String requestId) {
        if (actor.customerId() == null || actor.customerId().isBlank()) {
            throw error(ErrorCode.FORBIDDEN, "仅客户本人可以开户");
        }
        currency(request.currency());
        // A customer row is the stable lock even before that customer's first account exists.
        List<Map<String, Object>> customers = jdbc.queryForList(
                "SELECT identity_id, real_name_status, status FROM customer WHERE customer_id = ? FOR UPDATE",
                actor.customerId());
        if (customers.isEmpty() || !actor.identityId().equals(customers.getFirst().get("identity_id"))) {
            throw error(ErrorCode.FORBIDDEN, "客户身份不匹配");
        }
        String hash = hash(actor.customerId(), "CNY");
        String accountId = id();
        return command(actor, "ACCOUNT_OPEN", requestId, hash, accountId, AccountView.class, () -> {
            var customer = customers.getFirst();
            if (!"VERIFIED".equals(customer.get("real_name_status"))
                    || !"NORMAL".equals(customer.get("status"))) {
                throw error(ErrorCode.FORBIDDEN, "开户需要有效客户及已通过的实名信息");
            }
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM account WHERE customer_id = ? AND status <> 'CLOSED'",
                    Long.class, actor.customerId());
            if (count != null && count >= 3) {
                throw error(ErrorCode.ACCOUNT_LIMIT_REACHED, "每位客户最多保留三个未注销账户");
            }
            jdbc.update("INSERT INTO account (account_id, customer_id, currency, status, book_balance, reserved_amount) "
                    + "VALUES (?, ?, 'CNY', 'ACTIVE', 0.00, 0.00)", accountId, actor.customerId());
            String tradeNo = id();
            jdbc.update("INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, "
                    + "request_hash, account_id, amount, currency, status, accepted_at, completed_at) "
                    + "VALUES (?, ?, 'ACCOUNT_OPEN', ?, ?, ?, 0.00, 'CNY', 'SUCCESS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    tradeNo, actor.identityId(), requestId, hash, accountId);
            statusLog(actor, accountId, null, "ACTIVE", "客户开户");
            audit(actor, "ACCOUNT_OPEN", accountId, "客户开户", tradeNo);
            return view(load(accountId, false));
        });
    }

    @Transactional(readOnly = true)
    public Page<AccountView> list(Actor actor, int page, int pageSize) {
        pagination(page, pageSize);
        List<Object> args = new ArrayList<>();
        String condition = null;
        if (actor.customerId() != null && !actor.customerId().isBlank()) {
            condition = "customer_id = ?";
            args.add(actor.customerId());
        }
        if (isBackOffice(actor) && !actor.accountScope().isEmpty()) {
            List<String> scope = actor.accountScope().stream().filter(id -> !"*".equals(id)).sorted().toList();
            if (!scope.isEmpty()) {
                String scoped = "account_id IN (" + String.join(",", java.util.Collections.nCopies(scope.size(), "?")) + ")";
                condition = condition == null ? scoped : "(" + condition + " OR " + scoped + ")";
                args.addAll(scope);
            }
        }
        if (condition == null) {
            return new Page<>(List.of(), 0, page, pageSize);
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE " + condition, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(pageSize);
        queryArgs.add((long) (page - 1) * pageSize);
        List<AccountView> items = jdbc.query("SELECT * FROM account WHERE " + condition
                        + " ORDER BY created_at DESC, account_id DESC LIMIT ? OFFSET ?", Account::fromRow, queryArgs.toArray())
                .stream().map(this::view).toList();
        return new Page<>(items, count == null ? 0 : count, page, pageSize);
    }

    @Transactional(readOnly = true)
    public AccountView get(Actor actor, String accountId) {
        Account account = load(accountId, false);
        requireRead(actor, account);
        return view(account);
    }

    @Transactional(readOnly = true)
    public BalanceView balance(Actor actor, String accountId) {
        Account account = load(accountId, false);
        requireRead(actor, account);
        return new BalanceView(account.accountId(), account.currency(), account.status(),
                Money.format(account.bookBalance()), Money.format(account.reservedAmount()),
                Money.format(account.availableBalance()));
    }

    @Transactional
    public FreezeResult freeze(Actor actor, String accountId, FreezeRequest request, String requestId) {
        access.requireOperator(actor, accountId, true);
        Account account = load(accountId, true);
        String reason = text(request.reason(), "冻结原因");
        return command(actor, "ACCOUNT_FREEZE", requestId, hash(accountId, reason), accountId,
                FreezeResult.class, () -> {
                    if ("CLOSED".equals(account.status())) {
                        throw error(ErrorCode.ACCOUNT_STATUS_INVALID, "已注销账户不能冻结");
                    }
                    // Every new freeze is an independent restriction; a second risk restriction must not be lost.
                    jdbc.update("UPDATE account SET status = 'FROZEN', version = version + 1 WHERE account_id = ?", accountId);
                    String source = actor.roles().contains("RISK") ? "RISK" : "OPERATIONS";
                    statusLog(actor, accountId, account.status(), "FROZEN", source + ": " + reason);
                    audit(actor, "ACCOUNT_FREEZE", accountId, reason, null);
                    return new FreezeResult(accountId, "FROZEN", activeRestrictions(accountId));
                });
    }

    @Transactional
    public StatusResult unfreeze(Actor actor, String accountId, UnfreezeRequest request, String requestId) {
        access.requireOperator(actor, accountId, false);
        Account account = load(accountId, true);
        String reason = text(request.reason(), "解冻原因");
        String evidence = text(request.releaseEvidence(), "解除限制依据");
        if (request.resolvedRestrictionIds() == null || request.resolvedRestrictionIds().isEmpty()
                || request.resolvedRestrictionIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw error(ErrorCode.INVALID_ARGUMENT, "必须逐项列出已解除的有效限制编号");
        }
        List<Long> resolved = request.resolvedRestrictionIds().stream().distinct().sorted().toList();
        return command(actor, "ACCOUNT_UNFREEZE", requestId,
                hash(accountId, reason, evidence, encode(resolved)), accountId, StatusResult.class, () -> {
                    if (!"FROZEN".equals(account.status())) {
                        throw error(ErrorCode.ACCOUNT_STATUS_INVALID, "仅冻结账户可以解冻");
                    }
                    List<Long> active = activeRestrictions(accountId).stream().map(RestrictionView::restrictionId).sorted().toList();
                    if (active.isEmpty() || !active.equals(resolved)) {
                        throw error(ErrorCode.ACCOUNT_STATUS_INVALID, "解除依据必须覆盖全部当前有效限制");
                    }
                    jdbc.update("UPDATE account SET status = 'ACTIVE', version = version + 1 WHERE account_id = ?", accountId);
                    statusLog(actor, accountId, "FROZEN", "ACTIVE", reason);
                    // The complete evidence is retained; the ACTIVE log ID closes every earlier restriction.
                    audit(actor, "ACCOUNT_UNFREEZE", accountId, "解除依据: " + evidence, null);
                    return new StatusResult(accountId, "ACTIVE");
                });
    }

    @Transactional
    public LimitsResult limits(Actor actor, String accountId, LimitsRequest request, String requestId) {
        Account account = load(accountId, true);
        access.requireOwner(actor, account.customerId());
        BigDecimal single = Money.parsePositive(request.personalSingleLimit());
        BigDecimal daily = Money.parsePositive(request.personalDailyLimit());
        if (single.compareTo(daily) > 0) throw error(ErrorCode.INVALID_ARGUMENT, "单笔限额不得高于日限额");
        return command(actor, "ACCOUNT_LIMITS", requestId, hash(accountId, Money.format(single), Money.format(daily)),
                accountId, LimitsResult.class, () -> {
                    if ("CLOSED".equals(account.status())) {
                        throw error(ErrorCode.ACCOUNT_STATUS_INVALID, "已注销账户不能修改限额");
                    }
                    List<Map<String, Object>> policies = jdbc.queryForList(
                            "SELECT outgoing_single_limit, outgoing_daily_limit FROM risk_rule "
                                    + "WHERE rule_type = 'LIMIT_POLICY' AND enabled = 1 ORDER BY rule_version DESC, id DESC LIMIT 1");
                    BigDecimal systemSingle = policies.isEmpty() ? defaultSingleLimit
                            : (BigDecimal) policies.getFirst().get("outgoing_single_limit");
                    BigDecimal systemDaily = policies.isEmpty() ? defaultDailyLimit
                            : (BigDecimal) policies.getFirst().get("outgoing_daily_limit");
                    if (single.compareTo(systemSingle) > 0 || daily.compareTo(systemDaily) > 0) {
                        throw error(ErrorCode.INVALID_ARGUMENT, "个人限额不得高于系统上限");
                    }
                    jdbc.update("UPDATE account SET personal_single_limit = ?, personal_daily_limit = ?, "
                                    + "version = version + 1 WHERE account_id = ?", single, daily, accountId);
                    audit(actor, "ACCOUNT_LIMITS", accountId,
                            "single=" + Money.format(single) + ";daily=" + Money.format(daily), null);
                    return new LimitsResult(accountId, Money.format(single), Money.format(daily));
                });
    }

    @Transactional
    public StatusResult close(Actor actor, String accountId, String requestId) {
        Account account = load(accountId, true);
        access.requireOwner(actor, account.customerId());
        return command(actor, "ACCOUNT_CLOSE", requestId, hash(accountId), accountId, StatusResult.class, () -> {
            if ("CLOSED".equals(account.status())) {
                throw error(ErrorCode.ACCOUNT_STATUS_INVALID, "账户已经注销");
            }
            if ("FROZEN".equals(account.status())) {
                throw error(ErrorCode.ACCOUNT_NOT_CLOSABLE, "账户存在有效冻结限制");
            }
            Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM `transaction` t "
                    + "LEFT JOIN transfer_order o ON o.trade_no = t.trade_no "
                    + "WHERE (t.account_id = ? OR o.payee_account_id = ?) "
                    + "AND t.status IN ('ACCEPTED', 'PROCESSING', 'UNKNOWN', 'RECOVERING')", Long.class, accountId, accountId);
            if (account.bookBalance().signum() != 0 || account.reservedAmount().signum() != 0
                    || (pending != null && pending > 0)) {
                throw error(ErrorCode.ACCOUNT_NOT_CLOSABLE, "注销需要零余额、零占用且没有未决交易");
            }
            jdbc.update("UPDATE account SET status = 'CLOSED', version = version + 1 WHERE account_id = ?", accountId);
            statusLog(actor, accountId, account.status(), "CLOSED", "客户注销");
            audit(actor, "ACCOUNT_CLOSE", accountId, "客户注销", null);
            return new StatusResult(accountId, "CLOSED");
        });
    }

    @Transactional(readOnly = true)
    public Page<OperationView> operations(Actor actor, String accountId, int page, int pageSize,
            OffsetDateTime startTime, OffsetDateTime endTime) {
        requireRead(actor, load(accountId, false));
        List<Object> args = new ArrayList<>(List.of(accountId));
        String filter = timeFilter("target_type = 'account' AND target_ref = ?", args, startTime, endTime);
        return queryPage("audit_log", filter, args, page, pageSize,
                (rs, n) -> new OperationView(rs.getLong("id"), rs.getString("action"), rs.getString("reason"),
                        rs.getString("result"), offset(rs.getObject("created_at", LocalDateTime.class))), "created_at DESC, id DESC");
    }

    @Transactional(readOnly = true)
    public Page<FundEntryView> fundEntries(Actor actor, String accountId, int page, int pageSize,
            OffsetDateTime startTime, OffsetDateTime endTime) {
        requireRead(actor, load(accountId, false));
        List<Object> args = new ArrayList<>(List.of(accountId));
        String filter = timeFilter("account_id = ?", args, startTime, endTime);
        return queryPage("transaction_flow", filter, args, page, pageSize,
                (rs, n) -> new FundEntryView(rs.getString("entry_id"), rs.getString("trade_no"),
                        rs.getString("entry_role"), rs.getString("direction"), Money.format(rs.getBigDecimal("amount")),
                        Money.format(rs.getBigDecimal("book_balance_after")),
                        offset(rs.getObject("created_at", LocalDateTime.class))), "created_at DESC, id DESC");
    }

    private Account load(String accountId, boolean lock) {
        if (accountId == null || accountId.isBlank() || accountId.length() > 32) {
            throw error(ErrorCode.INVALID_ARGUMENT, "账户标识不合法");
        }
        List<Account> rows = jdbc.query("SELECT * FROM account WHERE account_id = ?" + (lock ? " FOR UPDATE" : ""),
                Account::fromRow, accountId);
        if (rows.isEmpty()) throw error(ErrorCode.ACCOUNT_NOT_FOUND, "账户不存在或无权访问");
        return rows.getFirst();
    }

    private void requireRead(Actor actor, Account account) {
        if (account.customerId().equals(actor.customerId())) {
            access.requireOwner(actor, account.customerId());
        } else if (isBackOffice(actor)) {
            access.requireOperator(actor, account.accountId(), true);
        } else {
            throw error(ErrorCode.ACCOUNT_NOT_FOUND, "账户不存在或无权访问");
        }
    }

    private boolean isBackOffice(Actor actor) {
        return actor.roles() != null && actor.roles().stream().anyMatch(BACK_OFFICE::contains);
    }

    private AccountView view(Account a) {
        return new AccountView(a.accountId(), a.currency(), a.status(), Money.format(a.bookBalance()),
                Money.format(a.reservedAmount()), Money.format(a.availableBalance()),
                nullableMoney(a.personalSingleLimit()), nullableMoney(a.personalDailyLimit()), offset(a.createdAt()),
                "FROZEN".equals(a.status()) ? activeRestrictions(a.accountId()) : List.of());
    }

    private List<RestrictionView> activeRestrictions(String accountId) {
        // IDs, rather than DATETIME seconds, distinguish restrictions created in the same second.
        return jdbc.query("SELECT id, change_reason FROM account_status_log WHERE account_id = ? "
                        + "AND new_status = 'FROZEN' AND id > COALESCE((SELECT MAX(s.id) FROM account_status_log s "
                        + "WHERE s.account_id = ? AND s.new_status = 'ACTIVE'), 0) ORDER BY id",
                (rs, n) -> new RestrictionView(rs.getLong("id"), rs.getString("change_reason")), accountId, accountId);
    }

    private <T> T command(Actor actor, String type, String requestId, String requestHash,
            String accountId, Class<T> resultType, Supplier<T> operation) {
        RequestIds.resolve(requestId, null);
        try {
            // Claim before changing business data. A competing transaction cannot observe the placeholder.
            jdbc.update("INSERT INTO account_command (identity_id, command_type, request_id, request_hash, account_id, result_json) "
                            + "VALUES (?, ?, ?, ?, ?, '{}')", actor.identityId(), type, requestId, requestHash, accountId);
        } catch (DuplicateKeyException ex) {
            List<Map<String, Object>> existing = commandRows(actor.identityId(), type, requestId);
            if (existing.isEmpty()) throw ex;
            return replay(existing.getFirst(), requestHash, resultType);
        }
        T result = operation.get();
        jdbc.update("UPDATE account_command SET result_json = ? WHERE identity_id = ? AND command_type = ? AND request_id = ?",
                encode(result), actor.identityId(), type, requestId);
        return result;
    }

    private List<Map<String, Object>> commandRows(String identityId, String type, String requestId) {
        return jdbc.queryForList("SELECT request_hash, result_json FROM account_command "
                + "WHERE identity_id = ? AND command_type = ? AND request_id = ? FOR UPDATE", identityId, type, requestId);
    }

    private <T> T replay(Map<String, Object> row, String requestHash, Class<T> resultType) {
        if (!requestHash.equals(row.get("request_hash"))) {
            throw error(ErrorCode.REQUEST_CONFLICT, "同一请求号对应了不同的请求内容");
        }
        try {
            return json.readValue((String) row.get("result_json"), resultType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid stored account command result", ex);
        }
    }

    private void statusLog(Actor actor, String accountId, String previous, String next, String reason) {
        jdbc.update("INSERT INTO account_status_log (account_id, previous_status, new_status, change_reason, operator_identity_id) "
                + "VALUES (?, ?, ?, ?, ?)", accountId, previous, next, bounded(reason), actor.identityId());
    }

    private void audit(Actor actor, String action, String accountId, String reason, String tradeNo) {
        jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, result, trade_no) "
                + "VALUES (?, ?, 'account', ?, ?, 'SUCCESS', ?)", actor.identityId(), action, accountId, bounded(reason), tradeNo);
    }

    private <T> Page<T> queryPage(String table, String filter, List<Object> args, int page, int pageSize,
            RowMapper<T> mapper, String ordering) {
        pagination(page, pageSize);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + filter, Long.class, args.toArray());
        args.add(pageSize);
        args.add((long) (page - 1) * pageSize);
        List<T> items = jdbc.query("SELECT * FROM " + table + " WHERE " + filter + " ORDER BY " + ordering
                + " LIMIT ? OFFSET ?", mapper, args.toArray());
        return new Page<>(items, count == null ? 0 : count, page, pageSize);
    }

    private String timeFilter(String filter, List<Object> args, OffsetDateTime start, OffsetDateTime end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw error(ErrorCode.INVALID_ARGUMENT, "开始时间不得晚于结束时间");
        }
        if (start != null) {
            filter += " AND created_at >= ?";
            args.add(start.atZoneSameInstant(ZONE).toLocalDateTime());
        }
        if (end != null) {
            filter += " AND created_at <= ?";
            args.add(end.atZoneSameInstant(ZONE).toLocalDateTime());
        }
        return filter;
    }

    private void pagination(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw error(ErrorCode.INVALID_ARGUMENT, "分页参数不合法");
    }

    private String hash(String... values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encode(Arrays.asList(values)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize account command result", ex);
        }
    }

    private String text(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw error(ErrorCode.INVALID_ARGUMENT, field + "不能为空且不得超过200字");
        }
        return value.trim();
    }

    private void currency(String value) {
        if (!"CNY".equals(value)) throw error(ErrorCode.INVALID_ARGUMENT, "仅支持CNY币种");
    }

    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String bounded(String text) { return text == null || text.length() <= 255 ? text : text.substring(0, 255); }
    private static OffsetDateTime offset(LocalDateTime time) { return time.atZone(ZONE).toOffsetDateTime(); }
    private static String nullableMoney(BigDecimal value) { return value == null ? null : Money.format(value); }
    private static BizException error(ErrorCode code, String message) { return new BizException(code, message); }
}
