package com.digitalbank.user.repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;

/** Verify the actual deployment fixture remains compatible with S1's encoder. */
class DemoSeedAuthenticationTest {
    @ParameterizedTest(name = "deployment seed for {0}")
    @CsvSource({"zhangsan,demo123", "lisi,demo123", "admin,admin123"})
    void deploymentSeedUsesTheConfiguredPasswordAlgorithm(String username, String password) throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("deploy/sql/init-data.sql"))) {
            directory = directory.getParent();
        }
        assertThat(directory).as("repository with deployment fixture").isNotNull();
        String sql = Files.readString(directory.resolve("deploy/sql/init-data.sql"), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("\\('CUS-[^']*',\\s*'" + Pattern.quote(username)
                + "',\\s*'([^']+)',\\s*'USERNAME'").matcher(sql);
        assertThat(matcher.find()).as("credential fixture for %s", username).isTrue();
        assertThat(new BCryptPasswordEncoder(12).matches(password, matcher.group(1))).isTrue();
    }
}
