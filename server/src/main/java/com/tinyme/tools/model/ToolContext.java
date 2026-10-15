package com.tinyme.tools.model;




import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import com.tinyme.agent.model.TurnLocation;

public record ToolContext(
        UUID sessionRowId,
        UUID toolCallId,
        ZoneId zone,
        LocalDate localDate,
        Instant now,
        UUID attachedPhotoId,
        TurnLocation location
) {
    public ToolContext(UUID sessionRowId, UUID toolCallId, ZoneId zone, LocalDate localDate, Instant now) {
        this(sessionRowId, toolCallId, zone, localDate, now, null, null);
    }

    public ToolContext(UUID sessionRowId, UUID toolCallId, ZoneId zone, LocalDate localDate, Instant now,
                       UUID attachedPhotoId) {
        this(sessionRowId, toolCallId, zone, localDate, now, attachedPhotoId, null);
    }

    public ToolContext withToolCallId(UUID id) {
        return new ToolContext(sessionRowId, id, zone, localDate, now, attachedPhotoId, location);
    }
}
