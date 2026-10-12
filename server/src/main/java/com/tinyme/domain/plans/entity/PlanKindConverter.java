package com.tinyme.domain.plans.entity;

import com.tinyme.domain.plans.model.PlanKind;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class PlanKindConverter implements AttributeConverter<PlanKind, String> {
    @Override public String convertToDatabaseColumn(PlanKind value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public PlanKind convertToEntityAttribute(String value) {
        return value == null ? null : PlanKind.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
