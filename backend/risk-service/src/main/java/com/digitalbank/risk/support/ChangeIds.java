package com.digitalbank.risk.support;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 风控写操作幂等工具：UUID 请求号校验 + 紧凑 JSON 固定顺序 SHA-256 指纹。 */
public final class ChangeIds {
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChangeIds() { }

    public static String uuid(String value) {
        if (value == null || !UUID_TEXT.matcher(value).matches()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "requestId 必须为 UUID");
        }
        return UUID.fromString(value).toString();
    }

    /** 告警处置指纹：动作 + 事件 + 结论 + 依据，固定顺序。 */
    public static String disposeHash(String eventId, String decision, String basis) {
        return hash(List.of("DISPOSE", empty(eventId), empty(decision), clean(basis)));
    }

    /** 规则变更指纹：变更类型 + 规则ID + 拟变更 JSON + 原因。 */
    public static String ruleHash(String ruleId, String changeJson, String reason) {
        return hash(List.of("RULE_CHANGE", empty(ruleId), empty(changeJson), clean(reason)));
    }

    /** 名单变更指纹：变更类型 + 名单类型 + 对象类型 + 对象标识 + 操作 + 原因。 */
    public static String listHash(String listType, String subjectType, String subjectId,
            String op, String reason) {
        return hash(List.of("LIST_CHANGE", empty(listType), empty(subjectType), empty(subjectId),
                empty(op), clean(reason)));
    }

    /** 审批指纹：动作 + 变更单 + 结论 + 意见。 */
    public static String approveHash(String changeId, String decision, String note) {
        return hash(List.of("APPROVAL", empty(changeId), empty(decision), clean(note)));
    }

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "无法序列化变更内容");
        }
    }

    private static String hash(List<String> values) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsString(values).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "无法计算请求内容指纹");
        }
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
