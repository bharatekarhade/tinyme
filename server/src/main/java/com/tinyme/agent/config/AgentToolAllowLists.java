package com.tinyme.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/** Server-side allow-lists for custom tool calls from each Managed Agent. */
@ConfigurationProperties(prefix = "tinyme.agent")
public class AgentToolAllowLists {
    private Map<String, List<String>> toolAllowLists = Map.of();

    public Map<String, List<String>> getToolAllowLists() { return toolAllowLists; }
    public void setToolAllowLists(Map<String, List<String>> toolAllowLists) {
        this.toolAllowLists = toolAllowLists == null ? Map.of() : Map.copyOf(toolAllowLists);
    }

    public boolean allows(String agent, String tool) {
        if (agent == null || tool == null) return false;
        List<String> tools = toolAllowLists.get(agent);
        return tools != null && (tools.contains("*") || tools.contains(tool));
    }

    public static AgentToolAllowLists forTests() {
        AgentToolAllowLists allowLists = new AgentToolAllowLists();
        allowLists.setToolAllowLists(Map.of("tinyme-chat", List.of("*")));
        return allowLists;
    }
}
