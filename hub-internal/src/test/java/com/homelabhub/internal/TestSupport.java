package com.homelabhub.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared helpers for Spring tests: a throwaway SQLite database per test class. */
public final class TestSupport {

    private TestSupport() {}

    public static Path tempDir() {
        try {
            return Files.createTempDirectory("hub-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void useTempDatabase(DynamicPropertyRegistry r, Path dir) {
        r.add("HUB_DATA_DIR", dir::toString);
        r.add("hub.config.poll-millis", () -> 1000);
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + dir.resolve("hub.db")
                + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL");
    }
}
