package com.tinyme.domain.cards.entity;

import com.tinyme.domain.cards.model.CardStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class CardStatusConverter implements AttributeConverter<CardStatus, String> {
    @Override
    public String convertToDatabaseColumn(CardStatus status) {
        return status == null ? null : status.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public CardStatus convertToEntityAttribute(String value) {
        return value == null ? null : CardStatus.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
