package com.homelabhub.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homelabhub.docs.github.GitHubProperties;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Turns the published version of each page into the JSON the public viewer reads (the same shape for the local snapshot and for the
 * GitHub Pages site). The only difference is where a link to another page points:
 * <ul>
 *   <li>local copy: the other page's local address, else its GitHub address, else plain text;</li>
 *   <li>GitHub copy: the other page's GitHub address, else its local address, else plain text.</li>
 * </ul>
 */
@Service
public class PageRenderer {

    public static final int SCHEMA_VERSION = 1;

    public enum Mode { LOCAL, GITHUB }

    /** One page ready to publish. {@code blobs} are the files (assets and bundle files) it needs. */
    public record Rendered(long id, String title, String host, String slug, ObjectNode page, Set<String> blobs) {}

    private record Live(long id, String kind, String title, String host, String slug, String body, Long bundleId, String publishedAt) {}

    private record Bundle(String sha, JsonNode manifest, JsonNode settings) {}

    private record Target(String host, String slug) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final GitHubProperties github;

    public PageRenderer(JdbcClient jdbc, ObjectMapper json, GitHubProperties github) {
        this.jdbc = jdbc;
        this.json = json;
        this.github = github;
    }

    /** The GitHub address of a folder on the site, or null when GitHub publishing is off. */
    public String githubUrl(String slug) {
        return slug == null || !github.enabled() ? null : github.effectiveSiteUrl() + slug + "/";
    }

    /** Every page that should be public in {@code mode}: LOCAL = has a local host, GITHUB = has a folder name. */
    public List<Rendered> render(Mode mode) {
        String col = mode == Mode.LOCAL ? "p.public_host" : "p.github_slug";
        List<Live> live = jdbc.sql("""
                SELECT p.id, p.kind, v.title, p.public_host AS host, p.github_slug AS slug, v.body_json AS body, v.bundle_id, p.published_at
                FROM doc_pages p JOIN doc_page_versions v ON v.page_id = p.id AND v.version = p.published_version
                WHERE p.deleted_at IS NULL AND %s IS NOT NULL AND p.published_version IS NOT NULL
                ORDER BY p.id
                """.formatted(col)).query(Live.class).list();
        Map<Long, Target> targets = new HashMap<>();
        jdbc.sql("SELECT id, public_host, github_slug FROM doc_pages WHERE deleted_at IS NULL AND published_version IS NOT NULL")
                .query((rs, i) -> targets.put(rs.getLong(1), new Target(rs.getString(2), rs.getString(3)))).list();
        List<Rendered> out = new java.util.ArrayList<>();
        for (Live l : live) {
            Set<String> needed = new HashSet<>();
            ObjectNode page = json.createObjectNode();
            page.put("schemaVersion", SCHEMA_VERSION);
            page.put("kind", l.kind());
            page.put("title", l.title());
            page.put("updatedAt", l.publishedAt());
            ObjectNode embeds = json.createObjectNode();
            ObjectNode assets = json.createObjectNode();
            JsonNode body = read(l.body());
            if (l.kind().equals("rich")) {
                page.set("body", resolve(body, mode, targets, embeds, assets, needed));
            } else {
                ObjectNode settings = ((ObjectNode) body.deepCopy());
                JsonNode intro = settings.remove("intro");
                page.set("settings", settings);
                page.set("intro", intro == null || intro.isNull() ? null : resolve(intro, mode, targets, embeds, assets, needed));
                Optional<Bundle> b = bundleById(l.bundleId());
                if (b.isEmpty()) {
                    continue; // a webapp without its app cannot be shown
                }
                page.set("bundle", bundleJson(b.get(), needed));
            }
            page.set("embeds", embeds);
            page.set("assets", assets);
            out.add(new Rendered(l.id(), l.title(), l.host(), l.slug(), page, needed));
        }
        return out;
    }

    private String href(Mode mode, Target t) {
        if (t == null) {
            return null;
        }
        String local = t.host() == null ? null : "https://" + t.host() + "/";
        String gh = githubUrl(t.slug());
        return mode == Mode.LOCAL ? (local != null ? local : gh) : (gh != null ? gh : local);
    }

    /** Copies a rich body for the public: links resolved (D6), embeds resolved to bundles, other page ids removed. */
    private JsonNode resolve(JsonNode node, Mode mode, Map<Long, Target> targets, ObjectNode embeds, ObjectNode assets, Set<String> needed) {
        if (node.isArray()) {
            ArrayNode out = json.createArrayNode();
            for (JsonNode c : node) {
                JsonNode r = resolve(c, mode, targets, embeds, assets, needed);
                if (r != null) {
                    out.add(r);
                }
            }
            return out;
        }
        if (!node.isObject()) {
            return node.deepCopy();
        }
        String type = node.path("type").asText();
        JsonNode a = node.path("attrs");
        switch (type) {
            case "pageLink" -> {
                ObjectNode out = json.createObjectNode().put("type", "pageLink");
                ObjectNode attrs = out.putObject("attrs").put("label", a.path("label").asText());
                String href = href(mode, targets.get(a.path("pageId").asLong()));
                if (href != null) {
                    attrs.put("href", href);
                }
                return out;
            }
            case "webappEmbed" -> {
                Optional<Bundle> b = embedBundle(a.path("pageId").asLong());
                if (b.isEmpty()) {
                    return null;
                }
                if (!embeds.has(b.get().sha())) {
                    ObjectNode e = json.createObjectNode();
                    e.set("settings", publicSettings(b.get().settings()));
                    e.set("bundle", bundleJson(b.get(), needed));
                    embeds.set(b.get().sha(), e);
                }
                ObjectNode out = json.createObjectNode().put("type", "webappEmbed");
                ObjectNode attrs = out.putObject("attrs").put("bundle", b.get().sha());
                if (a.has("height")) {
                    attrs.put("height", a.get("height").asInt());
                }
                return out;
            }
            default -> {
                ObjectNode out = json.createObjectNode();
                node.fields().forEachRemaining(f -> {
                    if (f.getKey().equals("content")) {
                        out.set("content", resolve(f.getValue(), mode, targets, embeds, assets, needed));
                    } else {
                        out.set(f.getKey(), f.getValue().deepCopy());
                    }
                });
                for (String key : List.of("sha", "lightSvg", "darkSvg")) {
                    String sha = a.path(key).asText(null);
                    if (sha != null && BlobStore.isSha(sha)) {
                        addAsset(sha, assets, needed);
                    }
                }
                return out;
            }
        }
    }

    private void addAsset(String sha, ObjectNode assets, Set<String> needed) {
        if (assets.has(sha)) {
            return;
        }
        jdbc.sql("SELECT mime, size, original_name FROM doc_assets WHERE sha256 = ?").param(sha).query((rs, i) -> {
            assets.putObject(sha).put("mime", rs.getString(1)).put("size", rs.getLong(2)).put("name", rs.getString(3));
            needed.add(sha);
            return 0;
        }).list();
    }

    private ObjectNode bundleJson(Bundle b, Set<String> needed) {
        ObjectNode out = json.createObjectNode().put("sha", b.sha());
        ObjectNode files = out.putObject("files");
        for (JsonNode f : b.manifest()) {
            files.putObject(f.path("path").asText()).put("sha", f.path("sha256").asText()).put("mime", f.path("mime").asText())
                    .put("size", f.path("size").asLong());
            needed.add(f.path("sha256").asText());
        }
        return out;
    }

    /** An embed only carries what the frame needs; the intro of the embedded page is not part of the embedding page. */
    private ObjectNode publicSettings(JsonNode settings) {
        ObjectNode s = settings.deepCopy();
        s.remove("intro");
        return s;
    }

    private Optional<Bundle> bundleById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT b.sha256, b.manifest_json, p.draft_json FROM doc_bundles b JOIN doc_pages p ON p.id = b.page_id WHERE b.id = ?")
                .param(id).query((rs, i) -> new Bundle(rs.getString(1), read(rs.getString(2)), read(rs.getString(3)))).optional();
    }

    /** The embedded page's published bundle (with its published settings), else its draft bundle; never from the Trash. */
    private Optional<Bundle> embedBundle(long pageId) {
        Optional<Bundle> published = jdbc.sql("""
                SELECT b.sha256, b.manifest_json, v.body_json FROM doc_pages p
                JOIN doc_page_versions v ON v.page_id = p.id AND v.version = p.published_version
                JOIN doc_bundles b ON b.id = v.bundle_id
                WHERE p.id = ? AND p.kind = 'webapp' AND p.deleted_at IS NULL
                """).param(pageId).query((rs, i) -> new Bundle(rs.getString(1), read(rs.getString(2)), read(rs.getString(3)))).optional();
        if (published.isPresent()) {
            return published;
        }
        return jdbc.sql("""
                SELECT b.sha256, b.manifest_json, p.draft_json FROM doc_pages p JOIN doc_bundles b ON b.id = p.draft_bundle
                WHERE p.id = ? AND p.kind = 'webapp' AND p.deleted_at IS NULL
                """).param(pageId).query((rs, i) -> new Bundle(rs.getString(1), read(rs.getString(2)), read(rs.getString(3)))).optional();
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException("corrupt JSON in the database", e);
        }
    }
}
