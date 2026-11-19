package com.digitalbank.user.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅在 auth.demo.enabled=true 时启用额外的联调凭据，密码由环境变量注入。 */
@ConfigurationProperties("auth.demo")
public record DemoAccountsProperties(Map<String, Account> users) {
    public DemoAccountsProperties {
        users = users == null ? Map.of() : Map.copyOf(users);
    }

    public record Account(String customerId, String password) {
        @Override
        public String toString() {
            return "DemoAccount[redacted]";
        }
    }
}
