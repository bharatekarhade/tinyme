package com.tinyme.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class LocalBlobStore implements BlobStore {
    private static final Pattern KEY = Pattern.compile("^photos/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.(jpg|png|heic|webp)$");
    private final Path root;

    public LocalBlobStore(@Value("${tinyme.blobs.path:./blobs}") String root) {
        this.root = Path.of(Objects.requireNonNull(root, "root")).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes) throws IOException {
        Path destination = resolve(key);
        Files.createDirectories(destination.getParent());
        Path part = Files.createTempFile(destination.getParent(), ".tinyme-photo-", ".part");
        try {
            Files.write(part, bytes);
            try {
                Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }

    @Override
    public InputStream get(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public void deleteAll() throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                if (!directory.equals(root)) Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid blob key");
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid blob key");
        return path;
    }
}
