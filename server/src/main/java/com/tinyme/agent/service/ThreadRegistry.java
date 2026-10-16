package com.tinyme.agent.service;

import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-turn mapping from Managed Agents thread IDs to the agent that owns them. */
final class ThreadRegistry {
    private final String coordinator;
    private final String coordinatorAgentId;
    private final String gmailAgentId;
    private final Map<String, String> agentsByThread = new ConcurrentHashMap<>();

    ThreadRegistry(String coordinator) {
        this(coordinator, null);
    }

    ThreadRegistry(String coordinator, String gmailAgentId) {
        this(coordinator, null, gmailAgentId);
    }

    ThreadRegistry(String coordinator, String coordinatorAgentId, String gmailAgentId) {
        this.coordinator = coordinator;
        this.coordinatorAgentId = coordinatorAgentId;
        this.gmailAgentId = gmailAgentId;
    }

    void observe(JsonNode event) {
        String type = text(event, "type");
        if (type == null) return;
        String threadId = firstText(event, "session_thread_id", "thread_id");
        if (threadId == null) return;
        String agentId = firstText(event, "agent_id", "agentId");
        if (agentId == null) agentId = firstText(event.path("agent"), "id", "agent_id");
        if (agentId == null) agentId = firstText(event.path("thread"), "agent_id");
        // IDs are provisioned by TinyMe and unique. When a stream supplies one, never let a display name override it.
        String announcedName = firstText(event, "agent_name");
        if (announcedName == null) announcedName = firstText(event.path("thread"), "agent_name", "name");
        String agent = agentId != null ? agentForId(agentId, announcedName) : announcedName;
        register(threadId, agent);
    }

    private String agentForId(String agentId, String announcedName) {
        if (gmailAgentId != null && gmailAgentId.equals(agentId)) return "tinyme-gmail";
        if (coordinatorAgentId != null) {
            return coordinatorAgentId.equals(agentId) ? coordinator : "unknown-agent";
        }
        if (announcedName != null && !"tinyme-gmail".equals(announcedName)) return announcedName;
        return "unknown-agent";
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
