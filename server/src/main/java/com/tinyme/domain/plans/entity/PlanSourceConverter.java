package com.tinyme.domain.plans.entity;

import com.tinyme.domain.plans.model.PlanSource;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class PlanSourceConverter implements AttributeConverter<PlanSource, String> {
    @Override public String convertToDatabaseColumn(PlanSource value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
    @Override public PlanSource convertToEntityAttribute(String value) {
        return value == null ? null : PlanSource.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
