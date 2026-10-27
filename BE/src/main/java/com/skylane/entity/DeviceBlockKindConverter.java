package com.skylane.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class DeviceBlockKindConverter implements AttributeConverter<DeviceBlockKind, String> {
    @Override public String convertToDatabaseColumn(DeviceBlockKind value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public DeviceBlockKind convertToEntityAttribute(String value) {
        return value == null ? null : DeviceBlockKind.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
