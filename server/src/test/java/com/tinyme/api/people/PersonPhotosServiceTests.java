package com.tinyme.api.people;

import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.service.PersonPhotoLinkService;
import com.tinyme.domain.people.service.PersonService;
import com.tinyme.storage.LocalBlobStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonPhotosServiceTests {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18")
            .asCompatibleSubstituteFor("postgres"));

    private static JdbcTemplate jdbc;
    private final PersonService people = mock(PersonService.class);
    @TempDir Path directory;

    @BeforeAll
    static void migrate() {
        postgres.start();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @BeforeEach
    void seedPerson() {
        jdbc.execute("TRUNCATE photos, photo_people, blobs, people CASCADE");
        jdbc.update("INSERT INTO people (slug, display_name, memory_path) VALUES ('kenji', 'Kenji', 'people/kenji.md')");
        when(people.findLiveBySlug("kenji")).thenReturn(Optional.of(
                new PersonSnapshot("kenji", "Kenji", List.of(), "friend", "people/kenji.md")));
    }

    @Test
    void uploadsImageLinksItToPersonAndServesItBack() throws Exception {
        var store = new LocalBlobStore(directory.toString());
        var service = new PersonPhotosService(people, store, jdbc);
        byte[] image = jpeg();

        PhotoResponse saved = service.attach("kenji", new MockMultipartFile("file", "memory.jpg", "image/jpeg", image),
                "At Kamakura");

        assertThat(saved.id()).isNotNull();
        assertThat(saved.caption()).isEqualTo("At Kamakura");
        assertThat(saved.contentUrl()).isEqualTo("/people/kenji/photos/" + saved.id() + "/content");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_people WHERE photo_id = ?", Integer.class, saved.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blobs WHERE type = 'photo' AND status = 'ready'", Integer.class)).isEqualTo(1);
        assertThat(service.list("kenji")).extracting(PhotoResponse::id).containsExactly(saved.id());
        try (var content = service.content("kenji", saved.id()).stream()) {
            assertThat(content.readAllBytes()).containsExactly(image);
        }
    }

    @Test
    void rejectsNonImagesAndOversizedUploads() {
        var service = new PersonPhotosService(people, new LocalBlobStore(directory.toString()), jdbc);

        assertThatThrownBy(() -> service.attach("kenji",
                new MockMultipartFile("file", "fake.jpg", "image/jpeg", "not an image".getBytes()), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.attach("kenji",
                new MockMultipartFile("file", "text.txt", "text/plain", "hello".getBytes()), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photos", Integer.class)).isZero();
    }

    @Test
    void linksTheCurrentChatPhotoIntoThePersonPhotoGallery() {
        UUID blobId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-10-15T01:00:00Z"), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO blobs (id, key, type, content_type, size_bytes, sha256, status, completed_at)
                VALUES (?, ?, 'photo', 'image/jpeg', 12, ?, 'ready', ?)
                """, blobId, "photos/2026/10/" + blobId + ".jpg", "0".repeat(64), now);
        jdbc.update("INSERT INTO photos (id, blob_id) VALUES (?, ?)", photoId, blobId);

        String displayName = new PersonPhotoLinkService(jdbc).addToProfile("kenji", photoId);
        var gallery = new PersonPhotosService(people, new LocalBlobStore(directory.toString()), jdbc).list("kenji");

        assertThat(displayName).isEqualTo("Kenji");
        assertThat(gallery).extracting(PhotoResponse::id).containsExactly(photoId);
        assertThat(gallery.getFirst().contentUrl()).isEqualTo("/people/kenji/photos/" + photoId + "/content");
    }

    private static byte[] jpeg() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.CYAN.getRGB());
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }
}
