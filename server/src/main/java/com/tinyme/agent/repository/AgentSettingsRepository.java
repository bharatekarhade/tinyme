package com.tinyme.agent.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AgentSettingsRepository {
    private final AgentSettingsJpaRepository settings;

    AgentSettingsRepository(AgentSettingsJpaRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resourceSettings() {
        Map<String, Object> values = new LinkedHashMap<>();
        settings.findAllById(List.of("env.default", "agent.chat", "agent.gmail", "memory.main"))
                .forEach(setting -> values.put(setting.getKey(), setting.getValue()));
        return values;
    }

    @Transactional(readOnly = true)
    public Optional<String> gmailAgentId() {
        return agentId("agent.gmail");
    }

    public Optional<String> chatAgentId() {
        return agentId("agent.chat");
    }

    private Optional<String> agentId(String key) {
        return settings.findById(key).map(setting -> {
            Object value = setting.getValue();
            Object id = value instanceof Map<?, ?> record ? record.get("id") : value;
            if (!(id instanceof String text) || text.isBlank()) {
                throw new IllegalStateException("Setting " + key + " has no valid agent ID");
            }
            return text;
        });
    }

    @Transactional(readOnly = true)
    public Optional<String> userTimezone() {
        return settings.findById("user.tz")
                .map(setting -> {
                    if (!(setting.getValue() instanceof String timezone) || timezone.isBlank()) {
                        throw new IllegalStateException("Setting user.tz must be a non-empty JSON string");
                    }
                    return timezone;
                });
    }
}
