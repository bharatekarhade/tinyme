package com.tinyme.domain.plans.entity;

import com.tinyme.domain.plans.model.PlanStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class PlanStatusConverter implements AttributeConverter<PlanStatus, String> {
    @Override public String convertToDatabaseColumn(PlanStatus value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public PlanStatus convertToEntityAttribute(String value) {
        return value == null ? null : PlanStatus.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
