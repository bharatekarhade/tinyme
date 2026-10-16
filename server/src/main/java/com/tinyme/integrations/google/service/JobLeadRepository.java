package com.tinyme.integrations.google.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Job postings found in job-alert emails. Deleted ones stay as tombstones so they never come back. */
@Repository
public class JobLeadRepository {
    public static final List<String> STATUSES =
            List.of("new", "interested", "applied", "interviewing", "offer", "rejected", "not_interested");
    private static final Pattern LINKEDIN_JOB = Pattern.compile("linkedin\\.com/(?:comm/)?jobs/view/(\\d{5,})");
    private static final String COLUMNS = "SELECT id, title, company, location, url, status, found_at, status_changed_at "
            + "FROM job_leads ";

    private final JdbcTemplate jdbc;

    public JobLeadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds a job unless the same one was seen before (kept or deleted). Returns whether it was new. */
    public boolean save(String messageId, MailScanRepository.Candidate candidate) {
        Map<String, Object> job = candidate.payload();
        String title = (String) job.get("title");
        String company = (String) job.get("company");
        String url = (String) job.get("url");
        return jdbc.update("""
                INSERT INTO job_leads (dedupe_key, title, company, location, url, gmail_message_id)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (dedupe_key) DO NOTHING
                """, dedupeKey(company, title, url), title, company, job.get("location"), url, messageId) == 1;
    }

    /** The same posting: LinkedIn's job id when there is a link, otherwise company and title. */
    static String dedupeKey(String company, String title, String url) {
        if (url != null) {
            Matcher id = LINKEDIN_JOB.matcher(url);
            if (id.find()) return "linkedin:" + id.group(1);
        }
        return "job:" + fold(company) + "|" + fold(title);
    }

    private static String fold(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** A page of jobs, newest first, optionally of one status, after where the previous page ended. */
    public Page page(String status, Cursor after, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(COLUMNS).append("WHERE deleted_at IS NULL");
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (after != null) {
            sql.append(" AND (found_at, id) < (?, ?)");
            args.add(Timestamp.from(after.foundAt()));
            args.add(after.id());
        }
        sql.append(" ORDER BY found_at DESC, id DESC LIMIT ?");
        args.add(limit + 1);
        List<JobLead> rows = jdbc.query(sql.toString(), (row, n) -> lead(row), args.toArray());
        boolean more = rows.size() > limit;
        List<JobLead> items = more ? rows.subList(0, limit) : rows;
        String next = more ? new Cursor(items.getLast().foundAt(), items.getLast().id()).encode() : null;
        return new Page(List.copyOf(items), next);
    }

    public int countNew() {
        return jdbc.queryForObject("SELECT count(*) FROM job_leads WHERE deleted_at IS NULL AND status = 'new'",
                Integer.class);
    }

    public Optional<JobLead> setStatus(UUID id, String status, Instant now) {
        jdbc.update("UPDATE job_leads SET status = ?, status_changed_at = ? WHERE id = ? AND deleted_at IS NULL",
                status, Timestamp.from(now), id);
        return find(id);
    }

    public boolean delete(UUID id, Instant now) {
        return jdbc.update("UPDATE job_leads SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL",
                Timestamp.from(now), id) == 1;
    }

    public Optional<JobLead> find(UUID id) {
        return jdbc.query(COLUMNS + "WHERE id = ? AND deleted_at IS NULL",
                result -> result.next() ? Optional.of(lead(result)) : Optional.empty(), id);
    }

    private static JobLead lead(ResultSet row) throws SQLException {
        Timestamp changed = row.getTimestamp("status_changed_at");
        return new JobLead(row.getObject("id", UUID.class), row.getString("title"), row.getString("company"),
                row.getString("location"), row.getString("url"), row.getString("status"),
                row.getTimestamp("found_at").toInstant(), changed == null ? null : changed.toInstant());
    }

    public record JobLead(UUID id, String title, String company, String location, String url, String status,
                          Instant foundAt, Instant statusChangedAt) { }

    public record Page(List<JobLead> items, String nextCursor) { }

    /** Where the previous page ended, opaque to the app. Decoded outside the repository so a bad one is a 400. */
    public record Cursor(Instant foundAt, UUID id) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((foundAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        public static Cursor decode(String text) {
            if (text == null || text.isBlank()) return null;
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8).split("\\|", 2);
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("cursor is not valid");
            }
        }
    }
}
