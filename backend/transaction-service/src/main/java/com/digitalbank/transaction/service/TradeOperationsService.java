package com.digitalbank.transaction.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.transaction.channel.MockChannelService;
import com.digitalbank.transaction.channel.MockChannelService.ChannelRow;
import com.digitalbank.transaction.client.AccountClient;
import com.digitalbank.transaction.dto.TransactionDtos.ManualActionResult;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import com.digitalbank.transaction.dto.TransactionDtos.Release;
import com.digitalbank.transaction.dto.TransactionDtos.ReasonRequest;
import com.digitalbank.transaction.security.Actor;
import com.digitalbank.transaction.security.TransactionAccess;
import com.digitalbank.transaction.support.Fingerprints;
import com.digitalbank.transaction.support.Money;
import com.digitalbank.transaction.support.RequestIds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 运营待处理交易、人工核对与恢复重试（UC-TR-08/09）。
 *
 * 授权：只接受 S1 过滤器核验后的会话角色与账户范围，管理员一律拒绝；
 * 核对/恢复为写操作，仅 OPERATIONS 可执行，且交易账户必须在授权范围内。
 * 不凭人工判断改余额或终态：核对沿原单原渠道结果上报，恢复只引用已登记的明确失败依据。
 * 同身份+动作+requestId 幂等留痕；审计与幂等记录一起持久化。
 */
@Service
public class TradeOperationsService {
    private static final Logger log = LoggerFactory.getLogger(TradeOperationsService.class);
    private static final Set<String> PENDING_STATUSES = Set.of("UNKNOWN", "PROCESSING", "RECOVERING");
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED", "REJECTED");
    private static final String RECONCILE = "RECONCILE";
    private static final String RECOVER = "RECOVER";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final TransactionAccess access;
    private final MockChannelService channel;
    private final AccountClient account;
    private final DepositService depositService;
    private final ExternalTransferService externalTransferService;

    public TradeOperationsService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            TransactionAccess access, MockChannelService channel, AccountClient account,
            DepositService depositService, ExternalTransferService externalTransferService) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.access = access;
        this.channel = channel;
        this.account = account;
        this.depositService = depositService;
        this.externalTransferService = externalTransferService;
    }

    /** 人工核对：沿原单、原渠道标识查询明确结果并幂等上报；仍未知则保留原状。 */
    public ManualActionResult reconcile(Actor actor, String tradeNo, ReasonRequest body, String traceId) {
        return execute(actor, tradeNo, body, RECONCILE, traceId, (row, reason, tid) -> {
            if ("RECOVERING".equals(row.status())) {
                return new Action("RECOVERING", null, "USE_RECOVER", "交易已有明确失败依据，请使用恢复重试");
            }
            if (TERMINAL_STATUSES.contains(row.status())) {
                return new Action(row.status(), null, "ALREADY_TERMINAL", "交易已终态，无需核对");
            }
            if (!PENDING_STATUSES.contains(row.status())) {
                return new Action(row.status(), null, "NOT_PENDING", "当前状态无需人工核对");
            }
            ChannelRow record = channel.find(tradeNo);
            if (record == null) {
                if ("WITHDRAWAL".equals(row.businessType()) || "INTERNAL_TRANSFER".equals(row.businessType())) {
                    Outcome outcome = account.outcome(tradeNo, tid);
                    return new Action(outcome.status(), null, "LOCAL_OUTCOME_" + outcome.status(),
                            "本地交易无渠道记录，已按资金结果解释当前状态");
                }
                return new Action(row.status(), null, "NO_CHANNEL_RECORD", "渠道记录缺失，不凭人工判断改判");
            }
            // 主动核对沿原单一查一次；查询会按当前渠道模式把 UNKNOWN 收敛为明确结果。
            ChannelRow latest = MockChannelService.UNKNOWN.equals(record.result())
                    ? channel.queryForReconcile(tradeNo) : record;
            if (MockChannelService.UNKNOWN.equals(latest.result())) {
                return new Action(row.status(), latest.result(), "STILL_UNKNOWN", "渠道仍为未知，保留原占用与状态");
            }
            if ("EXTERNAL_TRANSFER".equals(row.businessType())) {
                externalTransferService.reportExternalResult(tradeNo, row.accountId(), row.amount(), latest, tid);
            } else {
                depositService.reportChannelResult(tradeNo, row.accountId(), row.amount(), latest, tid);
            }
            String status = localStatus(tradeNo, row.status());
            return new Action(status, latest.result(), "REPORTED_" + latest.result(),
                    "已沿原渠道结果幂等上报，当前状态：" + status);
        });
    }

    /** 恢复重试：仅对有明确失败依据的 RECOVERING 原单重发同一资金动作，S2 侧保证幂等。 */
    public ManualActionResult recover(Actor actor, String tradeNo, ReasonRequest body, String traceId) {
        return execute(actor, tradeNo, body, RECOVER, traceId, (row, reason, tid) -> {
            if (TERMINAL_STATUSES.contains(row.status())) {
                return new Action(row.status(), null, "ALREADY_TERMINAL", "交易已终态，无需恢复");
            }
            if (!"RECOVERING".equals(row.status())) {
                throw new BizException(ErrorCode.REQUEST_CONFLICT,
                        "仅 RECOVERING 且有明确失败依据的交易可重试恢复，请先人工核对");
            }
            if ("EXTERNAL_TRANSFER".equals(row.businessType())) {
                ChannelRow record = channel.find(tradeNo);
                if (record == null || !MockChannelService.FAILED.equals(record.result())
                        || record.evidenceId() == null || uncertain(record.reasonCode())) {
                    throw new BizException(ErrorCode.REQUEST_CONFLICT, "缺少明确的跨行失败回执，不能恢复资金");
                }
                externalTransferService.reportExternalResult(tradeNo, row.accountId(), row.amount(), record, tid);
            } else if ("WITHDRAWAL".equals(row.businessType()) || "INTERNAL_TRANSFER".equals(row.businessType())) {
                String reasonCode = row.failureReason();
                String evidenceId = jdbc.query("SELECT channel_evidence_id FROM `transaction` WHERE trade_no = ?",
                        (rs, i) -> rs.getString(1), tradeNo).stream().findFirst().orElse(null);
                if (reasonCode == null || reasonCode.isBlank() || uncertain(reasonCode)
                        || evidenceId == null || evidenceId.isBlank()) {
                    throw new BizException(ErrorCode.REQUEST_CONFLICT, "缺少明确的本地失败依据，不能释放占用或额度");
                }
                account.release(new Release(tradeNo, row.accountId(), Money.format(row.amount()), row.currency(),
                        reasonCode, "LOCAL_FAILURE", evidenceId), tradeNo, tid);
            } else {
                throw new BizException(ErrorCode.REQUEST_CONFLICT, "该业务类型不支持人工恢复重试");
            }
            String status = localStatus(tradeNo, "RECOVERING");
            return new Action(status, null, "RECOVERY_RETRIED",
                    "已按原失败依据幂等重试资金恢复，当前状态：" + status);
        });
    }

    @FunctionalInterface
    private interface ActionHandler {
        Action apply(IdempotencyService.TradeRow row, String reason, String traceId);
    }

    private record Action(String status, String channelResult, String conclusion, String message) { }

    /** 授权、幂等认领、业务动作、留痕的统一外壳：写动作放在幂等认领之后，避免重复执行。 */
    private ManualActionResult execute(Actor actor, String tradeNo, ReasonRequest body, String action,
            String traceId, ActionHandler handler) {
        IdempotencyService.TradeRow row = jdbc.query(
                "SELECT trade_no, identity_id, business_type, status, account_id, amount, currency, failure_reason "
                        + "FROM `transaction` WHERE trade_no = ?",
                (rs, index) -> new IdempotencyService.TradeRow(rs.getString("trade_no"),
                        rs.getString("identity_id"), rs.getString("business_type"), rs.getString("status"),
                        rs.getString("account_id"), rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getString("failure_reason")),
                tradeNo).stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.TRADE_NOT_FOUND, "交易不存在"));
        // 运营写操作仅 OPERATIONS，风控为只读；对象级账户范围先于幂等认领。
        access.requireOperator(actor, row.accountId(), false);
        String reason = requireReason(body == null ? null : body.reason());
        String requestId = RequestIds.resolve(body == null ? null : body.requestId(), null);
        String hash = Fingerprints.actionHash(action, tradeNo, reason);

        Claim claim = claim(actor.identityId(), action, requestId, hash, tradeNo);
        if (!claim.isNew()) return decode(claim.resultJson(), tradeNo, action, requestId, traceId);
        try {
            Action done = handler.apply(row, reason, traceId);
            ManualActionResult result = new ManualActionResult(tradeNo, action, done.status(), done.channelResult(),
                    done.conclusion(), requestId, traceId);
            audit(actor.identityId(), action.equals(RECONCILE) ? "TRADE_RECONCILE" : "TRADE_RECOVERY_RETRY",
                    "transaction", tradeNo, reason, done.conclusion());
            finish(actor.identityId(), action, requestId, encode(result));
            return result;
        } catch (RuntimeException failure) {
            // 认领记录保留空结果，下次同键请求仍可重试；资金效果由 S2 幂等保证。
            log.warn("人工操作未完成，允许同 requestId 重试: tradeNo={}, action={}", tradeNo, action, failure);
            throw failure;
        }
    }

    private ManualActionResult decode(String json, String tradeNo, String action, String requestId, String traceId) {
        try {
            ManualActionResult result = mapper.readValue(json, ManualActionResult.class);
            return new ManualActionResult(result.tradeNo(), result.action(), result.status(),
                    result.channelResult(), result.conclusion(), requestId, traceId);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "无法读取人工操作结果");
        }
    }

    private String encode(ManualActionResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "无法保存人工操作结果");
        }
    }

    private record Claim(boolean isNew, String resultJson) { }

    private Claim claim(String identityId, String action, String requestId, String hash, String tradeNo) {
        return transactions.execute(status -> {
            List<String> hashes = jdbc.query(
                    "SELECT request_hash FROM trade_manual_action WHERE identity_id = ? AND action = ? AND request_id = ?",
                    (rs, i) -> rs.getString(1), identityId, action, requestId);
            if (!hashes.isEmpty()) {
                if (!hash.equals(hashes.getFirst())) {
                    throw new BizException(ErrorCode.REQUEST_CONFLICT, "同一请求号的内容与原操作不一致");
                }
                List<String> results = jdbc.query(
                        "SELECT result_json FROM trade_manual_action WHERE identity_id = ? AND action = ? AND request_id = ?",
                        (rs, i) -> rs.getString(1), identityId, action, requestId);
                String stored = results.isEmpty() ? null : results.getFirst();
                // 空结果意味着上次动作未完成，允许沿同一 requestId 重试。
                return new Claim(stored == null || stored.isBlank(), stored);
            }
            jdbc.update("INSERT INTO trade_manual_action (identity_id, action, request_id, request_hash, trade_no, "
                    + "result_json) VALUES (?, ?, ?, ?, ?, '')", identityId, action, requestId, hash, tradeNo);
            return new Claim(true, null);
        });
    }

    private void finish(String identityId, String action, String requestId, String resultJson) {
        jdbc.update("UPDATE trade_manual_action SET result_json = ? WHERE identity_id = ? AND action = ? AND request_id = ?",
                resultJson, identityId, action, requestId);
    }

    private String localStatus(String tradeNo, String fallback) {
        try {
            return account.outcome(tradeNo, null).status();
        } catch (BizException unavailable) {
            log.warn("资金结果暂不可读，按本地原单解释: tradeNo={}", tradeNo);
            List<String> statuses = jdbc.queryForList(
                    "SELECT status FROM `transaction` WHERE trade_no = ?", String.class, tradeNo);
            return statuses.isEmpty() ? fallback : statuses.getFirst();
        }
    }

    private void audit(String operator, String action, String targetType, String targetRef, String reason,
            String result) {
        jdbc.update("INSERT INTO audit_log (operator_identity_id, `action`, target_type, target_ref, reason, result, "
                + "trade_no) VALUES (?, ?, ?, ?, ?, ?, ?)", operator, action, targetType, targetRef,
                bounded(reason), result, targetRef);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 255) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请填写查询或操作原因（不超过255字符）");
        }
        return reason.trim();
    }

    private static String bounded(String value) {
        if (value == null) return null;
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private static boolean uncertain(String reason) {
        return reason == null || reason.contains("TIMEOUT") || reason.contains("UNKNOWN")
                || reason.contains("RESPONSE_LOST") || reason.contains("CONNECTION_LOST")
                || reason.contains("NETWORK_ERROR");
    }
}
