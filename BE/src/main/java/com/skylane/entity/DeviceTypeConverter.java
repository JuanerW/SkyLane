package com.skylane.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class DeviceTypeConverter implements AttributeConverter<DeviceType, String> {
    @Override public String convertToDatabaseColumn(DeviceType value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public DeviceType convertToEntityAttribute(String value) {
        return value == null ? null : DeviceType.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
