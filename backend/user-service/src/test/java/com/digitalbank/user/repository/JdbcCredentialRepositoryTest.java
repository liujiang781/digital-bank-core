package com.digitalbank.user.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用 H2 的 MySQL 模式验证查询和字段映射；真实 MySQL 初始化由 S4 联调验收。 */
class JdbcCredentialRepositoryTest {
    private JdbcTemplate jdbc;
    private JdbcCredentialRepository repository;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbc.execute("""
                CREATE TABLE customer (
                    customer_id VARCHAR(32) PRIMARY KEY,
                    identity_id VARCHAR(64) UNIQUE,
                    status VARCHAR(16)
                )
                """);
        jdbc.execute("""
                CREATE TABLE credential (
                    id BIGINT PRIMARY KEY,
                    customer_id VARCHAR(32),
                    username VARCHAR(50),
                    password_hash VARCHAR(255),
                    credential_type VARCHAR(16),
                    status VARCHAR(16)
                )
                """);
        jdbc.update("INSERT INTO customer VALUES (?, ?, ?)", "C1", "identity-1", "NORMAL");
        jdbc.update("INSERT INTO credential VALUES (?, ?, ?, ?, ?, ?)",
                1, "C1", "different-username", "stored-hash", "USERNAME", "NORMAL");
        repository = new JdbcCredentialRepository(jdbc);
    }

    @Test
    void queryUsesCustomerIdentityAndMapsBothStatuses() {
        var credential = repository.findByIdentityId("identity-1").orElseThrow();
        assertThat(credential.customerId()).isEqualTo("C1");
        assertThat(credential.passwordHash()).isEqualTo("stored-hash");
        assertThat(credential.isUsable()).isTrue();
        assertThat(repository.findByIdentityId("different-username")).isEmpty();
        jdbc.update("UPDATE credential SET status = 'LOCKED' WHERE id = 1");
        assertThat(repository.findByIdentityId("identity-1").orElseThrow().isUsable()).isFalse();
    }

    @Test
    void identityIsBoundAsParameter() {
        assertThat(repository.findByIdentityId("' OR '1'='1")).isEmpty();
    }
}
