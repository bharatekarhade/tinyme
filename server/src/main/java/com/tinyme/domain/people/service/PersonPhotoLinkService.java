package com.tinyme.domain.people.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Links an already stored photo (such as the image attached to this chat turn) to a person. */
@Service
public class PersonPhotoLinkService {
    private final JdbcTemplate jdbc;

    public PersonPhotoLinkService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional
    public String addToProfile(String slug, UUID photoId) {
        Objects.requireNonNull(photoId, "photoId");
        String displayName = jdbc.query("""
                SELECT display_name FROM people WHERE slug = ? AND deleted_at IS NULL
                """, rs -> rs.next() ? rs.getString("display_name") : null, slug);
        if (displayName == null) throw new PersonNotFoundException(slug);

        boolean photoAvailable = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM photos p JOIN blobs b ON b.id = p.blob_id
                    WHERE p.id = ? AND p.deleted_at IS NULL AND b.status = 'ready'
                )
                """, Boolean.class, photoId));
        if (!photoAvailable) throw new PhotoUnavailableException();

        jdbc.update("""
                INSERT INTO photo_people (photo_id, person_id)
                SELECT p.id, person.id
                FROM photos p CROSS JOIN people person
                WHERE p.id = ? AND person.slug = ? AND person.deleted_at IS NULL
                ON CONFLICT (photo_id, person_id) DO NOTHING
                """, photoId, slug);
        return displayName;
    }

    public static final class PersonNotFoundException extends RuntimeException {
        public PersonNotFoundException(String slug) { super(slug); }
    }

    public static final class PhotoUnavailableException extends RuntimeException {
        public PhotoUnavailableException() { super("The attached photo is no longer available"); }
    }
}
