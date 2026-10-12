package com.tinyme.domain.plans.entity;

import com.tinyme.domain.plans.model.RepeatFrequency;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class RepeatFrequencyConverter implements AttributeConverter<RepeatFrequency, String> {
    @Override public String convertToDatabaseColumn(RepeatFrequency value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public RepeatFrequency convertToEntityAttribute(String value) {
        return value == null ? null : RepeatFrequency.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
