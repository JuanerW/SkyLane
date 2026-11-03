package com.skylane.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class UserRoleConverter implements AttributeConverter<UserRole, String> {
    @Override public String convertToDatabaseColumn(UserRole value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public UserRole convertToEntityAttribute(String value) {
        return value == null ? null : UserRole.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
