package com.tinyme.tools.model;




import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public record ToolContext(
        UUID sessionRowId,
        UUID toolCallId,
        ZoneId zone,
        LocalDate localDate,
        Instant now
) {
    public ToolContext withToolCallId(UUID id) {
        return new ToolContext(sessionRowId, id, zone, localDate, now);
    }
}
