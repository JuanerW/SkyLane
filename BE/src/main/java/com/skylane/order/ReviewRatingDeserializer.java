package com.skylane.order;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/** A star rating must be an integer JSON number; never silently truncate 4.9 to 4. */
public class ReviewRatingDeserializer extends StdDeserializer<Integer> {
    public ReviewRatingDeserializer() {
        super(Integer.class);
    }

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
        return parser.getIntValue();
    }
}
