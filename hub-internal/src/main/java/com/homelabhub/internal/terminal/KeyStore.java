package com.homelabhub.internal.terminal;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Private keys for the Terminal page live in {data}/ssh and are referenced from dashboard.yaml by file NAME only. A name
 * that resolves (through a symlink or otherwise) to anything but a regular file directly inside that directory is refused,
 * so editing the YAML can never make the hub read another file. Key contents are never logged.
 */
@Component
public class KeyStore {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final long MAX_KEY_BYTES = 16 * 1024;

    private final Path dir;

    @Autowired
    public KeyStore(@Value("${HUB_DATA_DIR:./data}") String dataDir) {
        this(Path.of(dataDir, "ssh"));
    }

    KeyStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    public byte[] read(String name) throws TerminalException {
        if (name == null || !NAME.matcher(name).matches() || name.contains("..")) {
            throw new TerminalException(TerminalException.Code.key_invalid, "The key name in dashboard.yaml is not a plain file name.");
        }
        String shown = "'" + name + "'";
        try {
            Path realDir = dir.toRealPath();
            Path real = dir.resolve(name).toRealPath();
            if (!real.getParent().equals(realDir) || !Files.isRegularFile(real)) {
                throw new TerminalException(TerminalException.Code.key_invalid,
                        "The key " + shown + " is not a regular file inside the data folder's ssh directory.");
            }
            if (Files.size(real) > MAX_KEY_BYTES) {
                throw new TerminalException(TerminalException.Code.key_invalid, "The key file " + shown + " is too large to be an SSH key.");
            }
            return Files.readAllBytes(real);
        } catch (NoSuchFileException e) {
            throw new TerminalException(TerminalException.Code.key_missing,
                    "The key file " + shown + " is missing from the data folder's ssh directory (see README, Terminal setup).");
        } catch (AccessDeniedException e) {
            throw new TerminalException(TerminalException.Code.key_missing,
                    "The key file " + shown + " cannot be read by the hub. It must be owned by uid 10001 with mode 600.");
        } catch (IOException e) {
            throw new TerminalException(TerminalException.Code.key_missing, "The key file " + shown + " could not be read.");
        }
    }
}
