package com.homelabhub.docs.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory GitHub REST API (git objects, one branch, the Pages build) on a local port. No real API call is ever made in tests. */
public final class FakeGitHub implements AutoCloseable {

    public static final String TOKEN = "ghp_fake-token-0123456789", OWNER = "octo", REPO = "showcase", BRANCH = "gh-pages";
    public static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/repos/" + OWNER + "/" + REPO;

    private final HttpServer server;
    /** "METHOD path" of every request, in order. */
    public final List<String> requests = new CopyOnWriteArrayList<>();
    public final List<String> badAuth = new CopyOnWriteArrayList<>();
    public final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    public final Map<String, Map<String, String>> trees = new ConcurrentHashMap<>();
    public final Map<String, String> commitTree = new ConcurrentHashMap<>();
    public final List<Integer> commitParents = new CopyOnWriteArrayList<>();
    public volatile String branchCommit;
    public volatile boolean forced;
    /** Pages build statuses handed out one per poll (the last one repeats); null = the Pages API answers {@link #pagesCode}. */
    public volatile List<String> pagesStatuses;
    public volatile int pagesCode = 404;
    /** Requests whose "METHOD path" starts with this prefix fail with failStatus and failBody. */
    public volatile String failPrefix;
    public volatile int failStatus = 500;
    public volatile String failBody = "{\"message\":\"boom\"}";
    private int pollCount = 0;
    private int ids = 0;

    public FakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public GitHubProperties props() {
        return new GitHubProperties(true, OWNER + "/" + REPO, BRANCH, TOKEN, null, false, url());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public synchronized void pages(String... statuses) {
        pollCount = 0;
        pagesStatuses = List.of(statuses);
    }

    public long count(String prefix) {
        return requests.stream().filter(r -> r.startsWith(prefix)).count();
    }

    public long blobUploads() {
        return count("POST " + BASE + "/git/blobs");
    }

    public long commits() {
        return commitParents.size();
    }

    /** The files of the branch's current tree. */
    public Map<String, byte[]> files() {
        Map<String, byte[]> out = new TreeMap<>();
        String c = branchCommit;
        if (c != null) {
            trees.get(commitTree.get(c)).forEach((path, sha) -> out.put(path, blobs.get(sha)));
        }
        return out;
    }

    public String text(String path) {
        byte[] b = files().get(path);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    public static String gitSha(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String key = ex.getRequestMethod() + " " + path;
        requests.add(key);
        if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            badAuth.add(key);
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String fail = failPrefix;
        if (fail != null && key.startsWith(fail)) {
            send(ex, failStatus, failBody);
            return;
        }
        JsonNode in = body.isBlank() ? M.nullNode() : M.readTree(body);
        String q = ex.getRequestURI().getRawQuery();
        String m = ex.getRequestMethod();
        if (m.equals("GET") && path.equals(BASE + "/git/ref/heads/" + BRANCH)) {
            if (branchCommit == null) {
                send(ex, 404, "{\"message\":\"Not Found\"}");
            } else {
                send(ex, 200, "{\"object\":{\"sha\":\"" + branchCommit + "\"}}");
            }
        } else if (m.equals("GET") && path.startsWith(BASE + "/git/commits/")) {
            String tree = commitTree.get(path.substring((BASE + "/git/commits/").length()));
            send(ex, tree == null ? 404 : 200, tree == null ? "{\"message\":\"Not Found\"}" : "{\"tree\":{\"sha\":\"" + tree + "\"}}");
        } else if (m.equals("GET") && path.startsWith(BASE + "/git/trees/") && "recursive=1".equals(q)) {
            Map<String, String> t = trees.get(path.substring((BASE + "/git/trees/").length()));
            List<Map<String, String>> list = new ArrayList<>();
            t.forEach((p, sha) -> list.add(Map.of("path", p, "mode", "100644", "type", "blob", "sha", sha)));
            send(ex, 200, M.writeValueAsString(Map.of("sha", "x", "tree", list, "truncated", false)));
        } else if (m.equals("POST") && path.equals(BASE + "/git/blobs")) {
            if (!in.path("encoding").asText().equals("base64")) {
                send(ex, 422, "{\"message\":\"bad encoding\"}");
                return;
            }
            byte[] bytes = Base64.getDecoder().decode(in.path("content").asText());
            String sha = gitSha(bytes);
            blobs.put(sha, bytes);
            send(ex, 201, "{\"sha\":\"" + sha + "\"}");
        } else if (m.equals("POST") && path.equals(BASE + "/git/trees")) {
            if (in.has("base_tree")) {
                send(ex, 422, "{\"message\":\"base_tree is not allowed here\"}");
                return;
            }
            Map<String, String> t = new LinkedHashMap<>();
            for (JsonNode e : in.path("tree")) {
                if (!blobs.containsKey(e.path("sha").asText()) || !e.path("mode").asText().equals("100644") || !e.path("type").asText().equals("blob")) {
                    send(ex, 422, "{\"message\":\"tree entry is not valid\"}");
                    return;
                }
                t.put(e.path("path").asText(), e.path("sha").asText());
            }
            String id = "tree" + (++ids);
            trees.put(id, t);
            send(ex, 201, "{\"sha\":\"" + id + "\"}");
        } else if (m.equals("POST") && path.equals(BASE + "/git/commits")) {
            if (!trees.containsKey(in.path("tree").asText()) || !in.path("parents").isArray()) {
                send(ex, 422, "{\"message\":\"bad commit\"}");
                return;
            }
            String id = "commit" + (++ids);
            commitTree.put(id, in.path("tree").asText());
            commitParents.add(in.path("parents").size());
            send(ex, 201, "{\"sha\":\"" + id + "\"}");
        } else if (m.equals("POST") && path.equals(BASE + "/git/refs")) {
            if (branchCommit != null) {
                send(ex, 422, "{\"message\":\"Reference already exists\"}");
            } else if (!in.path("ref").asText().equals("refs/heads/" + BRANCH) || !commitTree.containsKey(in.path("sha").asText())) {
                send(ex, 422, "{\"message\":\"bad ref\"}");
            } else {
                branchCommit = in.path("sha").asText();
                send(ex, 201, "{\"ref\":\"refs/heads/" + BRANCH + "\"}");
            }
        } else if (m.equals("PATCH") && path.equals(BASE + "/git/refs/heads/" + BRANCH)) {
            if (!in.path("force").asBoolean(false) || !commitTree.containsKey(in.path("sha").asText())) {
                send(ex, 422, "{\"message\":\"Update is not a fast forward\"}");
            } else {
                branchCommit = in.path("sha").asText();
                forced = true;
                send(ex, 200, "{\"ref\":\"refs/heads/" + BRANCH + "\"}");
            }
        } else if (m.equals("GET") && path.equals(BASE + "/pages/builds/latest")) {
            List<String> st = pagesStatuses;
            if (st == null || st.isEmpty()) {
                send(ex, pagesCode, "{\"message\":\"Not Found\"}");
            } else {
                String s = st.get(Math.min(pollCount++, st.size() - 1));
                send(ex, 200, "{\"status\":\"" + s + "\",\"commit\":\"" + branchCommit + "\"" + (s.equals("errored") ? ",\"error\":{\"message\":\"Page build failed.\"}" : "") + "}");
            }
        } else {
            send(ex, 404, "{\"message\":\"Not Found\"}");
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }
}
