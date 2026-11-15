package com.homelabhub.docs.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homelabhub.docs.BlobStore;
import com.homelabhub.docs.DocsProperties;
import com.homelabhub.docs.HubEmbed;
import com.homelabhub.docs.PageRenderer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds the whole Pages site from the database, as {@code path → content}. The repository always equals "everything that is
 * marked for GitHub right now", so a page that is no longer marked simply is not in the result.
 *
 * <pre>
 * .nojekyll
 * viewer/assets/...                      the Docs viewer's files, one copy
 * &lt;slug&gt;/index.html, page.json          one page
 * &lt;slug&gt;/a/&lt;sha&gt;.&lt;ext&gt;                files the page lists
 * &lt;slug&gt;/app/&lt;bundle-sha&gt;/...           the page's own app and embedded apps, with the theme script
 * portfolio/...                          the portfolio app, avatar, and portfolio.json (only when it is deployed)
 * </pre>
 *
 * The two apps are read from the classpath folders {@code static-export/viewer} and {@code static-export/portfolio}, each with a
 * {@code files.txt} (a jar cannot be listed).
 */
@Service
public class StaticSite implements SiteSource {

    /** Always at the root of a site this app wrote. It tells Pages to serve files as they are, and marks the branch as ours. */
    public static final String MARKER = ".nojekyll";

    public static final String DEFAULT_ROOT = "static-export";
    private static final Pattern HEAD = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>.*?</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final PageRenderer renderer;
    private final BlobStore blobs;
    private final ObjectMapper json;
    private final Path portfolioFile;
    private final String root;

    @Autowired
    public StaticSite(PageRenderer renderer, BlobStore blobs, DocsProperties props, ObjectMapper json) {
        this(renderer, blobs, portfolioFile(props), json, DEFAULT_ROOT);
    }

    StaticSite(PageRenderer renderer, BlobStore blobs, Path portfolioFile, ObjectMapper json, String root) {
        this.renderer = renderer;
        this.blobs = blobs;
        this.portfolioFile = portfolioFile;
        this.json = json;
        this.root = root;
    }

    /** Where a deployed portfolio's data is kept. */
    public static Path portfolioFile(DocsProperties props) {
        return Path.of(props.dataDir(), "github", "portfolio.json");
    }

    @Override
    public Map<String, SiteContent> build() throws IOException {
        Map<String, SiteContent> site = new TreeMap<>();
        site.put(MARKER, SiteContent.of(new byte[0]));
        Map<String, byte[]> viewer = bundle("viewer");
        String viewerIndex = text(viewer.get("index.html"), "viewer/index.html");
        viewer.forEach((path, bytes) -> {
            if (!path.equals("index.html")) {
                site.put("viewer/" + path, SiteContent.of(bytes));
            }
        });
        for (PageRenderer.Rendered r : renderer.render(PageRenderer.Mode.GITHUB)) {
            addPage(site, r, viewerIndex);
        }
        if (Files.isRegularFile(portfolioFile)) {
            portfolioFiles(SiteContent.of(portfolioFile)).forEach((path, c) -> site.put("portfolio/" + path, c));
        }
        return site;
    }

    /** The files of the portfolio site, relative to its folder (also what the zip download holds). */
    public Map<String, SiteContent> portfolioFiles(SiteContent portfolioJson) throws IOException {
        Map<String, SiteContent> out = new TreeMap<>();
        Map<String, byte[]> bundle = bundle("portfolio");
        bundle.forEach((path, bytes) -> {
            if (!path.equals("index.html")) {
                out.put(path, SiteContent.of(bytes));
            }
        });
        out.put("index.html", SiteContent.of(portfolioShell(text(bundle.get("index.html"), "portfolio/index.html")).getBytes(StandardCharsets.UTF_8)));
        out.put("portfolio.json", portfolioJson);
        return out;
    }

    private void addPage(Map<String, SiteContent> site, PageRenderer.Rendered r, String viewerIndex) throws IOException {
        String dir = r.slug() + "/";
        ObjectNode page = r.page();
        JsonNode assets = page.path("assets");
        Iterator<Map.Entry<String, JsonNode>> it = assets.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> a = it.next();
            String sha = a.getKey();
            String file = "a/" + sha + "." + extension(a.getValue().path("mime").asText(), a.getValue().path("name").asText());
            ((ObjectNode) a.getValue()).put("file", file);
            site.put(dir + file, blobContent(sha));
        }
        addBundle(site, dir, page.path("bundle"));
        for (JsonNode e : page.path("embeds")) {
            addBundle(site, dir, e.path("bundle"));
        }
        site.put(dir + "page.json", SiteContent.of(json.writeValueAsBytes(page)));
        site.put(dir + "index.html", SiteContent.of(docShell(viewerIndex, r.title()).getBytes(StandardCharsets.UTF_8)));
    }

    private void addBundle(Map<String, SiteContent> site, String dir, JsonNode bundle) throws IOException {
        String sha = bundle.path("sha").asText(null);
        if (sha == null) {
            return;
        }
        String base = dir + "app/" + sha + "/";
        Iterator<Map.Entry<String, JsonNode>> files = bundle.path("files").fields();
        while (files.hasNext()) {
            Map.Entry<String, JsonNode> f = files.next();
            if (!safePath(f.getKey())) {
                continue;
            }
            site.put(base + f.getKey(), blobContent(f.getValue().path("sha").asText()));
        }
        site.putIfAbsent(base + HubEmbed.PATH, SiteContent.of(HubEmbed.SCRIPT.getBytes(StandardCharsets.UTF_8)));
    }

    private SiteContent blobContent(String sha) throws IOException {
        if (!blobs.exists(sha)) {
            throw new IOException("a file the page needs is missing from the store: " + sha);
        }
        return SiteContent.of(blobs.path(sha));
    }

    // ---------------------------------------------------------------- shells

    /** The viewer's index.html made for one page (see the contract: asset paths, static-data, noindex and the title). */
    static String docShell(String viewerIndex, String title) {
        String html = viewerIndex.replace("./assets/", "../viewer/assets/");
        String safeTitle = escape(title);
        String titleTag = "<title>" + safeTitle + "</title>";
        boolean hasTitle = TITLE.matcher(html).find();
        if (hasTitle) {
            html = TITLE.matcher(html).replaceFirst(Matcher.quoteReplacement(titleTag));
        }
        String tags = "<meta name=\"static-data\" content=\"page.json\">\n<meta name=\"robots\" content=\"noindex\">\n" + (hasTitle ? "" : titleTag + "\n");
        return afterHead(html, tags);
    }

    /** The portfolio's index.html with only the static-data tag added. */
    static String portfolioShell(String index) {
        return afterHead(index, "<meta name=\"static-data\" content=\"portfolio.json\">\n");
    }

    private static String afterHead(String html, String insert) {
        Matcher m = HEAD.matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("The bundled index.html has no <head> tag.");
        }
        return html.substring(0, m.end()) + "\n" + insert + html.substring(m.end());
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ---------------------------------------------------------------- helpers

    static String extension(String mime, String name) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String byMime = switch (m) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/svg+xml" -> "svg";
            case "application/pdf" -> "pdf";
            case "application/zip" -> "zip";
            case "text/plain" -> "txt";
            case "text/csv" -> "csv";
            case "text/markdown" -> "md";
            case "application/json" -> "json";
            default -> null;
        };
        if (byMime != null) {
            return byMime;
        }
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.matches("[a-z0-9]{1,8}")) {
                return ext;
            }
        }
        return "bin";
    }

    static boolean safePath(String p) {
        if (p == null || p.isEmpty() || p.startsWith("/") || p.contains("\\") || p.contains("\0")) {
            return false;
        }
        for (String part : p.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String text(byte[] b, String what) {
        if (b == null) {
            throw new IllegalStateException("The bundled " + what + " is missing.");
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Every file of a packed bundle, by relative path. */
    private Map<String, byte[]> bundle(String name) throws IOException {
        String base = root + "/" + name + "/";
        ClassLoader cl = StaticSite.class.getClassLoader();
        List<String> paths = new ArrayList<>();
        try (InputStream list = cl.getResourceAsStream(base + "files.txt")) {
            if (list == null) {
                throw new IllegalStateException("The " + name + " files are not packed into this build (" + base + "files.txt is missing).");
            }
            for (String line : new String(list.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String p = line.strip().replaceFirst("^\\./", "");
                if (!p.isEmpty() && !p.equals("files.txt") && safePath(p)) {
                    paths.add(p);
                }
            }
        }
        Map<String, byte[]> out = new TreeMap<>();
        for (String p : paths) {
            try (InputStream in = cl.getResourceAsStream(base + p)) {
                if (in == null) {
                    throw new IllegalStateException("The packed " + name + " file " + p + " is missing.");
                }
                out.put(p, in.readAllBytes());
            }
        }
        return out;
    }
}
