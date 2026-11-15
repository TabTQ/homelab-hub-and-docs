package com.homelabhub.docs.github;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The content of one file of the site: bytes in memory, or a file on disk (so a 25 MB blob is never loaded whole). */
public interface SiteContent {

    long size();

    InputStream open() throws IOException;

    static SiteContent of(byte[] bytes) {
        return new SiteContent() {
            @Override
            public long size() {
                return bytes.length;
            }

            @Override
            public InputStream open() {
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    static SiteContent of(Path file) throws IOException {
        long size = Files.size(file);
        return new SiteContent() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public InputStream open() throws IOException {
                return Files.newInputStream(file);
            }
        };
    }

    /** The id git gives a file: sha1 of "blob &lt;length&gt;\0" followed by the bytes. */
    default String gitBlobSha() throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        md.update(("blob " + size() + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        long seen = 0;
        try (InputStream in = open()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                seen += n;
            }
        }
        if (seen != size()) {
            throw new IOException("a file changed while the site was being built");
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
