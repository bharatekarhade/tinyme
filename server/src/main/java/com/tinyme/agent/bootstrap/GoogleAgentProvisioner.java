package com.tinyme.agent.bootstrap;

import com.tinyme.agent.client.ManagedAgentApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Re-publishes the chat agent after Google connection state changes. */
@Component
@ConditionalOnProperty(name = "tinyme.agent.setup-enabled", havingValue = "true", matchIfMissing = true)
public class GoogleAgentProvisioner {
    private static final Logger log = LoggerFactory.getLogger(GoogleAgentProvisioner.class);
    private final DataSource datasource;
    private final ManagedAgentApi api;
    private final Map<String, String> configured;
    private final JdbcTemplate jdbc;

    public GoogleAgentProvisioner(DataSource datasource, ManagedAgentApi api,
                                  @Value("${tinyme.agent.environment-id:}") String environmentId,
                                  @Value("${tinyme.agent.agent-id:}") String agentId,
                                  @Value("${tinyme.agent.memory-store-id:}") String memoryStoreId,
                                  @Value("${tinyme.agent.calendar-agent-id:}") String calendarAgentId,
                                  @Value("${tinyme.agent.scanner-agent-id:}") String scannerAgentId) {
        this.datasource = datasource;
        this.jdbc = new JdbcTemplate(datasource);
        this.api = api;
        this.configured = Map.of("ANTHROPIC_ENVIRONMENT_ID", environmentId,
                "ANTHROPIC_AGENT_ID", agentId, "ANTHROPIC_MEMORY_STORE_ID", memoryStoreId,
                "ANTHROPIC_CALENDAR_AGENT_ID", calendarAgentId,
                "ANTHROPIC_SCANNER_AGENT_ID", scannerAgentId);
    }

    public void sync() throws Exception {
        try (var connection = datasource.getConnection()) {
            new AgentProvisioner(api).provision(connection, configured);
        }
    }

    /** Retries a failed or interrupted Google agent publication when the status screen is opened. */
    public String retryIfPending() {
        if (!"pending".equals(agentsStatus())) return "ready";
        try {
            sync();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // AgentProvisioner deliberately leaves google.agents=pending for a later retry.
            log.warn("Google agent publication remains pending ({})", failure.getClass().getSimpleName());
        }
        return agentsStatus();
    }

    public String agentsStatus() {
        String status = jdbc.query("SELECT value ->> 'status' FROM settings WHERE key='google.agents'",
                result -> result.next() ? result.getString(1) : null);
        return "ready".equals(status) ? "ready" : "pending";
    }
}
