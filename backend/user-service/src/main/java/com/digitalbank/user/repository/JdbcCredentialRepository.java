package com.digitalbank.user.repository;

import com.digitalbank.user.domain.LoginCredential;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** 字段与实验二 customer、credential 表一致；使用参数绑定查询身份标识。 */
public final class JdbcCredentialRepository implements CredentialRepository {
    private static final String FIND_CREDENTIAL = """
            SELECT c.customer_id, c.identity_id, cr.password_hash,
                   c.status AS customer_status, cr.status AS credential_status
              FROM customer c
              JOIN credential cr ON cr.customer_id = c.customer_id
             WHERE c.identity_id = ?
               AND cr.credential_type = 'USERNAME'
             ORDER BY cr.id
             LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<LoginCredential> findByIdentityId(String identityId) {
        // 当前接口按 identityId 登录，选取该客户最早建立的 USERNAME 凭据。
        List<LoginCredential> credentials = jdbcTemplate.query(
                FIND_CREDENTIAL,
                (row, index) -> new LoginCredential(
                        row.getString("customer_id"),
                        row.getString("identity_id"),
                        row.getString("password_hash"),
                        row.getString("customer_status"),
                        row.getString("credential_status")
                ),
                identityId
        );
        return credentials.stream().findFirst();
    }
}
