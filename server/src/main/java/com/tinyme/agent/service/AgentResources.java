package com.tinyme.agent.service;

import com.tinyme.agent.model.AgentResourceIds;
import com.tinyme.agent.repository.AgentSettingsRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class AgentResources {
    private final AgentSettingsRepository settings;

    public AgentResources(AgentSettingsRepository settings) {
        this.settings = settings;
    }

    /** Reads the saved resources on demand, after startup provisioning has completed. */
    public AgentResourceIds load() {
        Map<String, Object> saved = settings.resourceSettings();
        Object environment = required(saved, "env.default");
        Object agent = required(saved, "agent.chat");
        Object memory = required(saved, "memory.main");
        return new AgentResourceIds(id(environment, "env.default"), id(agent, "agent.chat"),
                version(agent), id(memory, "memory.main"));
    }

    private static Object required(Map<String, Object> saved, String key) {
        Object value = saved.get(key);
        if (value == null) {
            throw invalid(key, "missing resource setting");
        }
        return value;
    }

    private static String id(Object value, String key) {
        Object candidate = value instanceof Map<?, ?> record ? record.get("id") : value;
        if (!(candidate instanceof String id) || !id.matches("[A-Za-z0-9_-]+")) {
            throw invalid(key, "missing or invalid ID");
        }
        return id;
    }

    private static int version(Object agent) {
        if (agent instanceof Map<?, ?> record && record.get("version") instanceof Number number) {
            try {
                int version = new BigDecimal(number.toString()).intValueExact();
                if (version > 0) {
                    return version;
                }
            } catch (ArithmeticException | NumberFormatException ignored) {
                // Reject fractional, overflowing, or otherwise invalid saved versions.
            }
        }
        throw invalid("agent.chat", "missing or invalid positive integer version");
    }

    private static IllegalStateException invalid(String key, String reason) {
        return new IllegalStateException("Agent resources unavailable: " + key + " has " + reason
                + ". Run agent setup (TINYME_AGENT_SETUP_ENABLED=true) before starting a turn.");
    }
}
