package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.Period;

public sealed interface MetricBlock extends Block
        permits StatBlock, ProgressBlock, ChartBlock, CheckinsBlock, BreakdownBlock {
    MetricSpec metric();
    Period period();
}
