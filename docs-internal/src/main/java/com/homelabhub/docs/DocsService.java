package com.homelabhub.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homelabhub.docs.cloudflare.CloudflareRoutes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pages, the tree, drafts, versions, the Trash, uploads and publishing (plan 5.2-5.4). Every change bumps the page's
 * {@code version} (optimistic locking: a stale editor gets 409) and most add a row to the page history. Public output is
 * produced by {@link DocsPublisher} after the database change has committed.
 */
@Service
public class DocsService {

    private static final Logger log = LoggerFactory.getLogger(DocsService.class);
    static final int KEEP_VERSIONS = 100;
    static final Duration TRASH_DAYS = Duration.ofDays(30);
    static final Duration BLOB_GRACE = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final BlobStore blobs;
    private final HostRules hostRules;
    private final DocsPublisher publisher;
    private final DocsProperties props;
    private final DocsAudit audit;
    private final CloudflareRoutes routes;
    private final Object writeLock = new Object();

    public DocsService(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper json, Clock clock, BlobStore blobs,
                       DocsPublisher publisher, DocsProperties props, DocsAudit audit, CloudflareRoutes routes) {
        this.audit = audit;
        this.routes = routes;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.blobs = blobs;
        this.hostRules = new HostRules(props.domainList());
        this.publisher = publisher;
        this.props = props;
    }

    // ---------------------------------------------------------------- rows

    record PageRow(long id, Long parentId, int position, String kind, String title, String draftJson, Long draftBundle,
                   String publicHost, Integer publishedVersion, String publishedAt, String deletedAt, Long trashRoot,
                   String createdAt, String updatedAt, long version) {
        boolean rich() {
            return kind.equals("rich");
        }
    }

    record VersionRow(long pageId, int version, String kind, String title, String bodyJson, Long bundleId, String note, String createdAt) {}

    record BundleRow(long id, long pageId, int seq, String sha256, String manifestJson, long totalSize, String createdAt) {}

    private static final String PAGE_COLS = "id, parent_id, position, kind, title, draft_json, draft_bundle, public_host, "
            + "published_version, published_at, deleted_at, trash_root, created_at, updated_at, version";

    List<PageRow> livePages() {
        return jdbc.sql("SELECT " + PAGE_COLS + " FROM doc_pages WHERE deleted_at IS NULL").query(PageRow.class).list();
    }

    Optional<PageRow> page(long id) {
        return jdbc.sql("SELECT " + PAGE_COLS + " FROM doc_pages WHERE id = ?").param(id).query(PageRow.class).optional();
    }

    PageRow livePage(long id) {
        PageRow p = page(id).orElseThrow(DocsException::notFound);
        if (p.deletedAt() != null) {
            throw DocsException.notFound();
        }
        return p;
    }

    Optional<VersionRow> versionRow(long pageId, int version) {
        return jdbc.sql("SELECT page_id, version, kind, title, body_json, bundle_id, note, created_at FROM doc_page_versions WHERE page_id = ? AND version = ?")
                .params(pageId, version).query(VersionRow.class).optional();
    }

    Optional<BundleRow> bundle(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT id, page_id, seq, sha256, manifest_json, total_size, created_at FROM doc_bundles WHERE id = ?")
                .param(id).query(BundleRow.class).optional();
    }

    Optional<BundleRow> bundleBySha(String sha) {
        return jdbc.sql("SELECT id, page_id, seq, sha256, manifest_json, total_size, created_at FROM doc_bundles WHERE sha256 = ? ORDER BY id DESC LIMIT 1")
                .param(sha).query(BundleRow.class).optional();
    }

    private String now() {
        return clock.instant().toString();
    }

    private <T> T write(Supplier<T> work) {
        synchronized (writeLock) {
            return tx.execute(s -> work.get());
        }
    }

    private void checkVersion(PageRow p, Long expected) {
        if (expected == null || expected != p.version()) {
            throw DocsException.conflict(p.version());
        }
    }

    private int nextHistory(long pageId) {
        return jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM doc_page_versions WHERE page_id = ?").param(pageId).query(Integer.class).single();
    }

    private int addHistory(long pageId, String kind, String title, String body, Long bundleId, String note) {
        int v = nextHistory(pageId);
        jdbc.sql("INSERT INTO doc_page_versions (page_id, version, kind, title, body_json, bundle_id, note, created_at) VALUES (?,?,?,?,?,?,?,?)")
                .params(pageId, v, kind, title, body, bundleId, note, now()).update();
        prune(pageId);
        return v;
    }

    /** Keeps the newest {@link #KEEP_VERSIONS} versions per page; published versions and the live one are never pruned. */
    private void prune(long pageId) {
        jdbc.sql("""
                DELETE FROM doc_page_versions WHERE page_id = :p AND kind <> 'published'
                  AND version <> COALESCE((SELECT published_version FROM doc_pages WHERE id = :p), -1)
                  AND version NOT IN (SELECT version FROM doc_page_versions WHERE page_id = :p AND kind <> 'published' ORDER BY version DESC LIMIT :keep)
                """).param("p", pageId).param("keep", KEEP_VERSIONS).update();
    }

    private void bump(long id) {
        jdbc.sql("UPDATE doc_pages SET version = version + 1, updated_at = ? WHERE id = ?").params(now(), id).update();
    }

    private void applyPlacements(List<DocTree.Placement> placements, Long skipId) {
        for (DocTree.Placement pl : placements) {
            if (skipId != null && pl.id() == skipId) {
                continue;
            }
            jdbc.sql("UPDATE doc_pages SET parent_id = ?, position = ? WHERE id = ?").params(pl.parentId(), pl.position(), pl.id()).update();
        }
    }

    private DocTree tree(List<PageRow> live) {
        return new DocTree(live.stream().map(p -> new DocTree.Node(p.id(), p.parentId(), p.position())).toList());
    }

    String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON is corrupt", e);
        }
    }

    // ---------------------------------------------------------------- tree

    public record TreeItem(long id, Long parentId, int position, String kind, String title, String publicHost, String state) {}

    public List<TreeItem> tree() {
        housekeeping();
        List<PageRow> live = livePages();
        return live.stream().sorted(Comparator.comparing((PageRow p) -> p.parentId() == null ? 0L : p.parentId()).thenComparingInt(PageRow::position))
                .map(p -> new TreeItem(p.id(), p.parentId(), p.position(), p.kind(), p.title(), p.publicHost(), state(p))).toList();
    }

    /** internal | public | public-changed (the draft differs from the live version) */
    String state(PageRow p) {
        if (p.publishedVersion() == null || p.publicHost() == null) {
            return "internal";
        }
        Optional<VersionRow> live = versionRow(p.id(), p.publishedVersion());
        if (live.isEmpty()) {
            return "public-changed";
        }
        VersionRow v = live.get();
        boolean same = v.title().equals(p.title()) && parse(v.bodyJson()).equals(parse(p.draftJson())) && Objects.equals(v.bundleId(), p.draftBundle());
        return same ? "public" : "public-changed";
    }

    public long create(Long parentId, Long afterId, String kind, String title) {
        if (!"rich".equals(kind) && !"webapp".equals(kind)) {
            throw DocsException.bad("A page is either rich or webapp.");
        }
        String t = cleanTitle(title);
        return write(() -> {
            List<PageRow> live = livePages();
            List<DocTree.Placement> placements;
            try {
                placements = tree(live).insert(parentId, afterId);
            } catch (IllegalArgumentException e) {
                throw DocsException.bad(e.getMessage());
            }
            int position = placements.stream().filter(p -> p.id() == -1L).findFirst().orElseThrow().position();
            applyPlacements(placements, -1L);
            String body = toJson(kind.equals("rich") ? DocSchema.empty() : WebappSettings.defaults());
            String n = now();
            long id = jdbc.sql("INSERT INTO doc_pages (parent_id, position, kind, title, draft_json, created_at, updated_at) VALUES (?,?,?,?,?,?,?) RETURNING id")
                    .params(parentId, position, kind, t, body, n, n).query(Long.class).single();
            addHistory(id, "created", t, body, null, null);
            return id;
        });
    }

    static String cleanTitle(String title) {
        String t = title == null ? "" : title.strip().replaceAll("[\\p{Cntrl}]", " ");
        if (t.isEmpty()) {
            throw DocsException.bad("The title cannot be empty.");
        }
        if (t.length() > 200) {
            throw DocsException.bad("The title is longer than 200 characters.");
        }
        return t;
    }

    // ---------------------------------------------------------------- one page

    public Map<String, Object> get(long id) {
        PageRow p = livePage(id);
        List<PageRow> live = livePages();
        Map<Long, PageRow> byId = new HashMap<>();
        live.forEach(x -> byId.put(x.id(), x));
        List<Map<String, Object>> crumbs = tree(live).ancestors(id).stream()
                .map(a -> Map.<String, Object>of("id", a, "title", byId.get(a).title())).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.id());
        out.put("parentId", p.parentId());
        out.put("kind", p.kind());
        out.put("title", p.title());
        out.put("body", parse(p.draftJson()));
        out.put("publicHost", p.publicHost());
        out.put("publishedVersion", p.publishedVersion());
        out.put("publishedAt", p.publishedAt());
        out.put("updatedAt", p.updatedAt());
        out.put("createdAt", p.createdAt());
        out.put("version", p.version());
        out.put("state", state(p));
        out.put("latestVersion", nextHistory(id) - 1);
        out.put("breadcrumbs", crumbs);
        out.put("childCount", tree(live).children(id).size());
        bundle(p.draftBundle()).ifPresent(b -> out.put("draftBundle", bundleView(b, p)));
        if (p.publishedVersion() != null) {
            versionRow(id, p.publishedVersion()).flatMap(v -> bundle(v.bundleId())).ifPresent(b -> out.put("publishedBundle", bundleView(b, p)));
        }
        return out;
    }

    /** Saves the draft. Never changes the public page. */
    public Map<String, Object> save(long id, String title, JsonNode body, Long expectedVersion) {
        String t = cleanTitle(title);
        return write(() -> {
            PageRow p = livePage(id);
            checkVersion(p, expectedVersion);
            ObjectNode clean = p.rich() ? DocSchema.validate(body).doc() : WebappSettings.validate(body);
            String bodyJson = toJson(clean);
            jdbc.sql("UPDATE doc_pages SET title = ?, draft_json = ? WHERE id = ?").params(t, bodyJson, id).update();
            boolean renamedOnly = !t.equals(p.title()) && parse(p.draftJson()).equals(clean);
            int v = addHistory(id, renamedOnly ? "renamed" : "saved", t, bodyJson, p.draftBundle(), renamedOnly ? "renamed from " + p.title() : null);
            bump(id);
            return Map.of("version", p.version() + 1, "historyVersion", v);
        });
    }

    public Map<String, Object> move(long id, Long parentId, Long afterId, Long expectedVersion) {
        Map<String, Object> r = write(() -> {
            PageRow p = livePage(id);
            checkVersion(p, expectedVersion);
            List<PageRow> live = livePages();
            List<DocTree.Placement> placements;
            try {
                placements = tree(live).move(id, parentId, afterId);
            } catch (IllegalArgumentException e) {
                throw DocsException.bad(e.getMessage());
            }
            applyPlacements(placements, null);
            if (!Objects.equals(p.parentId(), parentId)) {
                String where = parentId == null ? "the top level" : livePage(parentId).title();
                addHistory(id, "moved", p.title(), p.draftJson(), p.draftBundle(), "moved under " + where);
            }
            bump(id);
            return Map.<String, Object>of("version", p.version() + 1);
        });
        return r;
    }

    public long duplicate(long id) {
        return write(() -> {
            PageRow p = livePage(id);
            List<PageRow> live = livePages();
            List<DocTree.Placement> placements = tree(live).insert(p.parentId(), id);
            int position = placements.stream().filter(x -> x.id() == -1L).findFirst().orElseThrow().position();
            applyPlacements(placements, -1L);
            String title = p.title().length() > 193 ? p.title().substring(0, 193) + " (copy)" : p.title() + " (copy)";
            String n = now();
            long copy = jdbc.sql("INSERT INTO doc_pages (parent_id, position, kind, title, draft_json, created_at, updated_at) VALUES (?,?,?,?,?,?,?) RETURNING id")
                    .params(p.parentId(), position, p.kind(), title, p.draftJson(), n, n).query(Long.class).single();
            Long newBundle = null;
            Optional<BundleRow> b = bundle(p.draftBundle());
            if (b.isPresent()) {
                newBundle = jdbc.sql("INSERT INTO doc_bundles (page_id, seq, sha256, manifest_json, total_size, created_at) VALUES (?,?,?,?,?,?) RETURNING id")
                        .params(copy, 1, b.get().sha256(), b.get().manifestJson(), b.get().totalSize(), n).query(Long.class).single();
                jdbc.sql("UPDATE doc_pages SET draft_bundle = ? WHERE id = ?").params(newBundle, copy).update();
            }
            addHistory(copy, "created", title, p.draftJson(), newBundle, "duplicated from " + p.title());
            return copy;
        });
    }

    // ---------------------------------------------------------------- trash

    /** @param children "delete" (the subtree goes to the Trash) or "lift" (children move up one level); required when there are children */
    public void delete(long id, String children) {
        boolean wasPublic = write(() -> {
            PageRow p = livePage(id);
            List<PageRow> live = livePages();
            DocTree t = tree(live);
            boolean hasChildren = !t.children(id).isEmpty();
            if (hasChildren && !"delete".equals(children) && !"lift".equals(children)) {
                throw new DocsException(HttpStatus.BAD_REQUEST, "children_choice",
                        "This page has child pages. Choose whether to delete them too or move them up one level.");
            }
            Set<Long> gone = hasChildren && "lift".equals(children) ? Set.of(id) : t.subtree(id);
            applyPlacements(hasChildren && "lift".equals(children) ? t.lift(id) : t.remove(id), null);
            String n = now();
            boolean anyPublic = false;
            for (long g : gone) {
                PageRow r = livePage(g);
                anyPublic |= r.publishedVersion() != null;
                jdbc.sql("UPDATE doc_pages SET deleted_at = ?, trash_root = ?, published_version = NULL, version = version + 1 WHERE id = ?")
                        .params(n, id, g).update();
            }
            return anyPublic;
        });
        if (wasPublic) {
            publisher.publish();
        }
    }

    public List<Map<String, Object>> trash() {
        housekeeping();
        return jdbc.sql("""
                SELECT p.id, p.title, p.kind, p.deleted_at, p.public_host,
                       (SELECT count(*) FROM doc_pages c WHERE c.trash_root = p.id AND c.id <> p.id AND c.deleted_at IS NOT NULL) AS children
                FROM doc_pages p WHERE p.deleted_at IS NOT NULL AND p.trash_root = p.id ORDER BY p.deleted_at DESC
                """).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("title", rs.getString("title"));
                    m.put("kind", rs.getString("kind"));
                    m.put("deletedAt", rs.getString("deleted_at"));
                    m.put("purgeAt", Instant.parse(rs.getString("deleted_at")).plus(TRASH_DAYS).toString());
                    m.put("children", rs.getInt("children"));
                    m.put("publicHost", rs.getString("public_host"));
                    return m;
                }).list();
    }

    /** Brings a deleted page (and what was deleted with it) back. It returns as internal: publishing again is a choice. */
    public void restoreFromTrash(long id) {
        write(() -> {
            PageRow p = page(id).orElseThrow(DocsException::notFound);
            if (p.deletedAt() == null || !Objects.equals(p.trashRoot(), id)) {
                throw DocsException.notFound();
            }
            List<PageRow> live = livePages();
            Long parent = p.parentId() != null && live.stream().anyMatch(x -> x.id() == p.parentId()) ? p.parentId() : null;
            int position = tree(live).children(parent).size();
            jdbc.sql("UPDATE doc_pages SET deleted_at = NULL, trash_root = NULL, version = version + 1 WHERE trash_root = ?").param(id).update();
            jdbc.sql("UPDATE doc_pages SET parent_id = ?, position = ? WHERE id = ?").params(parent, position, id).update();
            return null;
        });
    }

    // ---------------------------------------------------------------- history

    public List<Map<String, Object>> versions(long id) {
        PageRow p = livePage(id);
        Map<Long, Integer> seqs = new HashMap<>();
        jdbc.sql("SELECT id, seq FROM doc_bundles WHERE page_id = ?").param(id).query((rs, i) -> seqs.put(rs.getLong(1), rs.getInt(2))).list();
        return jdbc.sql("SELECT version, kind, title, bundle_id, note, created_at FROM doc_page_versions WHERE page_id = ? ORDER BY version DESC")
                .param(id).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    int v = rs.getInt("version");
                    m.put("version", v);
                    m.put("kind", rs.getString("kind"));
                    m.put("title", rs.getString("title"));
                    long b = rs.getLong("bundle_id");
                    m.put("bundleSeq", rs.wasNull() ? null : seqs.get(b));
                    m.put("note", rs.getString("note"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("live", p.publishedVersion() != null && p.publishedVersion() == v);
                    return m;
                }).list();
    }

    public Map<String, Object> version(long id, int v) {
        livePage(id);
        VersionRow r = versionRow(id, v).orElseThrow(() -> new DocsException(HttpStatus.NOT_FOUND, "not_found", "That version does not exist."));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", r.version());
        m.put("kind", r.kind());
        m.put("title", r.title());
        m.put("body", parse(r.bodyJson()));
        m.put("bundleSeq", bundle(r.bundleId()).map(BundleRow::seq).orElse(null));
        m.put("note", r.note());
        m.put("createdAt", r.createdAt());
        return m;
    }

    /** Copies version {@code v} into the draft. Does not publish. */
    public Map<String, Object> restoreVersion(long id, int v, Long expectedVersion) {
        return write(() -> {
            PageRow p = livePage(id);
            checkVersion(p, expectedVersion);
            VersionRow r = versionRow(id, v).orElseThrow(() -> new DocsException(HttpStatus.NOT_FOUND, "not_found", "That version does not exist."));
            Long bundleId = r.bundleId() != null && bundle(r.bundleId()).map(b -> b.pageId() == id).orElse(false) ? r.bundleId() : p.draftBundle();
            jdbc.sql("UPDATE doc_pages SET title = ?, draft_json = ?, draft_bundle = ? WHERE id = ?").params(r.title(), r.bodyJson(), bundleId, id).update();
            int hv = addHistory(id, "restored", r.title(), r.bodyJson(), bundleId, "restored from v" + v);
            bump(id);
            return Map.<String, Object>of("version", p.version() + 1, "historyVersion", hv);
        });
    }

    // ---------------------------------------------------------------- hosts and publishing

    public Map<String, Object> checkHost(String host, Long pageId) {
        HostRules.Result r = hostRules.check(host);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", r.host());
        if (!r.ok()) {
            m.put("ok", false);
            m.put("message", r.error());
            return m;
        }
        String routeError = routes.hostError(r.host()); // null unless Cloudflare automation is on
        if (routeError != null) {
            m.put("ok", false);
            m.put("message", routeError);
            return m;
        }
        Optional<PageRow> owner = jdbc.sql("SELECT " + PAGE_COLS + " FROM doc_pages WHERE public_host = ?").param(r.host()).query(PageRow.class).optional();
        boolean taken = owner.isPresent() && !Objects.equals(owner.get().id(), pageId);
        m.put("ok", !taken);
        m.put("message", taken ? r.host() + " is already used by “" + owner.get().title() + "”" + (owner.get().deletedAt() != null ? " (in the Trash)." : ".")
                : r.host() + " is free.");
        return m;
    }

    private String requireHost(String requested, PageRow p) {
        String candidate = requested == null || requested.isBlank() ? p.publicHost() : requested;
        if (candidate == null) {
            throw DocsException.bad("Choose a public address (hostname) first.");
        }
        Map<String, Object> c = checkHost(candidate, p.id());
        if (!Boolean.TRUE.equals(c.get("ok"))) {
            throw DocsException.bad(String.valueOf(c.get("message")));
        }
        return (String) c.get("host");
    }

    public Map<String, Object> publishCheck(long id) {
        PageRow p = livePage(id);
        Map<Long, PageRow> live = new HashMap<>();
        livePages().forEach(x -> live.put(x.id(), x));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draftVersion", nextHistory(id) - 1);
        out.put("publishedVersion", p.publishedVersion());
        out.put("publishedAt", p.publishedAt());
        out.put("publicHost", p.publicHost());
        out.put("state", state(p));
        out.put("domains", hostRules.domains());
        JsonNode body = parse(p.draftJson());
        JsonNode doc = p.rich() ? body : body.path("intro");
        DocSchema.Refs refs = doc.isObject() ? DocSchema.refs(doc) : new DocSchema.Refs(Set.of(), Set.of(), Set.of());
        List<Map<String, Object>> links = new ArrayList<>();
        for (long target : refs.pageLinks()) {
            PageRow t = live.get(target);
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("pageId", target);
            l.put("title", t == null ? null : t.title());
            boolean isPublic = t != null && t.publishedVersion() != null && t.publicHost() != null;
            l.put("public", isPublic);
            l.put("href", isPublic ? "https://" + t.publicHost() + "/" : null);
            l.put("missing", t == null);
            links.add(l);
        }
        out.put("links", links);
        List<Map<String, Object>> embeds = new ArrayList<>();
        for (long target : refs.embeds()) {
            PageRow t = live.get(target);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("pageId", target);
            e.put("title", t == null ? null : t.title());
            Optional<BundleRow> b = t == null || !t.kind().equals("webapp") ? Optional.empty() : embedBundle(t);
            e.put("bundleSeq", b.map(BundleRow::seq).orElse(null));
            e.put("size", b.map(BundleRow::totalSize).orElse(null));
            e.put("source", b.isEmpty() ? "missing" : t.publishedVersion() != null ? "published" : "draft");
            embeds.add(e);
        }
        out.put("embeds", embeds);
        List<Map<String, Object>> files = new ArrayList<>();
        long bytes = 0;
        for (String sha : refs.assets()) {
            Optional<Map<String, Object>> a = asset(sha);
            if (a.isPresent()) {
                files.add(a.get());
                bytes += ((Number) a.get().get("size")).longValue();
            }
        }
        if (!p.rich()) {
            Optional<BundleRow> b = bundle(p.draftBundle());
            out.put("bundle", b.map(x -> bundleView(x, p)).orElse(null));
            if (b.isPresent()) {
                bytes += b.get().totalSize();
            }
        }
        out.put("files", files);
        out.put("fileBytes", bytes);
        List<String> problems = new ArrayList<>();
        if (!p.rich() && p.draftBundle() == null) {
            problems.add("Upload the app (index.html or a .zip) before publishing.");
        }
        out.put("problems", problems);
        Optional<VersionRow> liveVersion = p.publishedVersion() == null ? Optional.empty() : versionRow(id, p.publishedVersion());
        out.put("liveBody", liveVersion.map(v -> parse(v.bodyJson())).orElse(null));
        out.put("liveTitle", liveVersion.map(VersionRow::title).orElse(null));
        return out;
    }

    /** The bundle an embed shows: the embedded page's published bundle, or its current draft bundle if it was never published. */
    Optional<BundleRow> embedBundle(PageRow webapp) {
        if (webapp.publishedVersion() != null) {
            Optional<BundleRow> b = versionRow(webapp.id(), webapp.publishedVersion()).flatMap(v -> bundle(v.bundleId()));
            if (b.isPresent()) {
                return b;
            }
        }
        return bundle(webapp.draftBundle());
    }

    public Map<String, Object> publish(long id, String requestedHost, Long expectedVersion, String ip, String ua) {
        Map<String, Object> r = write(() -> {
            PageRow p = livePage(id);
            checkVersion(p, expectedVersion);
            if (!p.rich() && p.draftBundle() == null) {
                throw DocsException.bad("Upload the app (index.html or a .zip) before publishing.");
            }
            String host = requireHost(requestedHost, p);
            String note = Objects.equals(host, p.publicHost()) ? null : "hostname " + host;
            int v = addHistory(id, "published", p.title(), p.draftJson(), p.draftBundle(), note);
            jdbc.sql("UPDATE doc_pages SET public_host = ?, published_version = ?, published_at = ? WHERE id = ?").params(host, v, now(), id).update();
            bump(id);
            return Map.<String, Object>of("version", p.version() + 1, "publishedVersion", v, "publicHost", host);
        });
        audit.record("publish", "page " + id + " " + r.get("publicHost"), "ok", "v" + r.get("publishedVersion"), ip, ua);
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("live", publisher.publish());
        out.put("route", routes.routeInfo((String) r.get("publicHost")));
        return out;
    }

    public Map<String, Object> unpublish(long id, Long expectedVersion) {
        Map<String, Object> r = write(() -> {
            PageRow p = livePage(id);
            checkVersion(p, expectedVersion);
            if (p.publishedVersion() == null) {
                throw DocsException.bad("This page is not public.");
            }
            jdbc.sql("UPDATE doc_pages SET published_version = NULL WHERE id = ?").param(id).update();
            addHistory(id, "unpublished", p.title(), p.draftJson(), p.draftBundle(), p.publicHost() + " taken offline");
            bump(id);
            return Map.<String, Object>of("version", p.version() + 1);
        });
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("live", publisher.publish());
        return out;
    }

    /** "Save settings only": sets or clears the hostname. A live page moves to the new hostname at once (the old link dies). */
    public Map<String, Object> setHost(long id, String host, Long expectedVersion) {
        boolean[] live = {false};
        Map<String, Object> r = write(() -> {
            PageRow p = livePage(id);
            checkVersion(p, expectedVersion);
            String h = host == null || host.isBlank() ? null : requireHost(host, p);
            if (Objects.equals(h, p.publicHost())) {
                return Map.<String, Object>of("version", p.version(), "publicHost", h == null ? "" : h);
            }
            live[0] = p.publishedVersion() != null;
            jdbc.sql("UPDATE doc_pages SET public_host = ?, published_version = CASE WHEN ? IS NULL THEN NULL ELSE published_version END WHERE id = ?")
                    .params(h, h, id).update();
            addHistory(id, "host", p.title(), p.draftJson(), p.draftBundle(), h == null ? "hostname removed" : "hostname " + h);
            bump(id);
            return Map.<String, Object>of("version", p.version() + 1, "publicHost", h == null ? "" : h);
        });
        if (live[0]) {
            publisher.publish();
        }
        return r;
    }

    // ---------------------------------------------------------------- assets

    public Map<String, Object> addAsset(java.io.InputStream in, String originalName) throws IOException {
        long max = (long) props.maxAssetMb() * 1024 * 1024;
        BlobStore.Stored s = blobs.put(in, max);
        byte[] head;
        try (java.io.InputStream f = Files.newInputStream(blobs.path(s.sha256()))) {
            head = f.readNBytes(4096);
        }
        String name = cleanName(originalName);
        String mime = MimeSniffer.sniff(head, name);
        if (mime == null) {
            throw DocsException.bad("This file type is not allowed. Use an image (png, jpg, webp, gif, svg), a PDF, a ZIP or a text file.");
        }
        jdbc.sql("INSERT INTO doc_assets (sha256, mime, size, original_name, created_at) VALUES (?,?,?,?,?) ON CONFLICT (sha256) DO NOTHING")
                .params(s.sha256(), mime, s.size(), name, now()).update();
        return asset(s.sha256()).orElseThrow();
    }

    public Optional<Map<String, Object>> asset(String sha) {
        return jdbc.sql("SELECT sha256, mime, size, original_name FROM doc_assets WHERE sha256 = ?").param(sha).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sha256", rs.getString(1));
            m.put("mime", rs.getString(2));
            m.put("size", rs.getLong(3));
            m.put("name", rs.getString(4));
            return m;
        }).optional();
    }

    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll(".*[/\\\\]", "").replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (n.isEmpty()) {
            n = "file";
        }
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    // ---------------------------------------------------------------- bundles

    public Map<String, Object> uploadBundle(long id, byte[] data, String fileName, Long expectedVersion) throws IOException {
        long max = (long) props.maxBundleMb() * 1024 * 1024;
        if (data.length > max) {
            throw new BlobStore.TooLargeException("The upload is larger than " + props.maxBundleMb() + " MB.");
        }
        PageRow before = livePage(id);
        if (before.rich()) {
            throw DocsException.bad("Only webapp pages take an app upload.");
        }
        checkVersion(before, expectedVersion);
        List<BundleReader.Entry> entries;
        if (BundleReader.looksLikeZip(data)) {
            entries = BundleReader.zip(data);
        } else if (MimeSniffer.isUtf8Text(java.util.Arrays.copyOf(data, Math.min(data.length, 4096)))) {
            entries = BundleReader.singleHtml(data);
        } else {
            throw DocsException.bad("Upload an index.html file or a .zip.");
        }
        ArrayNode manifest = json.createArrayNode();
        long total = 0;
        for (BundleReader.Entry e : entries.stream().sorted(Comparator.comparing(BundleReader.Entry::path)).toList()) {
            BlobStore.Stored s = blobs.put(e.bytes());
            manifest.addObject().put("path", e.path()).put("sha256", s.sha256()).put("size", s.size()).put("mime", e.mime());
            total += s.size();
        }
        String manifestJson = toJson(manifest);
        String sha = BlobStore.sha256Hex(manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long size = total;
        return write(() -> {
            PageRow p = livePage(id);
            checkVersion(p, expectedVersion);
            int seq = jdbc.sql("SELECT COALESCE(MAX(seq), 0) + 1 FROM doc_bundles WHERE page_id = ?").param(id).query(Integer.class).single();
            long bundleId = jdbc.sql("INSERT INTO doc_bundles (page_id, seq, sha256, manifest_json, total_size, created_at) VALUES (?,?,?,?,?,?) RETURNING id")
                    .params(id, seq, sha, manifestJson, size, now()).query(Long.class).single();
            jdbc.sql("UPDATE doc_pages SET draft_bundle = ? WHERE id = ?").params(bundleId, id).update();
            addHistory(id, "bundle", p.title(), p.draftJson(), bundleId, "uploaded b" + seq + " (" + cleanName(fileName) + ")");
            bump(id);
            return Map.<String, Object>of("version", p.version() + 1, "seq", seq, "sha256", sha, "files", manifest.size(), "size", size);
        });
    }

    public List<Map<String, Object>> bundles(long id) {
        PageRow p = livePage(id);
        return jdbc.sql("SELECT id, page_id, seq, sha256, manifest_json, total_size, created_at FROM doc_bundles WHERE page_id = ? ORDER BY seq DESC")
                .param(id).query(BundleRow.class).list().stream().map(b -> bundleView(b, p)).toList();
    }

    Map<String, Object> bundleView(BundleRow b, PageRow p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", b.seq());
        m.put("sha256", b.sha256());
        m.put("size", b.totalSize());
        m.put("createdAt", b.createdAt());
        m.put("draft", Objects.equals(p.draftBundle(), b.id()));
        Long liveBundle = p.publishedVersion() == null ? null : versionRow(p.id(), p.publishedVersion()).map(VersionRow::bundleId).orElse(null);
        m.put("live", Objects.equals(liveBundle, b.id()));
        List<Map<String, Object>> files = new ArrayList<>();
        for (JsonNode f : parse(b.manifestJson())) {
            files.add(Map.of("path", f.path("path").asText(), "size", f.path("size").asLong()));
        }
        m.put("files", files);
        return m;
    }

    /** Rollback: an older bundle becomes the draft again. */
    public Map<String, Object> useBundle(long id, int seq, Long expectedVersion) {
        return write(() -> {
            PageRow p = livePage(id);
            checkVersion(p, expectedVersion);
            long bundleId = jdbc.sql("SELECT id FROM doc_bundles WHERE page_id = ? AND seq = ?").params(id, seq).query(Long.class).optional()
                    .orElseThrow(() -> new DocsException(HttpStatus.NOT_FOUND, "not_found", "No such bundle."));
            jdbc.sql("UPDATE doc_pages SET draft_bundle = ? WHERE id = ?").params(bundleId, id).update();
            addHistory(id, "bundle", p.title(), p.draftJson(), bundleId, "switched to b" + seq);
            bump(id);
            return Map.<String, Object>of("version", p.version() + 1);
        });
    }

    /** The bundle a preview token may be minted for: the page's draft bundle, or {@code seq} when given. */
    public BundleRow previewBundle(long id, Integer seq) {
        PageRow p = livePage(id);
        if (seq != null) {
            return jdbc.sql("SELECT id, page_id, seq, sha256, manifest_json, total_size, created_at FROM doc_bundles WHERE page_id = ? AND seq = ?")
                    .params(id, seq).query(BundleRow.class).optional().orElseThrow(() -> new DocsException(HttpStatus.NOT_FOUND, "not_found", "No such bundle."));
        }
        if (p.rich()) {
            throw DocsException.bad("Only webapp pages have an app.");
        }
        return embedBundleOrDraft(p).orElseThrow(() -> DocsException.bad("Upload the app first."));
    }

    private Optional<BundleRow> embedBundleOrDraft(PageRow p) {
        Optional<BundleRow> d = bundle(p.draftBundle());
        return d.isPresent() ? d : embedBundle(p);
    }

    /** The settings that govern a bundle's CSP: those of the page that owns it. */
    public JsonNode settingsOfBundle(BundleRow b) {
        return page(b.pageId()).map(p -> parse(p.draftJson())).orElse(WebappSettings.defaults());
    }

    // ---------------------------------------------------------------- housekeeping

    private volatile Instant lastHousekeeping = Instant.EPOCH;

    /** At most once a day, on a Docs request: empty the Trash of pages older than 30 days and drop unused blobs. */
    void housekeeping() {
        Instant now = clock.instant();
        if (Duration.between(lastHousekeeping, now).compareTo(Duration.ofDays(1)) < 0) {
            return;
        }
        lastHousekeeping = now;
        try {
            purgeTrash(now.minus(TRASH_DAYS));
            collectBlobs(now.minus(BLOB_GRACE));
        } catch (RuntimeException | IOException e) {
            log.warn("Docs housekeeping failed: {}", e.toString());
        }
    }

    void purgeTrash(Instant before) {
        write(() -> {
            List<Long> roots = jdbc.sql("SELECT id FROM doc_pages WHERE deleted_at IS NOT NULL AND trash_root = id AND deleted_at < ?")
                    .param(before.toString()).query(Long.class).list();
            for (long root : roots) {
                List<Long> ids = jdbc.sql("SELECT id FROM doc_pages WHERE trash_root = ?").param(root).query(Long.class).list();
                for (long id : ids) {
                    // Children that were lifted out stay; anything still pointing at a purged page moves to the top level.
                    jdbc.sql("UPDATE doc_pages SET parent_id = NULL WHERE parent_id = ? AND trash_root IS NOT ?").params(id, root).update();
                    jdbc.sql("UPDATE doc_pages SET draft_bundle = NULL, parent_id = NULL, public_host = NULL WHERE id = ?").param(id).update();
                }
                for (long id : ids) {
                    jdbc.sql("DELETE FROM doc_page_versions WHERE page_id = ?").param(id).update();
                    jdbc.sql("DELETE FROM doc_bundles WHERE page_id = ?").param(id).update();
                    jdbc.sql("DELETE FROM doc_pages WHERE id = ?").param(id).update();
                }
                log.info("Purged {} page(s) from the Trash", ids.size());
            }
            return null;
        });
    }

    /** Deletes assets no draft, version or bundle refers to, once they are older than the grace period. */
    void collectBlobs(Instant before) throws IOException {
        Set<String> used = new HashSet<>();
        for (String body : jdbc.sql("SELECT draft_json FROM doc_pages UNION ALL SELECT body_json FROM doc_page_versions").query(String.class).list()) {
            collectShas(parse(body), used);
        }
        for (String manifest : jdbc.sql("SELECT manifest_json FROM doc_bundles").query(String.class).list()) {
            for (JsonNode f : parse(manifest)) {
                used.add(f.path("sha256").asText());
            }
        }
        List<String> unused = jdbc.sql("SELECT sha256 FROM doc_assets WHERE created_at < ?").param(before.toString()).query(String.class).list()
                .stream().filter(s -> !used.contains(s)).toList();
        for (String sha : unused) {
            jdbc.sql("DELETE FROM doc_assets WHERE sha256 = ?").param(sha).update();
            Files.deleteIfExists(blobs.path(sha));
        }
        // Bundle files whose bundles are all gone (pages purged from the Trash).
        Path root = blobs.root();
        if (Files.isDirectory(root)) {
            Set<String> assets = new HashSet<>(jdbc.sql("SELECT sha256 FROM doc_assets").query(String.class).list());
            try (var dirs = Files.list(root)) {
                for (Path d : dirs.filter(Files::isDirectory).toList()) {
                    try (var fs = Files.list(d)) {
                        for (Path f : fs.toList()) {
                            String name = f.getFileName().toString();
                            if (BlobStore.isSha(name) && !used.contains(name) && !assets.contains(name)
                                    && Files.getLastModifiedTime(f).toInstant().isBefore(before)) {
                                Files.deleteIfExists(f);
                            }
                        }
                    }
                }
            }
        }
        if (!unused.isEmpty()) {
            log.info("Removed {} unused file(s)", unused.size());
        }
    }

    static void collectShas(JsonNode n, Set<String> out) {
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> {
                if (Set.of("sha", "lightSvg", "darkSvg").contains(e.getKey()) && e.getValue().isTextual()) {
                    out.add(e.getValue().asText());
                } else {
                    collectShas(e.getValue(), out);
                }
            });
        } else if (n.isArray()) {
            n.forEach(c -> collectShas(c, out));
        }
    }
}
