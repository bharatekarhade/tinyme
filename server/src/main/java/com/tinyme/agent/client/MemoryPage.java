package com.tinyme.agent.client;

import java.util.List;

/** One page of memory documents and the opaque cursor for the next page. */
public record MemoryPage(List<MemoryRef> memories, String nextCursor) {
    public MemoryPage {
        memories = List.copyOf(memories);
    }
}
