package com.tinyme.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "settings")
public class SettingEntity {
    @Id
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Object value;

    protected SettingEntity() {
    }

    public String getKey() {
        return key;
    }

    public Object getValue() {
        return value;
    }
}
