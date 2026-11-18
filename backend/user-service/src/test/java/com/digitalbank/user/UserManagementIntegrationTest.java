package com.digitalbank.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {
        "auth.credential-store=mysql",
        "auth.database.url=jdbc:h2:mem:user-management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "auth.database.username=sa", "auth.database.password=",
        "auth.database.driver-class-name=org.h2.Driver",
        "auth.staff.grants[admin-1].roles[0]=ADMIN",
        "auth.staff.grants[ops-1].roles[0]=OPERATIONS",
        "auth.staff.grants[ops-1].account-scope[0]=account-1"
})
@AutoConfigureMockMvc
@Sql(scripts = {"/schema-user-test.sql", "/db/s1-incremental.sql"})
class UserManagementIntegrationTest {
    private static final String PASSWORD = "customer-password";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        for (String identity : List.of("admin-1", "ops-1", "user-1", "user-2")) {
            String customer = "customer-" + identity;
            jdbc.update("INSERT INTO customer (customer_id, identity_id, name, real_name_status, status) "
                            + "VALUES (?, ?, '测试客户', 'VERIFIED', 'NORMAL')", customer, identity);
            jdbc.update("INSERT INTO credential (customer_id, username, password_hash, credential_type, status) "
                    + "VALUES (?, ?, ?, 'USERNAME', 'NORMAL')", customer, identity, HASH);
        }
        jdbc.update("INSERT INTO account VALUES ('account-1', 'customer-user-1', 'ACTIVE'), "
                + "('account-2', 'customer-user-1', 'FROZEN'), ('account-3', 'customer-user-2', 'ACTIVE')");
    }

    @Test
    void registrationCreatesVerifiedCustomerAndBcryptCredentialThenCanLogin() throws Exception {
        var body = registration("new-user", UUID.randomUUID().toString());
        JsonNode result = call(post("/api/v1/auth/register"), body, 200);
        assertThat(result.path("data").path("realNameStatus").asText()).isEqualTo("VERIFIED");
        String hash = jdbc.queryForObject("SELECT password_hash FROM credential WHERE customer_id = ?",
                String.class, result.path("data").path("customerId").asText());
        assertThat(hash).startsWith("$2");
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        String token = login("new-user");
        var profile = call(get("/api/v1/customers/me").header("Authorization", token), null, 200);
        assertThat(profile.path("data").path("identityId").asText()).isNotEqualTo("new-user");
        assertThat(profile.path("data").path("realName").asText()).isEqualTo("注册客户");
        assertThat(result.toString()).doesNotContain(PASSWORD, "passwordHash");
    }

    @Test
    void registrationReplayAndContentConflictHaveOneEffect() throws Exception {
        var body = registration("new-user", UUID.randomUUID().toString());
        var first = call(post("/api/v1/auth/register"), body, 200).path("data");
        assertThat(call(post("/api/v1/auth/register"), body, 200).path("data")).isEqualTo(first);
        var changed = new java.util.HashMap<>(body);
        changed.put("password", "different-password");
        call(post("/api/v1/auth/register"), changed, 409);
        changed.put("password", PASSWORD);
        changed.put("realName", "其他客户");
        call(post("/api/v1/auth/register"), changed, 409);
        call(post("/api/v1/auth/register"), registration("new-user", UUID.randomUUID().toString()), 409);
        assertThat(count("SELECT COUNT(*) FROM customer WHERE identity_id = 'new-user'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE `action` = 'CUSTOMER_REGISTER'")).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationReplaysCommitOneCustomer() throws Exception {
        var body = registration("new-user", UUID.randomUUID().toString());
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<JsonNode>>();
            for (int i = 0; i < 4; i++) tasks.add(() -> call(post("/api/v1/auth/register"), body, 200).path("data"));
            var results = pool.invokeAll(tasks);
            var first = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isEqualTo(first);
        }
        assertThat(count("SELECT COUNT(*) FROM user_command")).isEqualTo(1);
    }

    @Test
    void registrationAuditFailureRollsBackCustomerCredentialAndCommand() throws Exception {
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT reject_register CHECK (`action` <> 'CUSTOMER_REGISTER')");
        call(post("/api/v1/auth/register"), registration("new-user", UUID.randomUUID().toString()), 503);
        assertThat(count("SELECT COUNT(*) FROM customer WHERE identity_id = 'new-user'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM credential")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM user_command")).isZero();
    }

    @Test
    void registrationRejectsInvalidInputAndCannotClaimConfiguredStaffIdentity() throws Exception {
        var body = registration("new-user", "not-a-uuid");
        call(post("/api/v1/auth/register"), body, 400);
        body = registration("admin-1", UUID.randomUUID().toString());
        jdbc.update("DELETE FROM credential WHERE username = 'admin-1'");
        jdbc.update("DELETE FROM customer WHERE identity_id = 'admin-1'");
        call(post("/api/v1/auth/register"), body, 409);
        var unknown = new java.util.HashMap<>(registration("new-user", UUID.randomUUID().toString()));
        unknown.put("roles", List.of("ADMIN"));
        call(post("/api/v1/auth/register"), unknown, 400);
        unknown.remove("roles");
        unknown.put("password", "中".repeat(25));
        call(post("/api/v1/auth/register"), unknown, 400);
    }

    @Test
    void profileUpdateIsMaskedIdempotentAndDoesNotModifyIdentity() throws Exception {
        String token = login("user-1");
        var body = Map.of("phone", "13812345678", "email", "member@example.com", "requestId", UUID.randomUUID().toString());
        var first = call(put("/api/v1/customers/me").header("Authorization", token), body, 200).path("data");
        assertThat(first.path("phone").asText()).contains("*").doesNotContain("13812345678");
        assertThat(call(put("/api/v1/customers/me").header("Authorization", token), body, 200).path("data"))
                .isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE `action` = 'CUSTOMER_PROFILE_UPDATE'")).isEqualTo(1);
        var changed = new java.util.HashMap<>(body);
        changed.put("email", "other@example.com");
        call(put("/api/v1/customers/me").header("Authorization", token), changed, 409);
        assertThat(jdbc.queryForObject("SELECT email FROM customer WHERE identity_id = 'user-1'", String.class))
                .isEqualTo("member@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"identityId", "realName", "status", "roles", "customerId"})
    void profileCannotModifyProtectedFields(String field) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put(field, "forged");
        call(put("/api/v1/customers/me").header("Authorization", login("user-1")), body, 400);
    }

    @Test
    void duplicatePhoneRollsBackProfileAndIdempotencyRecord() throws Exception {
        jdbc.update("UPDATE customer SET phone='13812345678' WHERE identity_id='user-2'");
        call(put("/api/v1/customers/me").header("Authorization", login("user-1")),
                Map.of("phone", "13812345678", "requestId", UUID.randomUUID().toString()), 409);
        assertThat(jdbc.queryForObject("SELECT phone FROM customer WHERE identity_id='user-1'", String.class)).isNull();
        assertThat(count("SELECT COUNT(*) FROM user_command")).isZero();
    }

    @Test
    void customerListReturnsOnlyScopedCustomersAndAccountsAndAuditsReason() throws Exception {
        var result = call(get("/api/v1/customers").param("reason", "核查账户状态")
                .header("Authorization", login("ops-1")), null, 200).path("data");
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("accounts").size()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("accounts").get(0).path("accountId").asText()).isEqualTo("account-1");
        assertThat(result.toString()).doesNotContain("account-2", "account-3");
        assertThat(result.path("items").get(0).path("identityId").asText()).isNotEqualTo("user-1");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE `action`='CUSTOMER_LIST'")).isEqualTo(1);
        call(get("/api/v1/customers").param("reason", "核查").header("Authorization", login("user-1"))
                .header("X-Roles", "OPERATIONS").header("X-Account-Scope", "account-3"), null, 403);
        call(get("/api/v1/customers").header("Authorization", login("ops-1")), null, 400);
    }

    @Test
    void adminCanAssignScopeAndChangesInvalidateExistingSessions() throws Exception {
        String customerToken = login("user-2");
        String admin = login("admin-1");
        var grant = role("user-2", true, List.of("account-3"));
        var result = call(put("/api/v1/roles/OPERATIONS").header("Authorization", admin), grant, 200).path("data");
        assertThat(result.path("authorizationVersion").asLong()).isEqualTo(1);
        call(get("/api/v1/auth/me").header("Authorization", customerToken), null, 401);
        String staffToken = login("user-2");
        call(get("/api/v1/customers").param("reason", "核查").header("Authorization", staffToken), null, 200);
        var current = call(get("/api/v1/roles").param("identityId", "user-2").header("Authorization", admin), null, 200);
        assertThat(current.path("data")).isEqualTo(result);
        call(put("/api/v1/roles/OPERATIONS").header("Authorization", admin), role("user-2", false, List.of()), 200);
        call(get("/api/v1/auth/me").header("Authorization", staffToken), null, 401);
        var cleared = call(get("/api/v1/auth/me").header("Authorization", login("user-2")), null, 200).path("data");
        assertThat(cleared.path("roles").isEmpty()).isTrue();
        assertThat(cleared.path("authorizationVersion").asLong()).isEqualTo(2);
    }

    @Test
    void roleReplayDoesNotIncreaseVersionAndRevocationOverridesStaticGrant() throws Exception {
        String admin = login("admin-1");
        var body = role("ops-1", false, List.of());
        var first = call(put("/api/v1/roles/OPERATIONS").header("Authorization", admin), body, 200).path("data");
        assertThat(call(put("/api/v1/roles/OPERATIONS").header("Authorization", admin), body, 200).path("data"))
                .isEqualTo(first);
        var current = call(get("/api/v1/auth/me").header("Authorization", login("ops-1")), null, 200).path("data");
        assertThat(current.path("roles").isEmpty()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE `action`='ROLE_ASSIGN'")).isEqualTo(1);
    }

    @Test
    void roleVersionPreventsOldTokenResurrectionAfterScopeIsRestored() throws Exception {
        String admin = login("admin-1");
        call(put("/api/v1/roles/OPERATIONS").header("Authorization", admin),
                role("user-2", true, List.of("account-3")), 200);
        String old = login("user-2");
        call(put("/api/v1/roles/RISK").header("Authorization", admin),
                role("user-2", true, List.of("account-1")), 200);
        call(put("/api/v1/roles/OPERATIONS").header("Authorization", admin),
                role("user-2", true, List.of("account-3")), 200);
        call(get("/api/v1/auth/me").header("Authorization", old), null, 401);
    }

    @Test
    void sameAuthorizationRequestWithDifferentScopeIsConflict() throws Exception {
        String admin = login("admin-1");
        var original = role("user-2", true, List.of("account-3"));
        call(put("/api/v1/roles/OPERATIONS").header("Authorization", admin), original, 200);
        var changed = new java.util.HashMap<>(original);
        changed.put("accountScope", List.of("account-1"));
        call(put("/api/v1/roles/OPERATIONS").header("Authorization", admin), changed, 409);
        assertThat(jdbc.queryForObject("SELECT version FROM staff_authorization WHERE identity_id='user-2'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void invalidAccountScopeRollsBackGrantAndAudit() throws Exception {
        call(put("/api/v1/roles/OPERATIONS").header("Authorization", login("admin-1")),
                role("user-2", true, List.of("account-3", "not-found")), 400);
        assertThat(count("SELECT COUNT(*) FROM staff_authorization")).isZero();
        assertThat(count("SELECT COUNT(*) FROM staff_account_scope")).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_command")).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log")).isZero();
    }

    @Test
    void administratorCanRevokeAuthorizationOfALockedIdentity() throws Exception {
        jdbc.update("UPDATE credential SET status='LOCKED' WHERE username='ops-1'");
        call(put("/api/v1/roles/OPERATIONS").header("Authorization", login("admin-1")),
                role("ops-1", false, List.of()), 200);
        assertThat(jdbc.queryForObject("SELECT role_id FROM staff_authorization WHERE identity_id='ops-1'", String.class))
                .isNull();
    }

    @Test
    void roleManagementRejectsForgedAdminAndOnlineAdminAssignment() throws Exception {
        call(put("/api/v1/roles/OPERATIONS").header("Authorization", login("user-1"))
                        .header("X-Roles", "ADMIN"), role("user-2", true, List.of("account-1")), 403);
        String admin = login("admin-1");
        call(put("/api/v1/roles/ADMIN").header("Authorization", admin), role("user-2", true, List.of()), 400);
        call(put("/api/v1/roles/OPERATIONS").header("Authorization", admin), role("admin-1", true, List.of()), 403);
        call(get("/api/v1/customers").param("reason", "核查").header("Authorization", admin), null, 403);
        call(get("/api/v1/customers/me").header("Authorization", admin), null, 403);
        call(get("/api/v1/customers/me"), null, 401);
    }

    private Map<String, Object> registration(String identity, String requestId) {
        return Map.of("identityId", identity, "realName", "注册客户", "password", PASSWORD, "requestId", requestId);
    }

    private Map<String, Object> role(String identity, boolean enabled, List<String> scope) {
        return Map.of("identityId", identity, "enabled", enabled, "accountScope", scope,
                "reason", "岗位与账户范围调整", "requestId", UUID.randomUUID().toString());
    }

    private String login(String identityId) throws Exception {
        return "Bearer " + call(post("/api/v1/auth/login"), Map.of("identityId", identityId, "password", PASSWORD), 200)
                .path("data").path("token").asText();
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(expected);
        return mapper.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
