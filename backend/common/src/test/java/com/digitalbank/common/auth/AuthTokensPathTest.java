package com.digitalbank.common.auth;

import com.digitalbank.common.exception.BizException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Experiment 4: six independent paths plus header-count and token boundaries. */
class AuthTokensPathTest {
    private static final String TOKEN = "a".repeat(43);

    static Stream<Arguments> rejectedHeaders() {
        return Stream.of(
                Arguments.of("CT-S1-001 null headers", null),
                Arguments.of("CT-S1-002 empty headers", List.of()),
                Arguments.of("CT-S1-003 null value", Collections.singletonList(null)),
                Arguments.of("CT-S1-004 wrong scheme", List.of("Basic " + TOKEN)),
                Arguments.of("CT-S1-005 malformed token", List.of("Bearer invalid")),
                Arguments.of("CT-S1-007 duplicate headers", List.of("Bearer " + TOKEN, "Bearer " + TOKEN)),
                Arguments.of("CT-S1-008 short token", List.of("Bearer " + "a".repeat(42))),
                Arguments.of("CT-S1-009 long token", List.of("Bearer " + "a".repeat(44))),
                Arguments.of("CT-S1-010 illegal character", List.of("Bearer " + "a".repeat(42) + "+")),
                Arguments.of("CT-S1-011 trailing whitespace", List.of("Bearer " + TOKEN + " ")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedHeaders")
    void rejectsInvalidHeaders(String id, List<String> headers) {
        assertThatThrownBy(() -> AuthTokens.requireBearer(headers))
                .isInstanceOfSatisfying(BizException.class,
                        error -> assertThat(error.getErrorCode().status()).isEqualTo(401));
    }

    static Stream<Arguments> acceptedHeaders() {
        return Stream.of(
                Arguments.of("CT-S1-006 valid header", "Bearer " + TOKEN, TOKEN),
                Arguments.of("CT-S1-012 case insensitive scheme", "bEaReR " + TOKEN, TOKEN),
                Arguments.of("CT-S1-013 URL safe alphabet", "Bearer " + "_".repeat(42) + "-", "_".repeat(42) + "-"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedHeaders")
    void acceptsValidHeaders(String id, String header, String expected) {
        assertThat(AuthTokens.requireBearer(List.of(header))).isEqualTo(expected);
    }
}
