package com.tinyme.conversation.controller;

import com.tinyme.conversation.service.ConversationImageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ConversationImageController {
    private final ConversationImageService images;

    public ConversationImageController(ConversationImageService images) {
        this.images = images;
    }

    @GetMapping("/photos/{photoId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID photoId) {
        var image = images.content(photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(new InputStreamResource(image.stream()));
    }
}
