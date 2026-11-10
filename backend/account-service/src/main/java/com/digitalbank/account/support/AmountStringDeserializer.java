package com.digitalbank.account.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;

/** Keep money as text at the HTTP boundary; Jackson's scalar coercion would accept JSON numbers. */
public class AmountStringDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            throw MismatchedInputException.from(parser, String.class, "金额必须为两位小数字符串");
        }
        return parser.getText();
    }
}
