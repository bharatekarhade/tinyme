package com.tinyme.agent.model;

public record AgentResourceIds(String environmentId, String agentId,
                               int agentVersion, String memoryStoreId) {
}
