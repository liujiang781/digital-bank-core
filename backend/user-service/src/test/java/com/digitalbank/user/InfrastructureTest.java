package com.digitalbank.user;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTest.Endpoints.class)
class InfrastructureTest {
    @Autowired MockMvc mvc;

    @Test
    void traceSurvivesInHeaderAndBody() throws Exception {
        mvc.perform(get("/api/v1/user/health").header("X-Trace-Id", "smoke-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "smoke-123"))
                .andExpect(jsonPath("$.traceId").value("smoke-123"))
                .andExpect(jsonPath("$.data.service").value("user-service"));
    }

    @Test
    void unsafeTraceIsReplaced() throws Exception {
        mvc.perform(get("/api/v1/user/health").header("X-Trace-Id", "unsafe trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void businessErrorUsesContractStatus() throws Exception {
        mvc.perform(get("/test/business-error"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CONFLICT"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unexpectedErrorDoesNotExposeDetails() throws Exception {
        mvc.perform(get("/test/system-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("private-detail"))));
    }

    @Test
    void missingRouteRemains404() throws Exception {
        mvc.perform(get("/missing")).andExpect(status().isNotFound());
    }

    @Test
    void wrongMethodRemains405() throws Exception {
        mvc.perform(post("/api/v1/user/health"))
                .andExpect(status().isMethodNotAllowed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Endpoints {
        @Bean FailureController failureController() { return new FailureController(); }
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class FailureController {
        @GetMapping("/test/business-error")
        void business() { throw new BizException(ErrorCode.REQUEST_CONFLICT, "请求内容冲突"); }
        @GetMapping("/test/system-error")
        void system() { throw new IllegalStateException("private-detail"); }
    }
}
