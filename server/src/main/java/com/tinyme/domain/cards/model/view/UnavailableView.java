package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UnavailableView(@JsonProperty("block_type") String blockType, String error) implements BlockView {
}
