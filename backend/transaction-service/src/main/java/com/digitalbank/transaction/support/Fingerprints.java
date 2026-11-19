package com.digitalbank.transaction.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 请求内容指纹：交接文档 7.1 固定的 SHA-256 紧凑 JSON 数组，字段顺序不可调整。 */
public final class Fingerprints {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fingerprints() { }

    public static String requestHash(String businessType, String accountId, String payerAccountId,
            String payeeAccountId, String amount, String currency, String purpose) {
        try {
            String json = MAPPER.writeValueAsString(List.of(
                    businessType,
                    empty(accountId),
                    empty(payerAccountId),
                    empty(payeeAccountId),
                    amount,
                    currency,
                    purpose == null ? "" : purpose.trim()));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算请求内容指纹", e);
        }
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
