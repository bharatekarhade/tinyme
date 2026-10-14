package com.tinyme.domain.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import org.postgresql.util.PGobject;

import java.io.IOException;
import java.lang.reflect.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipOutputStream;

/** Writes allow-listed database tables as row-streamed JSON Lines archive entries. */
@Component
public class TableExporter {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public TableExporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return number of rows written */
    public long export(String table, String archiveRoot, ZipOutputStream zip) throws IOException {
        if (!UserDataTables.ALL.contains(table)) throw new IllegalArgumentException("Table is not exportable: " + table);
        String orderBy = switch (table) {
            case "entry_kinds" -> "kind";
            case "entry_people" -> "entry_id, person_id";
            case "photo_people" -> "photo_id, person_id";
            case "plan_people" -> "plan_id, person_id";
            case "plan_occurrences" -> "plan_id, day";
            case "health_sync_log" -> "kind";
            case "nudge_log" -> "nudge_key, day";
            default -> "id";
        };
        zip.putNextEntry(new java.util.zip.ZipEntry(archiveRoot + "db/" + table + ".jsonl"));
        long[] rows = {0};
        String sql = "SELECT * FROM " + table + " ORDER BY " + orderBy;
        try {
            jdbc.query(connection -> {
                PreparedStatement statement = connection.prepareStatement(sql,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                statement.setFetchSize(500);
                return statement;
            }, (RowCallbackHandler) resultSet -> {
                try {
                    Map<String, Object> row = readRow(resultSet);
                    zip.write(json.writeValueAsBytes(row));
                    zip.write('\n');
                } catch (IOException error) {
                    throw new ExportWriteException(error);
                }
                rows[0]++;
            });
        } catch (ExportWriteException error) {
            throw error.ioException;
        }
        zip.closeEntry();
        return rows[0];
    }

    private Map<String, Object> readRow(ResultSet resultSet) throws SQLException, IOException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            String name = metadata.getColumnLabel(column);
            String typeName = metadata.getColumnTypeName(column);
            if ("jsonb".equalsIgnoreCase(typeName) || "json".equalsIgnoreCase(typeName)) {
                String value = resultSet.getString(column);
                row.put(name, value == null ? null : json.readTree(value));
            } else {
                row.put(name, jsonValue(resultSet.getObject(column)));
            }
        }
        return row;
    }

    private Object jsonValue(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof UUID uuid) return uuid.toString();
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant().toString();
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof LocalDate date) return date.toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof LocalDateTime dateTime) return dateTime.toString();
        if (value instanceof java.sql.Array sqlArray) {
            Object array = sqlArray.getArray();
            try {
                return arrayValue(array);
            } finally {
                sqlArray.free();
            }
        }
        if (value instanceof PGobject postgresValue) return postgresValue.getValue();
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        return value;
    }

    private List<Object> arrayValue(Object array) throws SQLException {
        int length = Array.getLength(array);
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) values.add(jsonValue(Array.get(array, index)));
        return values;
    }

    private static final class ExportWriteException extends RuntimeException {
        private final IOException ioException;

        private ExportWriteException(IOException cause) {
            super(cause);
            ioException = cause;
        }
    }
}
