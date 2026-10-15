package com.tinyme.conversation.service;

import com.tinyme.conversation.model.ConversationImage;
import com.tinyme.storage.LocalBlobStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationImageServiceTests {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18")
            .asCompatibleSubstituteFor("postgres"));
    private static JdbcTemplate jdbc;
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
    void clearRows() {
        jdbc.execute("TRUNCATE photos, blobs CASCADE");
    }

    @Test
    void storesImageForHistoryAndReturnsManagedAgentsImageBlock() throws Exception {
        byte[] jpeg = jpeg();
        var service = new ConversationImageService(new LocalBlobStore(directory.toString()), jdbc);

        var stored = service.store(new ConversationImage("image/jpeg", Base64.getEncoder().encodeToString(jpeg)));

        assertThat(stored.history()).containsKeys("photo_id", "content_url", "content_type");
        assertThat(stored.model()).containsEntry("type", "image");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photos", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blobs WHERE status = 'ready'", Integer.class)).isEqualTo(1);
        try (var content = service.content(stored.photoId()).stream()) {
            assertThat(content.readAllBytes()).containsExactly(jpeg);
        }
    }

    @Test
    void rejectsInvalidImageBytesBeforeSavingRows() {
        var service = new ConversationImageService(new LocalBlobStore(directory.toString()), jdbc);

        assertThatThrownBy(() -> service.store(new ConversationImage("image/jpeg",
                Base64.getEncoder().encodeToString("not an image".getBytes()))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photos", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blobs", Integer.class)).isZero();
    }

    private static byte[] jpeg() throws Exception {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }
}
