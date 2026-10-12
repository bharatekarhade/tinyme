package com.tinyme.agent.service;

import com.tinyme.agent.client.ManagedAgents;
import com.tinyme.agent.client.MemoryPage;
import com.tinyme.agent.client.MemoryRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Copies memory-store documents into the export zip without persisting them on the server. */
@Component
public class MemoryArchive {
    private static final Logger log = LoggerFactory.getLogger(MemoryArchive.class);

    private final ManagedAgents agents;
    private final AgentResources resources;

    public MemoryArchive(ManagedAgents agents, AgentResources resources) {
        this.agents = agents;
        this.resources = resources;
    }

    public Result writeTo(String archiveRoot, ZipOutputStream zip) throws IOException {
        final String storeId;
        try {
            storeId = resources.load().memoryStoreId();
        } catch (RuntimeException error) {
            log.warn("Could not resolve the memory store for data export", error);
            return new Result(0, "Memory store is unavailable");
        }

        int documents = 0;
        String cursor = null;
        Set<String> seenCursors = new HashSet<>();
        do {
            final MemoryPage page;
            try {
                page = agents.listMemories(storeId, "/", cursor);
            } catch (IOException | InterruptedException | RuntimeException error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Memory listing failed during data export", error);
                return new Result(documents, "Memory store could not be fully exported");
            }

            for (MemoryRef memory : page.memories()) {
                final String content;
                try {
                    content = agents.getMemory(storeId, memory.id())
                            .orElseThrow(() -> new IOException("Memory document has no content"));
                } catch (IOException | InterruptedException | RuntimeException error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    log.warn("Memory retrieval failed during data export", error);
                    return new Result(documents, "Memory store could not be fully exported");
                }
                String path = zipPath(memory.path());
                if (path == null) {
                    log.warn("Skipping a memory document with an unsafe archive path");
                    return new Result(documents, "Memory store contains an invalid document path");
                }
                zip.putNextEntry(new ZipEntry(archiveRoot + "memory/" + path));
                zip.write(content.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                documents++;
            }

            cursor = page.nextCursor();
            if (cursor != null && !seenCursors.add(cursor)) {
                log.warn("Memory pagination repeated a cursor during data export");
                return new Result(documents, "Memory store pagination did not complete");
            }
        } while (cursor != null);
        return new Result(documents, null);
    }

    private static String zipPath(String path) {
        if (path == null || !path.startsWith("/") || path.contains("\\")) return null;
        String relative = path.substring(1);
        if (relative.isBlank()) return null;
        for (String segment : relative.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) return null;
        }
        return relative;
    }

    public record Result(int documents, String error) { }
}
