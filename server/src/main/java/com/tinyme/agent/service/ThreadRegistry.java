package com.tinyme.agent.service;

import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-turn mapping from Managed Agents thread IDs to the agent that owns them. */
final class ThreadRegistry {
    private final String coordinator;
    private final Map<String, String> agentsByThread = new ConcurrentHashMap<>();

    ThreadRegistry(String coordinator) {
        this.coordinator = coordinator;
    }

    void observe(JsonNode event) {
        String type = text(event, "type");
        if (type == null || !type.startsWith("session.thread_")) return;
        String threadId = firstText(event, "session_thread_id", "thread_id");
        String agent = firstText(event, "agent_name");
        if (agent == null) agent = firstText(event.path("thread"), "agent_name", "name");
        register(threadId, agent);
    }

    void register(String threadId, String agent) {
        if (threadId != null && !threadId.isBlank() && agent != null && !agent.isBlank()) {
            agentsByThread.put(threadId, agent);
        }
    }

    String agentFor(String threadId) {
        if (threadId == null || threadId.isBlank()) return coordinator;
        return agentsByThread.getOrDefault(threadId, "unknown-agent");
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) return value;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank()
                ? value.stringValue() : null;
    }
}
