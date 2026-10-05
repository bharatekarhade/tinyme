package com.tinyme.agent;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.URI;
import java.util.Map;

@Component
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "tinyme.agent.setup-enabled", havingValue = "true", matchIfMissing = true)
class AgentSetupRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentSetupRunner.class);
    private final DataSource datasource;
    private final String apiKey;
    private final Map<String, String> configured;
    private final boolean allowNewMemoryStore;

    AgentSetupRunner(DataSource datasource,
                     @Value("${tinyme.agent.api-key}") String apiKey,
                     @Value("${tinyme.agent.environment-id}") String environmentId,
                     @Value("${tinyme.agent.agent-id}") String agentId,
                     @Value("${tinyme.agent.memory-store-id}") String memoryStoreId,
                     @Value("${tinyme.agent.allow-new-memory-store:false}") boolean allowNewMemoryStore) {
        this.datasource = datasource;
        this.apiKey = apiKey;
        this.configured = Map.of("ANTHROPIC_ENVIRONMENT_ID", environmentId, "ANTHROPIC_AGENT_ID", agentId,
                "ANTHROPIC_MEMORY_STORE_ID", memoryStoreId);
        this.allowNewMemoryStore = allowNewMemoryStore;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (apiKey.isBlank()) throw new IllegalStateException("Agent setup requires ANTHROPIC_API_KEY");
        try (var connection = datasource.getConnection()) {
            new AgentProvisioner(new ManagedAgentApi(apiKey, URI.create("https://api.anthropic.com")), allowNewMemoryStore)
                    .provision(connection, configured);
        }
        log.info("Managed Agents setup complete; resource IDs and seed hashes are saved in settings");
    }
}
