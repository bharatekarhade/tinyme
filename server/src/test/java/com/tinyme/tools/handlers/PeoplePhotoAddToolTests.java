package com.tinyme.tools.handlers;

import com.tinyme.domain.people.service.PersonPhotoLinkService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeoplePhotoAddToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID PHOTO_ID = UUID.randomUUID();
    private static final ToolContext CONTEXT = new ToolContext(UUID.randomUUID(), null,
            ZoneId.of("Asia/Tokyo"), LocalDate.of(2026, 10, 15), Instant.parse("2026-10-15T01:00:00Z"), PHOTO_ID);

    private final PersonPhotoLinkService photos = mock(PersonPhotoLinkService.class);
    private final PeoplePhotoAddTool tool = new PeoplePhotoAddTool(photos);

    @Test
    void linksTheCurrentAttachmentAndReturnsProfileSummary() {
        when(photos.addToProfile("bharat", PHOTO_ID)).thenReturn("Bharat");

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(JSON.readTree("{\"slug\":\"bharat\"}"), CONTEXT);

        assertThat(result.summary()).isEqualTo("Added photo to Bharat's profile");
        assertThat(JSON.valueToTree(result.data()).get("photo_id").stringValue()).isEqualTo(PHOTO_ID.toString());
        verify(photos).addToProfile("bharat", PHOTO_ID);
    }

    @Test
    void refusesToSaveWhenThisMessageHasNoAttachment() {
        ToolContext withoutPhoto = new ToolContext(CONTEXT.sessionRowId(), null, CONTEXT.zone(),
                CONTEXT.localDate(), CONTEXT.now());

        ToolResult.Err result = (ToolResult.Err) tool.handle(JSON.readTree("{\"slug\":\"bharat\"}"), withoutPhoto);

        assertThat(result.code()).isEqualTo("validation_error");
        assertThat(result.message()).contains("no photo attached");
        verify(photos, never()).addToProfile("bharat", PHOTO_ID);
    }
}
