package com.digitalbank.transaction.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;

/** 接口金额固定为两位小数字符串；反序列化时提前校验，保证 400 语义。 */
public class AmountStringDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String value = parser.getValueAsString();
        if (value != null && Money.TWO_DECIMALS.matcher(value).matches()) return value;
        throw JsonMappingException.from(parser, "金额必须为两位小数字符串");
    }
}
