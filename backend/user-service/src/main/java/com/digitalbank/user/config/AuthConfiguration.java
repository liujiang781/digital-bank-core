package com.digitalbank.user.config;

import com.digitalbank.user.repository.CredentialRepository;
import com.digitalbank.user.repository.DemoCredentialRepository;
import com.digitalbank.user.repository.JdbcCredentialRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AuthConfiguration {
    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @ConditionalOnProperty(name = "auth.credential-store", havingValue = "demo", matchIfMissing = true)
    public CredentialRepository demoCredentialRepository(
            @Value("${auth.demo.enabled:false}") boolean enabled,
            @Value("${auth.demo.identity-id:demo-user}") String identityId,
            @Value("${auth.demo.password:}") String password,
            PasswordEncoder encoder) {
        return new DemoCredentialRepository(enabled, identityId, password, encoder);
    }

    @Bean
    @ConditionalOnProperty(name = "auth.credential-store", havingValue = "mysql")
    public CredentialRepository jdbcCredentialRepository(
            @Value("${auth.database.url}") String url,
            @Value("${auth.database.username}") String username,
            @Value("${auth.database.password}") String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        // 原型阶段按需建立连接；后续由 S4 统一配置连接池和数据库权限。
        return new JdbcCredentialRepository(new JdbcTemplate(dataSource));
    }
}
