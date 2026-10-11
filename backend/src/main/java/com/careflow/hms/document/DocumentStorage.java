package com.careflow.hms.document;

import com.careflow.hms.config.CareFlowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Local file storage for patient documents. Storage keys are random and generated here - never derived
 * from user input - and every resolved path is checked to stay inside the configured upload directory.
 */
@Slf4j
@Component
public class DocumentStorage {

    private final Path root;

    public DocumentStorage(CareFlowProperties props) {
        String dir = props.storage() == null || props.storage().uploadDir() == null ? "./uploads" : props.storage().uploadDir();
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    public String store(UUID organizationId, String extension, byte[] content) {
        LocalDate d = LocalDate.now();
        String key = organizationId + "/" + d.getYear() + "/" + String.format("%02d", d.getMonthValue()) + "/"
                + UUID.randomUUID() + "." + extension;
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store document", e);
        }
        return key;
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read document", e);
        }
    }

    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    public void deleteQuietly(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not remove orphaned document file {}", key);
        }
    }

    Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return p;
    }
}
