package com.skylane.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class DeviceStatusConverter implements AttributeConverter<DeviceStatus, String> {
    @Override public String convertToDatabaseColumn(DeviceStatus value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public DeviceStatus convertToEntityAttribute(String value) {
        return value == null ? null : DeviceStatus.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
