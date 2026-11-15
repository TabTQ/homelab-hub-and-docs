package com.homelabhub.docs.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The few GitHub REST calls the Pages publishing needs, over plain {@code java.net.http}: read a branch and its tree, create blobs,
 * trees and a parentless commit, move the branch, and read the latest Pages build. Errors carry the HTTP status and GitHub's own
 * message, never the token or the request headers.
 */
public class GitHubClient {

    /** One file of a tree: {@code mode} is always 100644 for what this app writes. */
    public record TreeEntry(String path, String sha) {}

    /** The branch as it is now: its commit, its tree, and every file of that tree ({@code truncated} when GitHub cut the list short). */
    public record Remote(String commitSha, String treeSha, Map<String, String> files, boolean otherEntries, boolean truncated) {}

    public record PagesBuild(String status, String commit, String error) {}

    public static class GitHubException extends RuntimeException {
        private final int status;

        GitHubException(int status, String message) {
            super(message);
            this.status = status;
        }

        /** 0 when GitHub could not be reached at all. */
        public int status() {
            return status;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(3);

    private final GitHubProperties p;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public GitHubClient(GitHubProperties props) {
        this.p = props;
    }

    private String repoPath() {
        return "/repos/" + p.owner() + "/" + p.repoName();
    }

    // ---------------------------------------------------------------- reading

    /** @return the branch, or empty when it does not exist yet */
    public Optional<Remote> readBranch() {
        JsonNode ref;
        try {
            ref = call("GET", repoPath() + "/git/ref/heads/" + encodePath(p.branch()), null, TIMEOUT);
        } catch (GitHubException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        String commit = ref.path("object").path("sha").asText("");
        if (commit.isEmpty()) {
            throw new GitHubException(200, "GitHub returned no commit for the branch.");
        }
        String tree = call("GET", repoPath() + "/git/commits/" + commit, null, TIMEOUT).path("tree").path("sha").asText("");
        if (tree.isEmpty()) {
            throw new GitHubException(200, "GitHub returned no tree for the branch.");
        }
        JsonNode t = call("GET", repoPath() + "/git/trees/" + tree + "?recursive=1", null, TIMEOUT);
        Map<String, String> files = new LinkedHashMap<>();
        boolean other = false;
        for (JsonNode e : t.path("tree")) {
            String type = e.path("type").asText();
            if (type.equals("blob") && e.path("mode").asText().equals("100644")) {
                files.put(e.path("path").asText(), e.path("sha").asText());
            } else if (!type.equals("tree")) {
                other = true; // a submodule, a symlink or an executable file: not what this app writes
            }
        }
        return Optional.of(new Remote(commit, tree, files, other, t.path("truncated").asBoolean(false)));
    }

    /** @return the latest Pages build; a 403 or 404 means the Pages API is not available to this token or repository */
    public PagesBuild latestBuild() {
        JsonNode b = call("GET", repoPath() + "/pages/builds/latest", null, TIMEOUT);
        String err = b.path("error").path("message").asText("");
        return new PagesBuild(b.path("status").asText(""), b.path("commit").asText(""), err.isEmpty() ? null : clip(err));
    }

    // ---------------------------------------------------------------- writing

    /** Creates a blob and returns its id. The content is streamed as base64, so a big file is never held in memory twice. */
    public String createBlob(SiteContent content) throws IOException {
        byte[] head = "{\"encoding\":\"base64\",\"content\":\"".getBytes(StandardCharsets.US_ASCII);
        byte[] tail = "\"}".getBytes(StandardCharsets.US_ASCII);
        long length = head.length + tail.length + 4L * ((content.size() + 2) / 3);
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return new java.io.SequenceInputStream(java.util.Collections.enumeration(
                        List.of(new ByteArrayInputStream(head), new Base64InputStream(content.open()), new ByteArrayInputStream(tail))));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }), length);
        JsonNode r = send("POST", repoPath() + "/git/blobs", body, UPLOAD_TIMEOUT);
        return r.path("sha").asText();
    }

    public String createTree(List<TreeEntry> entries) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode tree = body.putArray("tree");
        for (TreeEntry e : entries) {
            tree.addObject().put("path", e.path()).put("mode", "100644").put("type", "blob").put("sha", e.sha());
        }
        return call("POST", repoPath() + "/git/trees", body, TIMEOUT).path("sha").asText();
    }

    /** A commit with no parents: the branch never carries history. */
    public String createCommit(String message, String treeSha) {
        ObjectNode body = JSON.createObjectNode().put("message", message).put("tree", treeSha);
        body.putArray("parents");
        return call("POST", repoPath() + "/git/commits", body, TIMEOUT).path("sha").asText();
    }

    /** Points the branch at {@code commitSha}: creates it, or moves it with force. */
    public void moveBranch(String commitSha, boolean exists) {
        if (!exists) {
            ObjectNode body = JSON.createObjectNode().put("ref", "refs/heads/" + p.branch()).put("sha", commitSha);
            try {
                call("POST", repoPath() + "/git/refs", body, TIMEOUT);
                return;
            } catch (GitHubException e) {
                if (e.status() != 422) {
                    throw e; // 422 = the branch appeared meanwhile: move it instead
                }
            }
        }
        ObjectNode body = JSON.createObjectNode().put("sha", commitSha).put("force", true);
        call("PATCH", repoPath() + "/git/refs/heads/" + encodePath(p.branch()), body, TIMEOUT);
    }

    // ---------------------------------------------------------------- transport

    private JsonNode call(String method, String path, JsonNode body, Duration timeout) {
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString());
        return send(method, path, pub, timeout);
    }

    private JsonNode send(String method, String path, HttpRequest.BodyPublisher body, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(p.apiUrl() + path)).timeout(timeout)
                .header("Authorization", "Bearer " + p.token()).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "homelab-docs");
        if (!method.equals("GET")) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body);
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubException(0, "GitHub request interrupted.");
        } catch (IOException | RuntimeException e) {
            throw new GitHubException(0, "GitHub API not reachable (" + e.getClass().getSimpleName() + ").");
        }
        if (res.statusCode() / 100 != 2) {
            String detail = "";
            try {
                detail = JSON.readTree(res.body()).path("message").asText("");
            } catch (IOException | RuntimeException ignored) {
                // no JSON body
            }
            throw new GitHubException(res.statusCode(), describe(res.statusCode(), detail));
        }
        try {
            return res.body() == null || res.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(res.body());
        } catch (IOException e) {
            throw new GitHubException(res.statusCode(), "GitHub sent an answer that is not JSON.");
        }
    }

    private String describe(int status, String detail) {
        String hint = switch (status) {
            case 401 -> " Check DOCS_GH_TOKEN.";
            case 403 -> " The token may lack permission, or GitHub is limiting requests.";
            case 404 -> " Check DOCS_GH_REPO and that the token can reach it.";
            case 409 -> " If the repository is empty, create a first commit in it.";
            default -> "";
        };
        String msg = "GitHub API " + status + (detail.isEmpty() ? "" : ": " + clip(detail)) + "." + hint;
        return scrub(msg);
    }

    /** Whatever happens, the token never ends up in a message. */
    String scrub(String s) {
        return p.token().isEmpty() ? s : s.replace(p.token(), "***");
    }

    private static String clip(String s) {
        String flat = s.replaceAll("[\\r\\n]+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }

    private static String encodePath(String branch) {
        StringBuilder out = new StringBuilder();
        for (String part : branch.split("/")) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(URLEncoder.encode(part, StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /** Reads a stream and returns its base64 text, 3 KB at a time. */
    static final class Base64InputStream extends InputStream {
        private final InputStream in;
        private byte[] out = new byte[0];
        private int pos = 0;
        private boolean done = false;

        Base64InputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (pos >= out.length) {
                if (done) {
                    return -1;
                }
                byte[] chunk = in.readNBytes(3 * 1024); // a multiple of 3, so only the last chunk is padded
                if (chunk.length < 3 * 1024) {
                    done = true;
                }
                out = Base64.getEncoder().encode(chunk);
                pos = 0;
            }
            int n = Math.min(len, out.length - pos);
            System.arraycopy(out, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
