package com.digitalbank.user;

import com.digitalbank.common.web.CommonWebConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 凭据仓库按配置显式创建，默认演示模式不自动连接数据库。
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@Import(CommonWebConfiguration.class)
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
