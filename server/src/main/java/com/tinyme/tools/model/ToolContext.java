package com.tinyme.tools.model;




import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import com.tinyme.agent.model.TurnLocation;
import com.tinyme.agent.service.TurnListener;

public record ToolContext(
        UUID sessionRowId,
        UUID toolCallId,
        ZoneId zone,
        LocalDate localDate,
        Instant now,
        UUID attachedPhotoId,
        TurnLocation location,
        TurnListener turnListener,
        String agent,
        String threadId
) {
    public ToolContext(UUID sessionRowId, UUID toolCallId, ZoneId zone, LocalDate localDate, Instant now,
                       UUID attachedPhotoId, TurnLocation location, TurnListener turnListener) {
        this(sessionRowId, toolCallId, zone, localDate, now, attachedPhotoId, location, turnListener,
                "tinyme-chat", null);
    }

    public ToolContext(UUID sessionRowId, UUID toolCallId, ZoneId zone, LocalDate localDate, Instant now) {
        this(sessionRowId, toolCallId, zone, localDate, now, null, null, TurnListener.NONE);
    }

    public ToolContext(UUID sessionRowId, UUID toolCallId, ZoneId zone, LocalDate localDate, Instant now,
                       UUID attachedPhotoId) {
        this(sessionRowId, toolCallId, zone, localDate, now, attachedPhotoId, null, TurnListener.NONE);
    }

    public ToolContext(UUID sessionRowId, UUID toolCallId, ZoneId zone, LocalDate localDate, Instant now,
                       UUID attachedPhotoId, TurnLocation location) {
        this(sessionRowId, toolCallId, zone, localDate, now, attachedPhotoId, location, TurnListener.NONE);
    }

    public ToolContext withToolCallId(UUID id) {
        return new ToolContext(sessionRowId, id, zone, localDate, now, attachedPhotoId, location, turnListener,
                agent, threadId);
    }

    public ToolContext withAgent(String agentName, String thread) {
        return new ToolContext(sessionRowId, toolCallId, zone, localDate, now, attachedPhotoId, location, turnListener,
                agentName, thread);
    }
}
