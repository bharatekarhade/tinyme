package com.tinyme.domain.cards.model;

public sealed interface Block permits MetricBlock, GoalsBlock, LatestBlock {
    String label();
}
