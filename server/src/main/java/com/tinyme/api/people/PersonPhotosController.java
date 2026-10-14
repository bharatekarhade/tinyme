package com.tinyme.api.people;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/people/{slug}/photos")
public class PersonPhotosController {
    private final PersonPhotosService photos;

    public PersonPhotosController(PersonPhotosService photos) {
        this.photos = Objects.requireNonNull(photos, "photos");
    }

    @GetMapping
    public List<PhotoResponse> list(@PathVariable String slug) {
        return photos.list(slug);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoResponse attach(@PathVariable String slug, @RequestParam("file") MultipartFile file,
                                @RequestParam(value = "caption", required = false) String caption) throws IOException {
        return photos.attach(slug, file, caption);
    }

    @GetMapping("/{photoId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String slug, @PathVariable UUID photoId) {
        var photo = photos.content(slug, photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(photo.stream()));
    }
}
