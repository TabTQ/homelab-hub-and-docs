package com.homelabhub.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Plan N4: the terminal does nothing while unused, so nothing in the package may be @Scheduled. */
class NoBackgroundWorkTest {

    @Test
    void theTerminalPackageHasNoScheduledMethods() throws IOException {
        Path pkg = Path.of("src/main/java/com/homelabhub/internal/terminal");
        assertThat(pkg).isDirectory();
        try (Stream<Path> files = Files.list(pkg)) {
            files.filter(f -> f.toString().endsWith(".java")).forEach(f -> {
                try {
                    assertThat(Files.readString(f)).as(f.getFileName().toString()).doesNotContain("@Scheduled").doesNotContain("@Async");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
