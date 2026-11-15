package com.homelabhub.docs.github;

import com.homelabhub.docs.DocsAudit;
import com.homelabhub.docs.DocsProperties;
import com.homelabhub.docs.github.GitHubClient.GitHubException;
import com.homelabhub.docs.github.GitHubClient.PagesBuild;
import com.homelabhub.docs.github.GitHubClient.Remote;
import com.homelabhub.docs.github.GitHubClient.TreeEntry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the GitHub Pages branch equal to the site the database describes. One run builds the whole tree, works out each file's git
 * blob id, uploads only blobs the branch does not have yet, writes a tree and a parentless commit, and moves the branch (a commit is
 * skipped when nothing changed). Then it watches the Pages build until it is live.
 *
 * <p>{@link #requestSync()} only queues the work on one thread and never blocks or throws, so a GitHub problem can never fail or delay
 * a local publish. A failed run keeps the state {@code error} with a short message and is tried again on a timer. With the feature
 * off nothing here runs.
 */
@Service
public class GitHubSync {

    private static final Logger log = LoggerFactory.getLogger(GitHubSync.class);

    public enum State { OFF, IDLE, PENDING, BUILDING, LIVE, ERROR }

    public record Status(State state, String message, Instant lastSyncedAt, int files) {}

    private final GitHubProperties props;
    private final GitHubClient client;
    private final SiteSource source;
    private final Path dryRunDir;
    private final DocsAudit audit;
    private final long pollMillis;
    private final long pollMaxMillis;
    private final ReentrantLock runLock = new ReentrantLock();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile int done = 0;
    private volatile boolean autoRetry = true;
    private volatile Status status;
    private ExecutorService executor;

    @Autowired
    public GitHubSync(GitHubProperties props, StaticSite site, DocsProperties docs, DocsAudit audit) {
        this(props, props.enabled() && !props.dryRun() && props.configError() == null ? new GitHubClient(props) : null, site,
                Path.of(docs.dataDir(), "github-dry-run"), audit, 3000, 120_000);
    }

    public GitHubSync(GitHubProperties props, GitHubClient client, SiteSource source, Path dryRunDir, DocsAudit audit, long pollMillis, long pollMaxMillis) {
        this.props = props;
        this.client = client;
        this.source = source;
        this.dryRunDir = dryRunDir;
        this.audit = audit;
        this.pollMillis = pollMillis;
        this.pollMaxMillis = pollMaxMillis;
        this.status = props.enabled() ? new Status(State.IDLE, "Nothing sent yet.", null, 0) : new Status(State.OFF, "GitHub Pages publishing is off.", null, 0);
    }

    public boolean enabled() {
        return props.enabled();
    }

    public Status status() {
        return status;
    }

    /** The shape of GET /github/status. */
    public Map<String, Object> toMap() {
        Status s = status;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("dryRun", props.enabled() && props.dryRun());
        m.put("state", s.state().name().toLowerCase(java.util.Locale.ROOT));
        m.put("message", s.message());
        m.put("lastSyncedAt", s.lastSyncedAt() == null ? null : s.lastSyncedAt().toString());
        m.put("siteUrl", props.enabled() ? props.effectiveSiteUrl() : null);
        m.put("files", s.files());
        return m;
    }

    /** The shape of the {@code github} object in a publish response: only the state and its message. */
    public Map<String, Object> summary() {
        Status s = status;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", s.state().name().toLowerCase(java.util.Locale.ROOT));
        m.put("message", s.message());
        return m;
    }

    // ---------------------------------------------------------------- triggers

    /** Queues a run and returns at once. Safe to call from anywhere, any number of times: runs that pile up are merged. */
    public void requestSync() {
        try {
            if (!props.enabled()) {
                return;
            }
            generation.incrementAndGet();
            autoRetry = true;
            set(State.PENDING, "Waiting to send to GitHub.", status.lastSyncedAt(), status.files());
            enqueue();
        } catch (RuntimeException e) {
            log.warn("Could not queue the GitHub sync: {}", e.toString());
        }
    }

    /** The retry button. */
    public Map<String, Object> retry() {
        requestSync();
        return toMap();
    }

    @Scheduled(fixedDelayString = "${docs.github.retry-millis:30000}", initialDelayString = "${docs.github.retry-millis:30000}")
    void retryErrors() {
        if (props.enabled() && status.state() == State.ERROR && autoRetry && props.configError() == null) {
            enqueue();
        }
    }

    private synchronized void enqueue() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "github-sync");
                t.setDaemon(true);
                return t;
            });
        }
        executor.execute(this::drain);
    }

    @PreDestroy
    synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Runs until the last request has been served. A failure stops the loop; the timer or the retry button starts it again. */
    void drain() {
        runLock.lock();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int g = generation.get();
                if (g == done) {
                    return;
                }
                if (!runOnce(g)) {
                    return;
                }
                done = g;
            }
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            runLock.unlock();
        }
    }

    // ---------------------------------------------------------------- one run

    private boolean runOnce(int g) {
        try {
            String cfg = props.configError();
            if (cfg != null) {
                return error("GitHub publishing is not configured: " + cfg + ".", false);
            }
            Map<String, SiteContent> site = source.build();
            Map<String, String> shas = new TreeMap<>();
            for (Map.Entry<String, SiteContent> e : site.entrySet()) {
                shas.put(e.getKey(), e.getValue().gitBlobSha());
            }
            if (props.dryRun()) {
                writeDryRun(site);
                set(State.LIVE, "Dry run: " + site.size() + " files were written to " + dryRunDir + ". Nothing was sent to GitHub.", Instant.now(), site.size());
                return true;
            }
            Optional<Remote> remote = client.readBranch();
            // Every run replaces the whole branch. Refuse a branch this app did not write (for example one holding source code).
            if (remote.isPresent() && !remote.get().files().isEmpty() && !remote.get().files().containsKey(StaticSite.MARKER)) {
                return error("The branch " + props.branch() + " already holds other files, so it was left alone. "
                        + "Point DOCS_GH_BRANCH at an empty or new branch.", false);
            }
            if (remote.isPresent() && !remote.get().truncated() && !remote.get().otherEntries() && remote.get().files().equals(shas)) {
                return awaitBuild(remote.get().commitSha(), g, site.size(), "Up to date.");
            }
            String commit = push(site, shas, remote);
            audit("ok", site.size() + " files, commit " + commit.substring(0, Math.min(7, commit.length())));
            return awaitBuild(commit, g, site.size(), "Sent " + site.size() + " files.");
        } catch (GitHubException e) {
            audit("failed", e.getMessage());
            return error(e.getMessage(), true);
        } catch (IOException | RuntimeException e) {
            fail(e);
            return false;
        }
    }

    private String push(Map<String, SiteContent> site, Map<String, String> shas, Optional<Remote> remote) throws IOException {
        Set<String> have = remote.isPresent() ? new HashSet<>(remote.get().files().values()) : Set.of();
        Set<String> uploaded = new HashSet<>();
        int n = 0;
        for (Map.Entry<String, SiteContent> e : site.entrySet()) {
            String sha = shas.get(e.getKey());
            if (have.contains(sha) || !uploaded.add(sha)) {
                continue;
            }
            n++;
            set(State.PENDING, "Sending file " + n + " to GitHub.", status.lastSyncedAt(), status.files());
            String got = client.createBlob(e.getValue());
            if (!sha.equals(got)) {
                throw new IllegalStateException("GitHub stored a different file than the one sent.");
            }
        }
        List<TreeEntry> entries = new ArrayList<>();
        shas.forEach((path, sha) -> entries.add(new TreeEntry(path, sha)));
        String tree = client.createTree(entries);
        String commit = client.createCommit("Publish site " + Instant.now(), tree);
        client.moveBranch(commit, remote.isPresent());
        return commit;
    }

    /**
     * Watches the Pages build of {@code commit} until it is built (live), failed (error) or the time is up. A 403 or 404 from the
     * Pages API means it is not available to this token or repository: the files are sent, so the state is live.
     */
    private boolean awaitBuild(String commit, int g, int files, String sentMessage) {
        Instant sent = Instant.now();
        set(State.BUILDING, sentMessage + " GitHub Pages is building.", sent, files);
        long deadline = System.currentTimeMillis() + pollMaxMillis;
        while (true) {
            if (generation.get() != g) {
                return true; // a newer request supersedes this one; the loop in drain() starts it
            }
            try {
                PagesBuild b = client.latestBuild();
                if (commit.equals(b.commit()) || b.commit().isEmpty()) {
                    if (b.status().equals("built")) {
                        setIfCurrent(g, State.LIVE, sentMessage + " The site is live.", sent, files);
                        return true;
                    }
                    if (b.status().equals("errored")) {
                        return generation.get() != g || error("GitHub Pages could not build the site" + (b.error() == null ? "." : ": " + b.error()), false);
                    }
                }
            } catch (GitHubException e) {
                if (e.status() == 403 || e.status() == 404) {
                    setIfCurrent(g, State.LIVE, sentMessage + " GitHub does not report the build to this token, so the site may need a minute to appear.", sent, files);
                    return true;
                }
                log.debug("Pages build check failed, trying again: {}", e.getMessage());
            }
            if (System.currentTimeMillis() >= deadline) {
                setIfCurrent(g, State.LIVE, sentMessage + " GitHub has not confirmed the build yet. It can take a few minutes.", sent, files);
                return true;
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void writeDryRun(Map<String, SiteContent> site) throws IOException {
        if (Files.isDirectory(dryRunDir)) {
            try (Stream<Path> walk = Files.walk(dryRunDir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Path base = dryRunDir.toAbsolutePath().normalize();
        Files.createDirectories(base);
        for (Map.Entry<String, SiteContent> e : site.entrySet()) {
            Path target = base.resolve(e.getKey()).normalize();
            if (!target.startsWith(base)) {
                throw new IOException("a file path leaves the output folder");
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = e.getValue().open()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ---------------------------------------------------------------- status

    private synchronized void set(State state, String message, Instant lastSynced, int files) {
        status = new Status(state, message, lastSynced, files);
    }

    /** A run that has been overtaken by a newer request must not overwrite the newer request's status. */
    private synchronized void setIfCurrent(int g, State state, String message, Instant lastSynced, int files) {
        if (generation.get() == g) {
            set(state, message, lastSynced, files);
        }
    }

    private boolean error(String message, boolean retry) {
        autoRetry = retry;
        String m = message.length() > 300 ? message.substring(0, 300) + "..." : message;
        log.warn("GitHub sync stopped: {}", m);
        set(State.ERROR, m, status.lastSyncedAt(), status.files());
        return false;
    }

    private void fail(Exception e) {
        String m = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        if (client != null) {
            m = client.scrub(m);
        }
        audit("failed", m);
        error("The site could not be built or sent: " + m, true);
    }

    private void audit(String result, String detail) {
        if (audit != null) {
            audit.record("github-sync", props.repo(), result, detail == null ? null : (client == null ? detail : client.scrub(detail)), null, null);
        }
    }
}
