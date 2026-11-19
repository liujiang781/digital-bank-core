package com.digitalbank.common.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthPathsTest {
    @Test
    void onlyExactRegistrationPostIsPublic() {
        assertThat(AuthPaths.isPublic("POST", "/api/v1/auth/register")).isTrue();
        assertThat(AuthPaths.isPublic("GET", "/api/v1/auth/register")).isFalse();
        assertThat(AuthPaths.isPublic("POST", "/api/v1/auth/register/extra")).isFalse();
        assertThat(AuthPaths.isPublic("PUT", "/api/v1/roles/OPERATIONS")).isFalse();
        assertThat(AuthPaths.isPublic("GET", "/api/v1/customers/me")).isFalse();
    }
}
