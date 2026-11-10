package com.digitalbank.gateway.config;

import com.digitalbank.common.auth.InternalServiceToken;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration(proxyBeanMethods = false)
public class AuthGatewayConfiguration {
    @Bean
    public InternalServiceToken internalServiceToken(
            @Value("${auth.internal-token:${BANK_INTERNAL_TOKEN:}}") String token) {
        return new InternalServiceToken(token);
    }

    @Bean
    public WebClient authWebClient(
            @Value("${auth.user-service-url:${USER_SERVICE_URL:http://localhost:8081}}") String url) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(Duration.ofSeconds(3));
        return WebClient.builder()
                .baseUrl(url)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
