package com.tinyme.domain.entries.model;

public enum AggregateMetric {
    COUNT(false), SUM(true), AVG(true), MIN(true), MAX(true);

    private final boolean fieldRequired;

    AggregateMetric(boolean fieldRequired) {
        this.fieldRequired = fieldRequired;
    }

    public boolean requiresField() {
        return fieldRequired;
    }

    public String sqlExpression(String alias) {
        return switch (this) {
            case COUNT -> "COALESCE(SUM(" + alias + ".quantity), 0)";
            case SUM -> "COALESCE(SUM(" + numericExpression(alias) + "), 0)";
            case AVG -> "ROUND(AVG(" + numericExpression(alias) + "), 2)";
            case MIN -> "MIN(" + numericExpression(alias) + ")";
            case MAX -> "MAX(" + numericExpression(alias) + ")";
        };
    }

    private String numericExpression(String alias) {
        return "CASE WHEN jsonb_typeof(" + alias + ".data -> CAST(:field AS text)) = 'number' "
                + "THEN (" + alias + ".data ->> CAST(:field AS text))::numeric END";
    }

    public String sqlFieldFilter(String alias) {
        return requiresField()
                ? " AND jsonb_typeof(" + alias + ".data -> CAST(:field AS text)) = 'number'"
                : "";
    }
}
