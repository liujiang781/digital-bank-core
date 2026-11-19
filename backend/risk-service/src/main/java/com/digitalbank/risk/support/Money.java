package com.digitalbank.risk.support;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

public final class Money {
    public static final Pattern TWO_DECIMALS = Pattern.compile("[0-9]{1,16}\\.[0-9]{2}");

    private Money() { }

    public static BigDecimal parsePositive(String value) {
        if (value == null || !TWO_DECIMALS.matcher(value).matches()) throw invalid();
        BigDecimal amount = new BigDecimal(value);
        if (amount.signum() <= 0) throw invalid();
        return amount;
    }

    public static String format(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static BizException invalid() {
        return new BizException(ErrorCode.INVALID_ARGUMENT, "金额必须为正数、两位小数字符串，且不超过 DECIMAL(18,2) 范围");
    }
}
