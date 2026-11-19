package com.digitalbank.transaction.service;

import com.digitalbank.transaction.dto.TransactionDtos.Command;

/** 出账资金命令同形构造：取款/转账的 accountId 与 payerAccountId 均填付款账户（交接文档 7.1）。 */
final class OutgoingCommands {
    private OutgoingCommands() { }

    static Command command(String tradeNo, String payerAccountId, String payeeAccountId, String amount) {
        return new Command(tradeNo, payerAccountId, payeeAccountId, payerAccountId, amount, "CNY");
    }
}
