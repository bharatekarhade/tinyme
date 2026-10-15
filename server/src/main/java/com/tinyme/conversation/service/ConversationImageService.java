package com.tinyme.conversation.service;

import com.tinyme.conversation.model.ConversationImage;
import com.tinyme.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Stores chat images once, while returning both the model input and history reference. */
@Service
public class ConversationImageService {
    private static final Logger log = LoggerFactory.getLogger(ConversationImageService.class);
    private static final int MAX_BYTES = 1_100_000;

    private final BlobStore blobs;
    private final JdbcTemplate jdbc;

    public ConversationImageService(BlobStore blobs, JdbcTemplate jdbc) {
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional
    public StoredImage store(ConversationImage image) throws IOException {
        String mediaType = normalize(image.mediaType());
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(image.data());
        } catch (IllegalArgumentException malformed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The attached image is invalid", malformed);
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES || !matchesSignature(mediaType, bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid JPEG, PNG, or WebP image under 1.1 MB");
        }

        UUID blobId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        LocalDate month = LocalDate.now(ZoneOffset.UTC);
        String extension = switch (mediaType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new IllegalStateException("Unexpected normalized image type");
        };
        String key = "photos/%04d/%02d/%s.%s".formatted(month.getYear(), month.getMonthValue(), blobId, extension);
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        try {
            blobs.put(key, bytes);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            try { blobs.delete(key); }
                            catch (IOException failure) { log.warn("Could not remove a rolled-back chat image", failure); }
                        }
                    }
                });
            }
            jdbc.update("""
                    INSERT INTO blobs (id, key, type, content_type, size_bytes, sha256, status, expires_at, completed_at)
                    VALUES (?, ?, 'photo', ?, ?, ?, 'ready', NULL, ?)
                    """, blobId, key, mediaType, bytes.length, sha256(bytes), now);
            jdbc.update("INSERT INTO photos (id, blob_id) VALUES (?, ?)", photoId, blobId);
        } catch (RuntimeException | Error failure) {
            try { blobs.delete(key); } catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }

        String url = "/photos/%s/content".formatted(photoId);
        Map<String, Object> history = Map.of("photo_id", photoId.toString(), "content_url", url,
                "content_type", mediaType);
        Map<String, Object> model = Map.of("type", "image", "source", Map.of("type", "base64",
                "media_type", mediaType, "data", image.data()));
        return new StoredImage(photoId, blobId, key, history, model);
    }

    @Transactional
    public void deleteStored(StoredImage image) {
        try { blobs.delete(image.key()); }
        catch (IOException failure) { log.warn("Could not remove an unused chat image", failure); }
        jdbc.update("DELETE FROM photos WHERE id = ?", image.photoId());
        jdbc.update("DELETE FROM blobs WHERE id = ?", image.blobId());
    }

    @Transactional(readOnly = true)
    public ImageContent content(UUID photoId) {
        var row = jdbc.query("""
                SELECT b.key, b.content_type FROM photos p JOIN blobs b ON b.id = p.blob_id
                WHERE p.id = ? AND p.deleted_at IS NULL AND b.status = 'ready'
                """, rs -> rs.next() ? new BlobRef(rs.getString("key"), rs.getString("content_type")) : null,
                photoId);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found");
        try {
            return new ImageContent(blobs.get(row.key()), row.contentType());
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo content is unavailable", failure);
        }
    }

    private static String normalize(String mediaType) {
        return switch (mediaType == null ? "" : mediaType.toLowerCase(java.util.Locale.ROOT)) {
            case "image/jpeg" -> "image/jpeg";
            case "image/png" -> "image/png";
            case "image/webp" -> "image/webp";
            default -> throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Chat images must be JPEG, PNG, or WebP");
        };
    }

    private static boolean matchesSignature(String type, byte[] bytes) {
        return switch (type) {
            case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P'
                    && bytes[2] == 'N' && bytes[3] == 'G' && bytes[4] == 13 && bytes[5] == 10
                    && bytes[6] == 26 && bytes[7] == 10;
            case "image/webp" -> bytes.length >= 12
                    && "RIFF".equals(new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                    && "WEBP".equals(new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
            default -> false;
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record StoredImage(UUID photoId, UUID blobId, String key,
                              Map<String, Object> history, Map<String, Object> model) {
        public StoredImage {
            history = Map.copyOf(history);
            model = Map.copyOf(model);
        }
    }

    public record ImageContent(java.io.InputStream stream, String contentType) { }
    private record BlobRef(String key, String contentType) { }
}
