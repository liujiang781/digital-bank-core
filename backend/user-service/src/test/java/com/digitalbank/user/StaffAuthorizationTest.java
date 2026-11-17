package com.digitalbank.user;

import com.digitalbank.user.config.StaffAuthorizationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "auth.demo.enabled=true",
        "auth.demo.identity-id=customer-test",
        "auth.demo.password=customer-test-password",
        "auth.demo.users[ops-test].customer-id=staff-customer",
        "auth.demo.users[ops-test].password=operations-test-password",
        "auth.staff.grants[ops-test].roles[0]=OPERATIONS",
        "auth.staff.grants[ops-test].account-scope[0]=account-1",
        "auth.internal-token=staff-test-service-token-at-least-32-bytes"
})
@AutoConfigureMockMvc
class StaffAuthorizationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void staffLoginInternalVerificationAndLogoutUseTheSameTrustedAuthorization() throws Exception {
        String token = login("ops-test", "operations-test-password");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token)
                        .header("X-Roles", "RISK").header("X-Account-Scope", "account-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("OPERATIONS"))
                .andExpect(jsonPath("$.data.accountScope[0]").value("account-1"));
        mvc.perform(get("/internal/v1/auth/session").header("Authorization", "Bearer " + token)
                        .header("X-Service-Token", "staff-test-service-token-at-least-32-bytes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identityId").value("ops-test"))
                .andExpect(jsonPath("$.data.roles[0]").value("OPERATIONS"))
                .andExpect(jsonPath("$.data.accountScope[0]").value("account-1"));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ordinaryCustomerCannotGrantItselfRolesOrScope() throws Exception {
        String token = login("customer-test", "customer-test-password");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token)
                        .header("X-Roles", "OPERATIONS").header("X-Account-Scope", "account-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles").isEmpty())
                .andExpect(jsonPath("$.data.accountScope").isEmpty());
    }

    @Test
    void configuredRoleDoesNotBypassCredentialAuthentication() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identityId", "ops-test", "password", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "account-*", " "})
    void ambiguousAccountScopesAreRejected(String scope) {
        assertThatThrownBy(() -> new StaffAuthorizationProperties.Grant(Set.of("OPERATIONS"), Set.of(scope)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownRoleAndScopeWithoutRoleAreRejected() {
        assertThatThrownBy(() -> new StaffAuthorizationProperties.Grant(Set.of("ADMIN"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaffAuthorizationProperties.Grant(Set.of(), Set.of("account-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String login(String identityId, String password) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identityId", identityId, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(response).path("data").path("token").asText();
        assertThat(token).hasSize(43);
        return token;
    }
}
