package com.tinyme.api.admin;

import com.tinyme.agent.client.ManagedAgents;
import com.tinyme.agent.client.MemoryPage;
import com.tinyme.agent.client.MemoryRef;
import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.auth.service.DeviceTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@AutoConfigureMockMvc
@Import(AgentDatabaseConfiguration.class)
class AdminExportTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceTokenService tokens;

    @MockitoBean ManagedAgents agents;

    @BeforeEach
    void resetDatabaseAndStubs() {
        jdbc.update("DELETE FROM pending_actions");
        jdbc.update("DELETE FROM photo_people");
        jdbc.update("DELETE FROM photos");
        jdbc.update("DELETE FROM entry_people");
        jdbc.update("DELETE FROM entries");
        jdbc.update("DELETE FROM people");
        jdbc.update("DELETE FROM entry_kinds");
        jdbc.update("DELETE FROM blobs");
        jdbc.update("DELETE FROM places");
        jdbc.update("DELETE FROM tool_calls");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM agent_sessions");
        jdbc.update("DELETE FROM cards WHERE system = false");
        jdbc.update("DELETE FROM settings WHERE key IN ('env.default', 'agent.chat', 'memory.main', 'user.tz')");
        setting("env.default", "{\"id\":\"env_saved\"}");
        setting("agent.chat", "{\"id\":\"agent_saved\",\"version\":3}");
        setting("memory.main", "{\"id\":\"memstore_saved\"}");
        setting("user.tz", "\"Asia/Tokyo\"");
        reset(agents);
    }

    @Test
    void exportStreamsDatabaseRowsAndAllPagedMemoryDocumentsIntoZip() throws Exception {
        seedExportRows();
        when(agents.listMemories("memstore_saved", "/", null)).thenReturn(new MemoryPage(List.of(
                new MemoryRef("mem_profile", "/me/profile.md"),
                new MemoryRef("mem_rules", "/me/rules.md")), "page-2"));
        when(agents.listMemories("memstore_saved", "/", "page-2")).thenReturn(new MemoryPage(List.of(
                new MemoryRef("mem_kenji", "/people/kenji.md")), null));
        when(agents.getMemory("memstore_saved", "mem_profile")).thenReturn(java.util.Optional.of("Profile notes"));
        when(agents.getMemory("memstore_saved", "mem_rules")).thenReturn(java.util.Optional.of("Rules notes"));
        when(agents.getMemory("memstore_saved", "mem_kenji")).thenReturn(java.util.Optional.of("Kenji notes"));

        byte[] archive = download();
        Map<String, byte[]> files = unzip(archive);
        String root = "tinyme-export-" + LocalDate.now(java.time.Clock.systemUTC().withZone(java.time.ZoneId.of("Asia/Tokyo"))) + "/";

        assertThat(files).containsKeys(root + "README.txt", root + "manifest.json",
                root + "db/entries.jsonl", root + "db/people.jsonl", root + "db/cards.jsonl",
                root + "db/messages.jsonl", root + "db/tool_calls.jsonl", root + "db/plans.jsonl",
                root + "db/plan_people.jsonl", root + "db/plan_occurrences.jsonl",
                root + "memory/me/profile.md", root + "memory/me/rules.md", root + "memory/people/kenji.md");
        assertThat(new String(files.get(root + "memory/people/kenji.md"), StandardCharsets.UTF_8))
                .isEqualTo("Kenji notes");
        assertThat(files.keySet()).noneMatch(path -> path.contains("account") || path.contains("refresh_tokens")
                || path.endsWith("/db/settings.jsonl"));

        List<JsonNode> entries = jsonLines(files.get(root + "db/entries.jsonl"));
        assertThat(entries).hasSize(2);
        assertThat(entries).anyMatch(entry -> entry.path("deleted_at").isNull()
                && entry.path("data").path("type").asString().equals("coffee"));
        assertThat(entries).anyMatch(entry -> !entry.path("deleted_at").isNull()
                && entry.path("data").path("type").asString().equals("tea"));
        for (String path : files.keySet()) {
            if (path.startsWith(root + "db/") && path.endsWith(".jsonl")) {
                assertThat(jsonLines(files.get(path))).isNotNull();
            }
        }
        assertThat(jsonLines(files.get(root + "db/people.jsonl"))).hasSize(1);
        assertThat(jsonLines(files.get(root + "db/messages.jsonl"))).hasSize(1);
        assertThat(jsonLines(files.get(root + "db/tool_calls.jsonl"))).hasSize(1);

        JsonNode manifest = JSON.readTree(files.get(root + "manifest.json"));
        assertThat(manifest.path("format").asString()).isEqualTo("tinyme-export");
        assertThat(manifest.path("schema_version").asString()).isEqualTo("1.6");
        assertThat(manifest.path("user_zone").asString()).isEqualTo("Asia/Tokyo");
        assertThat(manifest.path("counts").path("entries").asLong()).isEqualTo(2);
        assertThat(manifest.path("counts").path("people").asLong()).isEqualTo(1);
        assertThat(manifest.path("counts").path("messages").asLong()).isEqualTo(1);
        assertThat(manifest.path("counts").path("tool_calls").asLong()).isEqualTo(1);
        assertThat(manifest.path("memory").path("documents").asInt()).isEqualTo(3);
        assertThat(manifest.path("memory").path("error").isNull()).isTrue();
        verify(agents).listMemories("memstore_saved", "/", "page-2");
    }

    @Test
    void memoryFailureStillReturnsDatabaseArchiveAndReportsPartialExport() throws Exception {
        seedExportRows();
        when(agents.listMemories("memstore_saved", "/", null)).thenThrow(new IOException("memory API unavailable"));

        Map<String, byte[]> files = unzip(download());
        String root = files.keySet().stream().filter(path -> path.endsWith("/manifest.json"))
                .findFirst().orElseThrow().replace("manifest.json", "");
        assertThat(files).containsKey(root + "db/entries.jsonl");
        JsonNode manifest = JSON.readTree(files.get(root + "manifest.json"));
        assertThat(manifest.path("memory").path("documents").asInt()).isZero();
        assertThat(manifest.path("memory").path("error").asString()).isNotBlank();
    }

    @Test
    void onlyOneExportRunsAtATime() throws Exception {
        CountDownLatch memoryFetchStarted = new CountDownLatch(1);
        CountDownLatch allowMemoryFetch = new CountDownLatch(1);
        when(agents.listMemories("memstore_saved", "/", null)).thenReturn(new MemoryPage(
                List.of(new MemoryRef("mem_profile", "/me/profile.md")), null));
        when(agents.getMemory("memstore_saved", "mem_profile")).thenAnswer(invocation -> {
            memoryFetchStarted.countDown();
            if (!allowMemoryFetch.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out");
            return java.util.Optional.of("Profile notes");
        });

        String token = token();
        MvcResult first = mvc.perform(get("/admin/export").header("Authorization", "Bearer " + token))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(memoryFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            mvc.perform(get("/admin/export").header("Authorization", "Bearer " + token))
                    .andExpect(status().isConflict());
        } finally {
            allowMemoryFetch.countDown();
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(first))
                .andExpect(status().isOk());
    }

    @Test
    void exportRequiresAuthentication() throws Exception {
        mvc.perform(get("/admin/export")).andExpect(status().isUnauthorized());
    }

    private byte[] download() throws Exception {
        String token = token();
        MvcResult initial = mvc.perform(get("/admin/export").header("Authorization", "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("tinyme-export-")))
                .andReturn();
        MvcResult completed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andReturn();
        return completed.getResponse().getContentAsByteArray();
    }

    private String token() {
        return tokens.issue("export-test").token();
    }

    private void seedExportRows() {
        jdbc.update("INSERT INTO entry_kinds (kind, description) VALUES ('drink', 'Drinks')");
        UUID live = jdbc.queryForObject("""
                INSERT INTO entries (ts, local_day, kind, quantity, text, data)
                VALUES ('2026-10-12T02:00:00Z', '2026-10-12', 'drink', 1, 'coffee', '{\"type\":\"coffee\"}')
                RETURNING id
                """, UUID.class);
        jdbc.update("""
                INSERT INTO entries (ts, local_day, kind, quantity, text, data, deleted_at)
                VALUES ('2026-10-11T02:00:00Z', '2026-10-11', 'drink', 1, 'tea', '{\"type\":\"tea\"}', now())
                """);
        UUID personId = jdbc.queryForObject("""
                INSERT INTO people (slug, display_name, memory_path) VALUES ('kenji', 'Kenji', 'people/kenji.md')
                RETURNING id
                """, UUID.class);
        jdbc.update("INSERT INTO entry_people (entry_id, person_id) VALUES (?, ?)", live, personId);
        UUID sessionId = jdbc.queryForObject("""
                INSERT INTO agent_sessions (anthropic_session_id, agent, agent_version, kind, status)
                VALUES ('sesn_export_test', 'chat', 3, 'job', 'active') RETURNING id
                """, UUID.class);
        jdbc.update("INSERT INTO messages (session_id, role, content) VALUES (?, 'USER', 'had a coffee')",
                sessionId);
        UUID toolCallId = jdbc.queryForObject("""
                INSERT INTO tool_calls (session_id, anthropic_event_id, tool, input, result, status, finished_at)
                VALUES (?, 'evt_export_test', 'entries_add', '{}', '{\"ok\":true}', 'done', now()) RETURNING id
                """, UUID.class, sessionId);
        jdbc.update("""
                INSERT INTO pending_actions (tool_call_id, type, payload, origin, expires_at)
                VALUES (?, 'test', '{}', 'chat', now() + interval '1 day')
                """, toolCallId);
    }

    private void setting(String key, String value) {
        jdbc.update("INSERT INTO settings (key, value) VALUES (?, CAST(? AS jsonb))", key, value);
    }

    private static List<JsonNode> jsonLines(byte[] bytes) throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) rows.add(JSON.readTree(line));
        }
        return rows;
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                zip.transferTo(bytes);
                files.put(entry.getName(), bytes.toByteArray());
                zip.closeEntry();
            }
        }
        return files;
    }
}
