package com.tinyme.domain.plans.entity;

import com.tinyme.domain.plans.model.OccurrenceExceptionStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class OccurrenceExceptionStatusConverter implements AttributeConverter<OccurrenceExceptionStatus, String> {
    @Override public String convertToDatabaseColumn(OccurrenceExceptionStatus value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public OccurrenceExceptionStatus convertToEntityAttribute(String value) {
        return value == null ? null : OccurrenceExceptionStatus.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
