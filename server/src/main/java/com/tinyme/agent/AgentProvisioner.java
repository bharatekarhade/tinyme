package com.tinyme.agent;



import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.MessageDigest;
import java.util.HexFormat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

final class AgentProvisioner {
    private static final Logger log = LoggerFactory.getLogger(AgentProvisioner.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final boolean allowNewMemoryStore;
    private static final long LOCK_ID = 724198032;
    private final ManagedAgentApi api;
    private final PathMatchingResourcePatternResolver resources = new PathMatchingResourcePatternResolver();

    AgentProvisioner(ManagedAgentApi api) {
        this(api, false);
    }

    AgentProvisioner(ManagedAgentApi api, boolean allowNewMemoryStore) {
        this.api = api;
        this.allowNewMemoryStore = allowNewMemoryStore;
    }

    Map<String, String> provision(Connection connection, Map<String, String> configured) throws Exception {
        // Session lock plus autocommit keeps each ID durable before the next API call.
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException("Provisioning requires an autocommit connection");
        }
        try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_ID);
            try (var result = statement.executeQuery()) {
                result.next();
                if (!result.getBoolean(1)) {
                    throw new IllegalStateException("Another instance is provisioning agents; retry startup after it finishes");
                }
            }
        }
        try {
            var environment = yaml("seed/environments/default.yaml");
            var agent = yaml("seed/agents/chat.yaml");
            if (!environment.containsKey("config") || !agent.keySet().containsAll(java.util.List.of("name", "model", "system", "tools"))) {
                throw new IllegalStateException("Incomplete agent/environment seed configuration");
            }
            var memories = new LinkedHashMap<String, String>();
            for (var resource : resources.getResources("classpath*:seed/memories/*.md")) {
                memories.put("/me/" + resource.getFilename(), resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (memories.isEmpty()) throw new IllegalStateException("No classpath memory seeds found");
            var ids = new LinkedHashMap<String, String>();
            ids.put("ANTHROPIC_ENVIRONMENT_ID", ensure(connection, "env.default", "ANTHROPIC_ENVIRONMENT_ID",
                    "/v1/environments", environment, false, configured));
            ids.put("ANTHROPIC_AGENT_ID", ensure(connection, "agent.chat", "ANTHROPIC_AGENT_ID",
                    "/v1/agents", agent, false, configured));
            String store = ensure(connection, "memory.main", "ANTHROPIC_MEMORY_STORE_ID", "/v1/memory_stores",
                    Map.of("name", "tinyme-memory", "description", "User profile, preferences, rules, routines and people."), true, configured);
            ids.put("ANTHROPIC_MEMORY_STORE_ID", store);
            for (var memory : memories.entrySet()) {
                try {
                    api.request("POST", "/v1/memory_stores/" + store + "/memories",
                            Map.of("path", memory.getKey(), "content", memory.getValue()), true);
                } catch (ManagedAgentApi.ApiException error) {
                    if (error.status != 409 || !error.type.equals("memory_path_conflict_error")) throw error;
                    // Never overwrite existing user memory on startup.
                }
            }
            return ids;
        } finally {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1, LOCK_ID);
                statement.execute();
            }
        }
    }

    private String ensure(Connection connection, String setting, String envKey, String endpoint,
                          Map<?, ?> payload, boolean memory, Map<String, String> configured) throws Exception {
        Map<?, ?> saved = null;
        try (var statement = connection.prepareStatement("SELECT value::text FROM settings WHERE key = ?")) {
            statement.setString(1, setting);
            try (var result = statement.executeQuery()) {
                if (result.next()) {
                    Object value = JSON.readValue(result.getString(1), Object.class);
                    if (value instanceof String legacyId) saved = Map.of("id", legacyId);
                    else if (value instanceof Map<?, ?> record) saved = record;
                    else throw new IllegalStateException("Invalid resource setting: " + setting);
                }
            }
        }
        String supplied = configured.get(envKey);
        if (supplied != null && supplied.isBlank()) supplied = null;
        String id = saved == null ? supplied : resourceId(saved);
        if (saved != null && supplied != null && !id.equals(supplied)) {
            log.warn("{} from configuration is ignored; using the saved database ID", envKey);
        }
        Map<?, ?> remote = null;
        if (id != null) {
            validateId(id);
            try {
                remote = api.request("GET", endpoint + "/" + id, null, memory);
            } catch (ManagedAgentApi.ApiException error) {
                if (error.status != 404) throw error;
            }
            if (remote == null || remote.get("archived_at") != null) {
                if (memory && !allowNewMemoryStore) {
                    throw new IllegalStateException("Memory store not found or archived. Restore it, or set "
                            + "TINYME_ALLOW_NEW_MEMORY_STORE=true to start with an empty memory store.");
                }
                log.warn("{} is missing or archived; creating a replacement{}", envKey,
                        memory ? " memory store with seed templates; prior memories are not recovered" : " configuration");
                remote = null;
            }
        }
        String hash = memory ? null : seedHash(payload);
        if (remote == null) {
            remote = api.request("POST", endpoint, payload, memory);
            id = resourceId(remote);
        } else if (!memory && (saved == null || !hash.equals(saved.get("seedHash")))) {
            var update = new LinkedHashMap<Object, Object>(payload);
            if (setting.equals("agent.chat")) update.put("version", agentVersion(remote));
            remote = api.request("POST", endpoint + "/" + id, update, false);
        }
        var record = new LinkedHashMap<String, Object>();
        record.put("id", id);
        if (!memory) record.put("seedHash", hash);
        if (setting.equals("agent.chat")) record.put("version", agentVersion(remote));
        try (var statement = connection.prepareStatement(
                "INSERT INTO settings (key, value) VALUES (?, ?::jsonb) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
            statement.setString(1, setting);
            statement.setString(2, JSON.writeValueAsString(record));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Could not save " + envKey + "=" + id + "; recover this ID before retrying", error);
        }
        return id;
    }

    static String seedHash(Map<?, ?> seed) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.writeValueAsBytes(seed)));
    }

    private static String resourceId(Map<?, ?> resource) throws IOException {
        if (!(resource.get("id") instanceof String id)) throw new IOException("Resource is missing its ID");
        validateId(id);
        return id;
    }

    private static Number agentVersion(Map<?, ?> resource) throws IOException {
        if (!(resource.get("version") instanceof Number version) || version.longValue() < 1) {
            throw new IOException("Agent response is missing a valid version");
        }
        return version;
    }

    private Map<?, ?> yaml(String path) throws IOException {
        try (var input = resources.getResource("classpath:" + path).getInputStream()) {
            return YAMLMapper.builder().build().readValue(input, Map.class);
        }
    }

    static void validateId(String id) {
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid Managed Agents resource ID");
    }
}
