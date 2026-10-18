package com.homelabhub.internal.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Secrets for ${secret:NAME}. Sources, in order: environment variable HUB_SECRET_NAME, then a KEY=VALUE file
 * (default {data}/secrets.env). The file is re-read when it changes, so adding a secret needs no restart.
 * Values are only ever handed to the code that calls the upstream service; nothing here logs or returns them to the UI.
 */
@Component
public class SecretStore implements SecretLookup {

    private static final Logger log = LoggerFactory.getLogger(SecretStore.class);

    private final Path file;
    private volatile long loadedMtime = -1;
    private volatile Map<String, String> fromFile = Map.of();

    public SecretStore(@Value("${hub.secrets.file:}") String configured, @Value("${HUB_DATA_DIR:./data}") String dataDir) {
        this.file = configured == null || configured.isBlank() ? Path.of(dataDir, "secrets.env") : Path.of(configured);
    }

    @Override
    public boolean isSet(String name) {
        return get(name).isPresent();
    }

    public Optional<String> get(String name) {
        String env = System.getenv("HUB_SECRET_" + name);
        if (env != null && !env.isEmpty()) {
            return Optional.of(env);
        }
        reloadIfChanged();
        String v = fromFile.get(name);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v);
    }

    /** Replaces every ${secret:NAME} in the text. A missing secret throws, so a request is never sent with a hole in it. */
    public String resolve(String text) {
        if (text == null || !text.contains("${secret:")) {
            return text;
        }
        var m = ConfigParser.SECRET_REF_ANYWHERE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = get(name).orElseThrow(() -> new MissingSecretException(name));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static final class MissingSecretException extends RuntimeException {
        private final String secretName;

        public MissingSecretException(String name) {
            super("Secret '" + name + "' is not set");
            this.secretName = name;
        }

        public String secretName() {
            return secretName;
        }
    }

    private synchronized void reloadIfChanged() {
        try {
            if (!Files.isRegularFile(file)) {
                fromFile = Map.of();
                loadedMtime = -1;
                return;
            }
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (mtime == loadedMtime) {
                return;
            }
            checkPermissions();
            Map<String, String> parsed = new HashMap<>();
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String val = line.substring(eq + 1).strip();
                if (val.length() >= 2 && (val.startsWith("\"") && val.endsWith("\"") || val.startsWith("'") && val.endsWith("'"))) {
                    val = val.substring(1, val.length() - 1);
                }
                parsed.put(key, val);
            }
            fromFile = Map.copyOf(parsed);
            loadedMtime = mtime;
            log.info("Loaded {} secret name(s) from {}", parsed.size(), file);
        } catch (IOException e) {
            log.warn("Could not read secrets file {}: {}", file, e.getMessage());
        }
    }

    private void checkPermissions() {
        try {
            var perms = Files.getPosixFilePermissions(file);
            boolean loose = perms.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"));
            if (loose) {
                log.warn("{} is readable by other users. Run: chmod 600 {}", file, file);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system
        }
    }
}
