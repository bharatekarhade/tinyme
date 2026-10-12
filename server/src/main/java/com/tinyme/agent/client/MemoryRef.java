package com.tinyme.agent.client;

/** Metadata for one memory document; directory-like memory prefixes are omitted. */
public record MemoryRef(String id, String path) { }
