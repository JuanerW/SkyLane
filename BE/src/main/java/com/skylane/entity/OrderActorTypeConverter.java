package com.skylane.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class OrderActorTypeConverter implements AttributeConverter<OrderActorType, String> {
    @Override public String convertToDatabaseColumn(OrderActorType value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public OrderActorType convertToEntityAttribute(String value) {
        return value == null ? null : OrderActorType.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
