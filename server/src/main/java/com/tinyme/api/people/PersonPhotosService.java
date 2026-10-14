package com.tinyme.api.people;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.tinyme.domain.people.service.PersonService;
import com.tinyme.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class PersonPhotosService {
    private static final Logger log = LoggerFactory.getLogger(PersonPhotosService.class);
    private static final long MAX_IMAGE_BYTES = 15L * 1024 * 1024;
    private static final DateTimeFormatter EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT);

    private final PersonService people;
    private final BlobStore blobs;
    private final JdbcTemplate jdbc;

    public PersonPhotosService(PersonService people, BlobStore blobs, JdbcTemplate jdbc) {
        this.people = Objects.requireNonNull(people, "people");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional
    public PhotoResponse attach(String slug, MultipartFile file, String caption) throws IOException {
        var person = people.findLiveBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
        UUID personId = jdbc.queryForObject("SELECT id FROM people WHERE slug = ? AND deleted_at IS NULL", UUID.class, slug);
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a photo first");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Photos must be 15 MB or smaller");
        String contentType = normalizeContentType(file.getContentType());
        byte[] bytes = file.getBytes();
        validateImageContent(contentType, bytes);
        String extension = extension(contentType);
        Instant now = Instant.now();
        OffsetDateTime databaseNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        LocalDate month = LocalDate.now(ZoneOffset.UTC);
        UUID blobId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        String key = "photos/%04d/%02d/%s.%s".formatted(month.getYear(), month.getMonthValue(), blobId, extension);
        String cleanCaption = cleanCaption(caption);
        Metadata metadata = readMetadata(bytes);
        Instant takenAt = takenAt(metadata);
        var location = location(metadata);
        String sha256 = sha256(bytes);

        try {
            blobs.put(key, bytes);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            try { blobs.delete(key); }
                            catch (IOException failure) { log.warn("Could not remove an uncommitted photo blob", failure); }
                        }
                    }
                });
            }
            jdbc.update("""
                    INSERT INTO blobs (id, key, type, content_type, size_bytes, sha256, status, expires_at, completed_at)
                    VALUES (?, ?, 'photo', ?, ?, ?, 'ready', NULL, ?)
                    """, blobId, key, contentType, bytes.length, sha256, databaseNow);
            jdbc.update("""
                    INSERT INTO photos (id, blob_id, caption, taken_at, lat, lng)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, photoId, blobId, cleanCaption,
                    takenAt == null ? null : OffsetDateTime.ofInstant(takenAt, ZoneOffset.UTC),
                    location.latitude(), location.longitude());
            jdbc.update("INSERT INTO photo_people (photo_id, person_id) VALUES (?, ?)", photoId, personId);
            return new PhotoResponse(photoId, cleanCaption, takenAt, "/people/%s/photos/%s/content".formatted(slug, photoId));
        } catch (RuntimeException | Error failure) {
            try { blobs.delete(key); } catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public List<PhotoResponse> list(String slug) {
        if (people.findLiveBySlug(slug).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found");
        return jdbc.query("""
                SELECT p.id, p.caption, p.taken_at
                FROM photos p
                JOIN photo_people pp ON pp.photo_id = p.id
                JOIN people person ON person.id = pp.person_id
                WHERE person.slug = ? AND person.deleted_at IS NULL AND p.deleted_at IS NULL
                ORDER BY p.taken_at DESC NULLS LAST, p.created_at DESC, p.id DESC
                LIMIT 100
                """, (rs, rowNum) -> new PhotoResponse(rs.getObject("id", UUID.class), rs.getString("caption"),
                rs.getObject("taken_at", java.time.OffsetDateTime.class) == null ? null
                        : rs.getObject("taken_at", java.time.OffsetDateTime.class).toInstant(),
                "/people/%s/photos/%s/content".formatted(slug, rs.getObject("id", UUID.class))), slug);
    }

    @Transactional(readOnly = true)
    public PhotoContent content(String slug, UUID photoId) {
        var row = jdbc.query("""
                SELECT b.key, b.content_type
                FROM photos p JOIN blobs b ON b.id = p.blob_id
                JOIN photo_people pp ON pp.photo_id = p.id
                JOIN people person ON person.id = pp.person_id
                WHERE p.id = ? AND person.slug = ? AND person.deleted_at IS NULL
                  AND p.deleted_at IS NULL AND b.status = 'ready'
                """, rs -> rs.next() ? new PhotoKey(rs.getString("key"), rs.getString("content_type")) : null,
                photoId, slug);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found");
        try {
            return new PhotoContent(blobs.get(row.key()), row.contentType());
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo content is unavailable", failure);
        }
    }

    private static String normalizeContentType(String raw) {
        String type = raw == null ? "" : raw.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        return switch (type) {
            case "image/jpeg", "image/jpg" -> "image/jpeg";
            case "image/png", "image/heic", "image/heif", "image/webp" -> type;
            default -> throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Choose a JPEG, PNG, HEIC, or WebP photo");
        };
    }

    private static String extension(String type) {
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/heic", "image/heif" -> "heic";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("Unsupported image type");
        };
    }

    private static void validateImageContent(String type, byte[] bytes) {
        boolean valid = switch (type) {
            case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                    && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P'
                    && bytes[2] == 'N' && bytes[3] == 'G' && bytes[4] == 0x0d && bytes[5] == 0x0a
                    && bytes[6] == 0x1a && bytes[7] == 0x0a;
            case "image/webp" -> bytes.length >= 12 && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
            case "image/heic", "image/heif" -> bytes.length >= 12
                    && new String(bytes, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp");
            default -> false;
        };
        if (!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file isn't a valid image");
    }

    private static String cleanCaption(String caption) {
        if (caption == null || caption.isBlank()) return null;
        String clean = caption.strip();
        if (clean.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Caption must be 500 characters or fewer");
        return clean;
    }

    private static Metadata readMetadata(byte[] bytes) {
        try { return ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes)); }
        catch (Exception ignored) { return new Metadata(); }
    }

    private static Instant takenAt(Metadata metadata) {
        var directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (directory == null) return null;
        String value = directory.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
        if (value == null) return null;
        try { return java.time.LocalDateTime.parse(value, EXIF_DATE).toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static Coordinates location(Metadata metadata) {
        var gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        if (gps == null || gps.getGeoLocation() == null) return new Coordinates(null, null);
        var point = gps.getGeoLocation();
        return new Coordinates(point.getLatitude(), point.getLongitude());
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record PhotoContent(java.io.InputStream stream, String contentType) { }
    private record PhotoKey(String key, String contentType) { }
    private record Coordinates(Double latitude, Double longitude) { }
}
