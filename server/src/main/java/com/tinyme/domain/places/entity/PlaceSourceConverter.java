package com.tinyme.domain.places.entity;

import com.tinyme.domain.places.model.PlaceSource;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class PlaceSourceConverter implements AttributeConverter<PlaceSource, String> {
    @Override public String convertToDatabaseColumn(PlaceSource value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    @Override public PlaceSource convertToEntityAttribute(String value) {
        return value == null ? null : PlaceSource.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
