package com.digitalbank.user;

import com.digitalbank.common.auth.AccessService;
import com.digitalbank.common.auth.InternalServiceToken;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "auth.demo.enabled=true",
        "auth.demo.identity-id=auth-test-user",
        "auth.demo.password=auth-test-password",
        "auth.internal-token=test-service-secret-at-least-32-characters"
})
@AutoConfigureMockMvc
class AuthControllerTest {
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/auth/me";
    private static final String PASSWORD = "auth-test-password";
    private static final String SERVICE_SECRET = "test-service-secret-at-least-32-characters";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccessService accessService;

    @Test
    void loginReturnsSessionWithoutExposingCredentials() throws Exception {
        MvcResult result = mvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "login-test")
                        .content(loginJson("auth-test-user", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.traceId").value("login-test"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.customer.customerId").value("DEMO-CUSTOMER-001"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(PASSWORD, "passwordHash");
        assertThat(mapper.readTree(body).at("/data/token").asText())
                .matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void wrongPasswordAndUnknownIdentityHaveSameFailure() throws Exception {
        for (String identity : new String[]{"auth-test-user", "unknown-user"}) {
            mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(identity, "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.message").value("身份标识或密码错误，或用户不可用"));
        }
    }

    @Test
    void invalidInputReturns400() throws Exception {
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("auth-test-user", "密".repeat(30))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousAndMalformedTokensAreRejected() throws Exception {
        mvc.perform(get(ME).header("X-Trace-Id", "anonymous-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.traceId").value("anonymous-test"));
        mvc.perform(get(ME).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedIdentityDoesNotComeFromClientHeaders() throws Exception {
        String token = login();
        mvc.perform(get(ME)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Customer-Id", "OTHER-CUSTOMER")
                        .header("X-Identity-Id", "forged-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identityId").value("auth-test-user"));
        mvc.perform(get(ME).header("Authorization", "Bearer " + token, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTokenImmediately() throws Exception {
        String token = login();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get(ME).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalIntrospectionRequiresBothServiceAndUserCredentials() throws Exception {
        String token = login();
        String endpoint = "/internal/v1/auth/session";
        mvc.perform(get(endpoint).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint)
                        .header(InternalServiceToken.HEADER, SERVICE_SECRET)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identityId").value("auth-test-user"));
        mvc.perform(get(endpoint).header(InternalServiceToken.HEADER, SERVICE_SECRET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCheckRejectsOtherCustomers() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AccessService.IDENTITY_ATTRIBUTE, new SessionIdentity("C1", "I1"));
        accessService.requireOwner(request, "C1");
        assertThatThrownBy(() -> accessService.requireOwner(request, "C2"))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode().status()).isEqualTo(403));
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("auth-test-user", PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = mapper.readTree(result.getResponse().getContentAsString());
        return response.at("/data/token").asText();
    }

    private String loginJson(String identity, String password) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of("identityId", identity, "password", password));
    }
}
