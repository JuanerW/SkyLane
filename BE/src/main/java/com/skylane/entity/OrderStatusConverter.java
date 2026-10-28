package com.skylane.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class OrderStatusConverter implements AttributeConverter<OrderStatus, String> {
    @Override public String convertToDatabaseColumn(OrderStatus value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public OrderStatus convertToEntityAttribute(String value) {
        return value == null ? null : OrderStatus.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
