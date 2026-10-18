package com.homelabhub.docs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Content-addressed files (D14): {@code <root>/<aa>/<sha256>}. Assets and webapp bundle files share one store, so a file used
 * on several pages is stored once and its URL never changes. Writes go to a temp file first and are renamed into place.
 */
public final class BlobStore {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    public record Stored(String sha256, long size) {}

    public static final class TooLargeException extends IOException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    private final Path root;

    public BlobStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public static boolean isSha(String s) {
        return s != null && SHA.matcher(s).matches();
    }

    /** Where {@code sha} lives (or would live). Only ever built from a validated hash, never from a user path. */
    public Path path(String sha) {
        if (!isSha(sha)) {
            throw new IllegalArgumentException("not a sha256");
        }
        return root.resolve(sha.substring(0, 2)).resolve(sha);
    }

    public boolean exists(String sha) {
        return isSha(sha) && Files.isRegularFile(path(sha));
    }

    public Stored put(byte[] bytes) throws IOException {
        return put(new java.io.ByteArrayInputStream(bytes), Long.MAX_VALUE);
    }

    /** Streams {@code in} into the store, refusing more than {@code maxBytes}. */
    public Stored put(InputStream in, long maxBytes) throws IOException {
        Files.createDirectories(root);
        Path tmp = Files.createTempFile(root, ".up-", ".tmp");
        try {
            MessageDigest md = sha256();
            long size = 0;
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(tmp, StandardOpenOption.WRITE), md)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    size += n;
                    if (size > maxBytes) {
                        throw new TooLargeException("The file is larger than " + (maxBytes / (1024 * 1024)) + " MB.");
                    }
                    out.write(buf, 0, n);
                }
            }
            String sha = HexFormat.of().formatHex(md.digest());
            Path target = path(sha);
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                worldReadable(tmp);
                try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    ch.force(true);
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // the same content arrived twice at once: either copy is fine
                }
            }
            return new Stored(sha, size);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Copies one blob into another store with the same layout (used by the publisher). No-op when already there. */
    public void copyTo(String sha, Path otherRoot) throws IOException {
        Path target = otherRoot.resolve(sha.substring(0, 2)).resolve(sha);
        if (Files.exists(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".cp-", ".tmp");
        try {
            Files.copy(path(sha), tmp, StandardCopyOption.REPLACE_EXISTING);
            worldReadable(tmp);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static void worldReadable(Path p) {
        try {
            // docs-public runs as another user and reads the snapshot copies of these files.
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not POSIX
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }
}
