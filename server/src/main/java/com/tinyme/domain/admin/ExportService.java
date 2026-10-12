package com.tinyme.domain.admin;

import com.tinyme.agent.service.MemoryArchive;
import com.tinyme.settings.UserZone;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams a complete database snapshot and the agent memory files as a ZIP archive. */
@Service
public class ExportService {
    private static final List<String> TABLES = List.of(
            "entries", "entry_kinds", "people", "entry_people", "cards", "messages", "agent_sessions",
            "tool_calls", "photos", "photo_people", "blobs", "places", "pending_actions");

    private static final String README = """
            TinyMe data export

            The db folder contains one JSON object per line for each database row.
            Entries marked deleted are included so this archive is a complete record.
            JSON columns remain nested JSON; timestamps are written in UTC.
            The memory folder contains the Markdown files the agent keeps for you.
            Account password hashes, phone sign-in tokens, and server configuration are not included.
            See manifest.json for export time, row counts, and any memory export warning.
            """;

    private final AtomicBoolean running = new AtomicBoolean();
    private final TableExporter tables;
    private final MemoryArchive memories;
    private final JdbcTemplate jdbc;
    private final UserZone userZone;
    private final Clock clock;
    private final String appVersion;
    private final JsonMapper json = JsonMapper.builder().build();

    public ExportService(TableExporter tables, MemoryArchive memories, JdbcTemplate jdbc, UserZone userZone,
                         Clock clock, @Value("${tinyme.app-version:0.1.0}") String appVersion) {
        this.tables = tables;
        this.memories = memories;
        this.jdbc = jdbc;
        this.userZone = userZone;
        this.clock = clock;
        this.appVersion = appVersion;
    }

    public boolean tryAcquire() {
        return running.compareAndSet(false, true);
    }

    public void release() {
        running.set(false);
    }

    public String filename() {
        LocalDate day = LocalDate.now(clock.withZone(userZone.current()));
        return "tinyme-export-" + day + ".zip";
    }

    /** The controller calls this on Spring's streaming worker, so the transaction covers actual export reads. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void writeArchive(OutputStream output) throws IOException {
        Map<String, Long> rowCounts = new LinkedHashMap<>();
        MemoryArchive.Result memoryResult;
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            String root = "tinyme-export-" + LocalDate.now(clock.withZone(userZone.current())) + "/";
            addText(zip, root + "README.txt", README);
            for (String table : TABLES) {
                rowCounts.put(table, tables.export(table, root, zip));
            }

            memoryResult = memories.writeTo(root, zip);
            Map<String, Object> manifest = manifest(rowCounts, memoryResult);
            addBytes(zip, root + "manifest.json", json.writeValueAsBytes(manifest));
            zip.finish();
        }
    }

    private Map<String, Object> manifest(Map<String, Long> rowCounts, MemoryArchive.Result memoryResult) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "tinyme-export");
        manifest.put("version", 1);
        manifest.put("exported_at", clock.instant().toString());
        manifest.put("app_version", appVersion);
        manifest.put("schema_version", schemaVersion());
        manifest.put("user_zone", userZone.current().getId());
        manifest.put("counts", Map.of(
                "entries", rowCounts.getOrDefault("entries", 0L),
                "people", rowCounts.getOrDefault("people", 0L),
                "cards", rowCounts.getOrDefault("cards", 0L),
                "messages", rowCounts.getOrDefault("messages", 0L),
                "tool_calls", rowCounts.getOrDefault("tool_calls", 0L)));
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("documents", memoryResult.documents());
        memory.put("error", memoryResult.error());
        manifest.put("memory", memory);
        return manifest;
    }

    private String schemaVersion() {
        try {
            String version = jdbc.queryForObject(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                    String.class);
            return version == null ? "unknown" : version;
        } catch (DataAccessException error) {
            return "unknown";
        }
    }

    private static void addText(ZipOutputStream zip, String path, String text) throws IOException {
        addBytes(zip, path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void addBytes(ZipOutputStream zip, String path, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content);
        zip.closeEntry();
    }
}
