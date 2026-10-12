package com.tinyme.domain.cards.model.view;

import java.time.LocalDate;

public record Slot(LocalDate period, String state, boolean done, String activity) {
}
