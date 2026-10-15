package com.tinyme.domain.admin;

import java.util.List;

/** Single ordered allow-list for user data included in exports and removed by Delete all. */
public final class UserDataTables {
    private UserDataTables() { }

    public static final List<String> ALL = List.of(
            "entries", "entry_kinds", "people", "entry_people", "cards", "messages", "agent_sessions",
            "tool_calls", "photos", "photo_people", "blobs", "places", "pending_actions",
            "plans", "plan_people", "plan_occurrences", "health_settings", "health_sync_log", "nudge_log",
            "google_account", "oauth_states");

    /** Secret-bearing tables are cleared by Delete all but never included in an export archive. */
    public static final List<String> SECRETS = List.of("google_account", "oauth_states");

    public static final List<String> EXPORTABLE = ALL.stream()
            .filter(table -> !SECRETS.contains(table))
            .toList();

    public static final List<String> KEEP = List.of(
            "account", "refresh_tokens", "settings", "flyway_schema_history");
}
