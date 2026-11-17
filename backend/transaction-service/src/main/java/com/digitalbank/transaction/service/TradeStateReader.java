package com.digitalbank.transaction.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.transaction.client.AccountClient;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import com.digitalbank.transaction.dto.TransactionDtos.TradeView;
import org.springframework.stereotype.Component;

/**
 * 交易当前状态读取：优先 S2 主库资金状态；账户服务暂不可用时按本地原单解释。
 * 对外查询与存款/取款/转账编排共用，保证对用户的状态口径一致。
 */
@Component
public class TradeStateReader {
    private final IdempotencyService idempotency;
    private final AccountClient account;

    public TradeStateReader(IdempotencyService idempotency, AccountClient account) {
        this.idempotency = idempotency;
        this.account = account;
    }

    public TradeView currentView(String tradeNo, String traceId) {
        try {
            Outcome outcome = account.outcome(tradeNo, traceId);
            return new TradeView(outcome.tradeNo(), outcome.status(), outcome.reasonCode());
        } catch (BizException unreachable) {
            IdempotencyService.TradeRow row = idempotency.readTrade(tradeNo);
            if (row == null) throw new BizException(ErrorCode.TRADE_NOT_FOUND, "交易不存在");
            return new TradeView(row.tradeNo(), row.status(), row.failureReason());
        }
    }
}
