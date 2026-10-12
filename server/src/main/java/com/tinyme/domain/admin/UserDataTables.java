package com.tinyme.domain.admin;

import java.util.List;

/** Single ordered allow-list for user data included in exports and removed by Delete all. */
public final class UserDataTables {
    private UserDataTables() { }

    public static final List<String> ALL = List.of(
            "entries", "entry_kinds", "people", "entry_people", "cards", "messages", "agent_sessions",
            "tool_calls", "photos", "photo_people", "blobs", "places", "pending_actions");

    public static final List<String> KEEP = List.of(
            "account", "refresh_tokens", "settings", "flyway_schema_history");
}
