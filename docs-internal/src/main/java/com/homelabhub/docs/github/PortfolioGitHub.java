package com.homelabhub.docs.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homelabhub.docs.BlobStore;
import com.homelabhub.docs.DocsException;
import com.homelabhub.docs.DocsProperties;
import com.homelabhub.docs.portfolio.SnapshotPublisher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The portfolio on GitHub Pages. Locally the portfolio goes live on every save; on GitHub it is deployed by hand. Deploy stores a copy
 * of the current snapshot, remembers when and a hash of its content (without the envelope's timestamp), and asks for a sync. The site
 * always uses the stored copy, so editing the portfolio makes it "behind" until the next deploy. Remove deletes the copy and the marks.
 */
@Service
public class PortfolioGitHub {

    static final String KEY_HASH = "github.portfolio.hash";
    static final String KEY_AT = "github.portfolio.at";

    private final JdbcClient jdbc;
    private final SnapshotPublisher snapshot;
    private final GitHubSync sync;
    private final GitHubProperties props;
    private final StaticSite site;
    private final ObjectMapper json;
    private final Clock clock;
    private final Path stored;

    public PortfolioGitHub(JdbcClient jdbc, SnapshotPublisher snapshot, GitHubSync sync, GitHubProperties props, StaticSite site,
                           ObjectMapper json, Clock clock, DocsProperties docs) {
        this.jdbc = jdbc;
        this.snapshot = snapshot;
        this.sync = sync;
        this.props = props;
        this.site = site;
        this.json = json;
        this.clock = clock;
        this.stored = StaticSite.portfolioFile(docs);
    }

    public synchronized Map<String, Object> status() {
        return view();
    }

    public synchronized Map<String, Object> deploy() {
        requireEnabled();
        byte[] bytes = currentSnapshot();
        try {
            Files.createDirectories(stored.getParent());
            Path tmp = Files.createTempFile(stored.getParent(), ".portfolio-", ".tmp");
            try {
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, stored, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, stored, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new DocsException(HttpStatus.INTERNAL_SERVER_ERROR, "write_failed", "The portfolio could not be stored for GitHub.");
        }
        put(KEY_HASH, hash(bytes));
        put(KEY_AT, clock.instant().toString());
        sync.requestSync();
        return view();
    }

    public synchronized Map<String, Object> remove() {
        requireEnabled();
        try {
            Files.deleteIfExists(stored);
        } catch (IOException e) {
            throw new DocsException(HttpStatus.INTERNAL_SERVER_ERROR, "write_failed", "The stored portfolio could not be removed.");
        }
        jdbc.sql("DELETE FROM kv WHERE k IN (?, ?)").params(KEY_HASH, KEY_AT).update();
        sync.requestSync();
        return view();
    }

    /** The static portfolio site as a zip, files at the zip root. Works whether or not GitHub is enabled. */
    public byte[] exportZip() {
        byte[] bytes = currentSnapshot();
        try {
            Map<String, SiteContent> files = site.portfolioFiles(SiteContent.of(bytes));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (Map.Entry<String, SiteContent> e : files.entrySet()) {
                    zip.putNextEntry(new ZipEntry(e.getKey()));
                    try (InputStream in = e.getValue().open()) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IllegalStateException e) {
            throw new DocsException(HttpStatus.SERVICE_UNAVAILABLE, "bundle_missing", e.getMessage());
        } catch (IOException e) {
            throw new DocsException(HttpStatus.INTERNAL_SERVER_ERROR, "export_failed", "The static site could not be built.");
        }
    }

    // ---------------------------------------------------------------- state

    private Map<String, Object> view() {
        Optional<String> deployedHash = get(KEY_HASH);
        boolean deployed = deployedHash.isPresent() && Files.isRegularFile(stored);
        boolean behind = false;
        if (deployed) {
            try {
                behind = !hash(Files.readAllBytes(snapshot.file())).equals(deployedHash.get());
            } catch (IOException | RuntimeException e) {
                behind = true; // no readable snapshot to compare with: do not claim it is up to date
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("deployed", deployed);
        m.put("deployedAt", deployed ? get(KEY_AT).orElse(null) : null);
        m.put("behind", behind);
        m.put("url", props.enabled() ? props.effectiveSiteUrl() + "portfolio/" : null);
        m.put("sync", sync.summary());
        return m;
    }

    private void requireEnabled() {
        if (!props.enabled()) {
            throw new DocsException(HttpStatus.BAD_REQUEST, "github_off", "GitHub Pages publishing is not turned on (DOCS_GH_ENABLED).");
        }
    }

    private byte[] currentSnapshot() {
        try {
            if (!Files.isRegularFile(snapshot.file())) {
                snapshot.publish();
            }
            return Files.readAllBytes(snapshot.file());
        } catch (IOException e) {
            throw new DocsException(HttpStatus.CONFLICT, "no_snapshot", "The portfolio has no published copy yet. Save it once and try again.");
        }
    }

    /** A hash of the snapshot that ignores the envelope's timestamp, so only a real change of content counts. */
    String hash(byte[] snapshotBytes) {
        try {
            JsonNode n = json.readTree(snapshotBytes);
            if (n instanceof ObjectNode o) {
                o.remove("generatedAt");
            }
            return BlobStore.sha256Hex(json.writeValueAsBytes(n));
        } catch (IOException e) {
            return BlobStore.sha256Hex(snapshotBytes);
        }
    }

    private Optional<String> get(String key) {
        return jdbc.sql("SELECT v FROM kv WHERE k = ?").param(key).query(String.class).optional();
    }

    private void put(String key, String value) {
        jdbc.sql("INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").params(key, value).update();
    }
}
