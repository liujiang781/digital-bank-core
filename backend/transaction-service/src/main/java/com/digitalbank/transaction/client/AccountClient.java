package com.digitalbank.transaction.client;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.transaction.dto.TransactionDtos.Command;
import com.digitalbank.transaction.dto.TransactionDtos.DepositResult;
import com.digitalbank.transaction.dto.TransactionDtos.Failure;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import com.digitalbank.transaction.dto.TransactionDtos.Snapshot;
import com.digitalbank.transaction.dto.TransactionDtos.SnapshotRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * S2 内部调用客户端：连接超时 2 秒、响应超时 5 秒；可重试命令最多自动重试 3 次（1/2/4 秒），
 * 每次重试前先查询资金结果，400/401/403/409/404 不重试（交接文档第 8 节）。
 */
@Component
public class AccountClient {
    private static final Logger log = LoggerFactory.getLogger(AccountClient.class);
    private static final ParameterizedTypeReference<ApiResponse<Outcome>> OUTCOME_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final long[] BACKOFF_MILLIS = {1000, 2000, 4000};
    private static final int MAX_RETRIES = 3;

    private final RestClient rest;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper();

    public AccountClient(@Value("${account.base-url}") String baseUrl,
            @Value("${account.internal-token:}") String token) {
        this.token = token == null ? "" : token;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(2000));
        factory.setReadTimeout(Duration.ofMillis(5000));
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }

    public Snapshot snapshot(SnapshotRequest body, String traceId) {
        ApiResponse<Snapshot> response = post("/internal/v1/accounts/snapshots", body, traceId,
                new ParameterizedTypeReference<ApiResponse<Snapshot>>() { });
        return response.data();
    }

    /** 账户侧受理存款；重复相同受理返回当前状态，不重复修改受理时间。 */
    public Outcome acceptDeposit(Command command, String tradeNo, String traceId) {
        return sendCommand("/internal/v1/postings/deposit-acceptances", command, tradeNo, traceId);
    }

    /** 渠道结果上报（SUCCESS/FAILED/UNKNOWN 三类统一入口）。 */
    public Outcome depositResult(DepositResult receipt, String tradeNo, String traceId) {
        return sendCommand("/internal/v1/postings/deposit-results", receipt, tradeNo, traceId);
    }

    /** 受理前拒绝（风险拒绝，evidenceType=RISK_EVENT）。 */
    public Outcome recordFailure(Failure failure, String tradeNo, String traceId) {
        return sendCommand("/internal/v1/postings/failures", failure, tradeNo, traceId);
    }

    /** 主库读取当前资金状态；查询失败由调用方按可查询提示降级。 */
    public Outcome outcome(String tradeNo, String traceId) {
        try {
            ApiResponse<Outcome> response = rest.get().uri("/internal/v1/postings/{tradeNo}", tradeNo)
                    .headers(headers -> internalHeaders(headers, traceId))
                    .retrieve()
                    .toEntity(OUTCOME_TYPE)
                    .getBody();
            if (response == null || response.data() == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "账户服务返回空响应");
            }
            return response.data();
        } catch (HttpStatusCodeException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "账户服务连接失败或超时");
        }
    }

    private Outcome sendCommand(String path, Object body, String tradeNo, String traceId) {
        BizException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                // 每次重试前先查询资金结果；效果已确定或已未知时不再重发同一命令
                Outcome current = queryQuietly(tradeNo, traceId);
                if (current != null && !"ACCEPTED".equals(current.status())
                        && !"PROCESSING".equals(current.status())) {
                    return current;
                }
                sleep(BACKOFF_MILLIS[attempt - 1]);
            }
            try {
                ApiResponse<Outcome> response = post(path, body, traceId, OUTCOME_TYPE);
                return response.data();
            } catch (BizException e) {
                last = e;
                if (!isRetryable(e)) throw e;
                log.warn("账户服务命令第 {} 次失败，将按规则重试: tradeNo={}, path={}", attempt + 1, tradeNo, path);
            }
        }
        throw last != null ? last : new BizException(ErrorCode.INTERNAL_ERROR, "账户服务调用失败");
    }

    private Outcome queryQuietly(String tradeNo, String traceId) {
        try {
            return outcome(tradeNo, traceId);
        } catch (BizException e) {
            return null;
        }
    }

    private <T> ApiResponse<T> post(String path, Object body, String traceId,
            ParameterizedTypeReference<ApiResponse<T>> type) {
        try {
            return rest.post().uri(path)
                    .headers(headers -> internalHeaders(headers, traceId))
                    .body(body)
                    .retrieve()
                    .toEntity(type)
                    .getBody();
        } catch (HttpStatusCodeException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "账户服务连接失败或超时");
        }
    }

    private void internalHeaders(HttpHeaders headers, String traceId) {
        headers.set("X-Internal-Token", token);
        headers.set(TraceIds.HEADER, TraceIds.normalize(traceId));
    }

    private BizException translate(HttpStatusCodeException e) {
        try {
            JsonNode node = mapper.readTree(e.getResponseBodyAsString());
            String code = node.path("code").asText("");
            String message = node.path("message").asText("账户服务返回错误");
            try {
                return new BizException(ErrorCode.valueOf(code), message);
            } catch (IllegalArgumentException unknown) {
                return new BizException(ErrorCode.INTERNAL_ERROR, message);
            }
        } catch (Exception parseFailure) {
            return new BizException(ErrorCode.INTERNAL_ERROR, "账户服务返回异常响应");
        }
    }

    private boolean isRetryable(BizException e) {
        return e.getErrorCode() == ErrorCode.INTERNAL_ERROR;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "重试等待被中断");
        }
    }
}
