package com.digitalbank.common.auth;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.common.trace.TraceIds;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Servlet 服务独立校验令牌，不能把客户端传入的身份头当作认证结果。 */
public final class RemoteSessionVerifier implements SessionVerifier {
    private final RestClient client;
    private final InternalServiceToken serviceToken;

    public RemoteSessionVerifier(String userServiceUrl, InternalServiceToken serviceToken) {
        this.serviceToken = serviceToken;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder()
                .baseUrl(URI.create(userServiceUrl).toString())
                .requestFactory(factory)
                .build();
    }

    @Override
    public SessionIdentity verify(String token, String traceId) {
        String secret = serviceToken.configuredValue();
        try {
            ApiResponse<SessionIdentity> response = client.get()
                    .uri("/internal/v1/auth/session")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(InternalServiceToken.HEADER, secret)
                    .header(TraceIds.HEADER, TraceIds.normalize(traceId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            if (response == null || !"SUCCEEDED".equals(response.code())
                    || response.data() == null || !response.data().isValid()) {
                throw unavailable();
            }
            return response.data();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                throw AuthTokens.unauthenticated();
            }
            throw unavailable();
        } catch (RestClientException ex) {
            // 服务不可达时拒绝放行，不把依赖故障伪装成密码错误。
            throw unavailable();
        }
    }

    private BizException unavailable() {
        return new BizException(ErrorCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试");
    }
}
