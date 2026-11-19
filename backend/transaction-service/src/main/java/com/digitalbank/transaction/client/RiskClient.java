package com.digitalbank.transaction.client;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.transaction.dto.RiskDtos.CheckRequest;
import com.digitalbank.transaction.dto.RiskDtos.Decision;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** 风控内部调用客户端（POST /internal/v1/risk/checks）；不自动重试，避免重复落风险事件。 */
@Component
public class RiskClient {
    private static final ParameterizedTypeReference<ApiResponse<Decision>> DECISION_TYPE =
            new ParameterizedTypeReference<>() { };

    private final RestClient rest;
    private final String token;

    public RiskClient(@Value("${risk.base-url}") String baseUrl,
            @Value("${risk.internal-token:}") String token) {
        this.token = token == null ? "" : token;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(2000));
        factory.setReadTimeout(Duration.ofMillis(5000));
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }

    public Decision check(CheckRequest request, String traceId) {
        try {
            ApiResponse<Decision> response = rest.post().uri("/internal/v1/risk/checks")
                    .headers(headers -> {
                        headers.set("X-Internal-Token", token);
                        headers.set(TraceIds.HEADER, TraceIds.normalize(traceId));
                    })
                    .body(request)
                    .retrieve()
                    .toEntity(DECISION_TYPE)
                    .getBody();
            if (response == null || response.data() == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "风控服务返回空响应");
            }
            return response.data();
        } catch (HttpStatusCodeException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "风控服务返回错误，稍后沿原单恢复");
        } catch (ResourceAccessException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "风控服务连接失败或超时，稍后沿原单恢复");
        }
    }
}
