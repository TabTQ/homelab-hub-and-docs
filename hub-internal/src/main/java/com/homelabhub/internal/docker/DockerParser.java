package com.homelabhub.internal.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Turns the docker CLI's one-JSON-object-per-line output into records. Lines that are not JSON are skipped. */
final class DockerParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DockerParser() {}

    record Container(String id, String name, String image, String state, String status, String health, String ports, String created,
                     String runningFor, String project, String service, String workdir, List<String> configFiles, boolean self) {}

    record Stats(String id, String name, Double cpuPercent, Double memPercent, String memUsage, String netIO, String blockIO,
                 String pids) {}

    /** One image (rows of `docker image ls` with the same id merged); {@code <none>} tags dropped. */
    record Image(String id, List<String> tags, String size, String created) {}

    record Network(String name, String driver, String created) {}

    /** Networks docker creates itself; never offered for removal even if a filter lets one through. */
    static final java.util.Set<String> BUILTIN_NETWORKS = java.util.Set.of("bridge", "host", "none", "ingress", "docker_gwbridge");

    static List<Image> images(String out) {
        java.util.Map<String, Image> byId = new java.util.LinkedHashMap<>();
        for (JsonNode n : lines(out)) {
            String id = text(n, "ID");
            if (id == null || !DockerCommands.IMAGE_ID.matcher(id).matches()) {
                continue;
            }
            id = id.startsWith("sha256:") ? id : "sha256:" + id;
            String repo = text(n, "Repository");
            String tag = text(n, "Tag");
            Image img = byId.computeIfAbsent(id, k -> new Image(k, new ArrayList<>(), text(n, "Size"),
                    text(n, "CreatedSince") != null ? text(n, "CreatedSince") : text(n, "CreatedAt")));
            if (repo != null && !repo.equals("<none>") && tag != null && !tag.equals("<none>")) {
                String ref = repo + ":" + tag;
                if (DockerCommands.IMAGE.matcher(ref).matches() && !img.tags().contains(ref)) {
                    img.tags().add(ref);
                }
            }
        }
        return byId.values().stream().map(i -> new Image(i.id(), List.copyOf(i.tags()), i.size(), i.created())).toList();
    }

    static List<Network> networks(String out) {
        List<Network> list = new ArrayList<>();
        for (JsonNode n : lines(out)) {
            String name = text(n, "Name");
            if (name == null || BUILTIN_NETWORKS.contains(name) || !DockerCommands.NETWORK.matcher(name).matches()) {
                continue;
            }
            list.add(new Network(name, text(n, "Driver"), text(n, "CreatedAt")));
        }
        list.sort((a, b) -> a.name().compareTo(b.name()));
        return list;
    }

    /** One image id per line (from docker inspect), normalised to the sha256: form docker image ls uses. */
    static java.util.Set<String> imageIds(String out) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String line : out.split("\n")) {
            String l = line.strip();
            if (DockerCommands.IMAGE_ID.matcher(l).matches()) {
                ids.add(l.startsWith("sha256:") ? l : "sha256:" + l);
            }
        }
        return ids;
    }

    static List<Container> containers(String out) {
        List<Container> list = new ArrayList<>();
        for (JsonNode n : lines(out)) {
            String status = text(n, "status");
            String files = text(n, "configFiles");
            list.add(new Container(text(n, "id"), stripSlash(text(n, "name")), text(n, "image"),
                    text(n, "state") == null ? null : text(n, "state").toLowerCase(Locale.ROOT), status, health(status),
                    text(n, "ports"), text(n, "created"), text(n, "runningFor"), blankToNull(text(n, "project")),
                    blankToNull(text(n, "service")), blankToNull(text(n, "workdir")),
                    files == null || files.isBlank() ? List.of() : Arrays.stream(files.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList(),
                    "true".equalsIgnoreCase(text(n, "self"))));
        }
        list.sort((a, b) -> String.valueOf(a.name()).compareToIgnoreCase(String.valueOf(b.name())));
        return list;
    }

    static List<Stats> stats(String out) {
        List<Stats> list = new ArrayList<>();
        for (JsonNode n : lines(out)) {
            String id = text(n, "ID");
            list.add(new Stats(id, stripSlash(text(n, "Name")), percent(text(n, "CPUPerc")), percent(text(n, "MemPerc")),
                    text(n, "MemUsage"), text(n, "NetIO"), text(n, "BlockIO"), text(n, "PIDs")));
        }
        return list;
    }

    /** One name per line, blank lines and anything not matching the pattern dropped. */
    static List<String> names(String out, java.util.regex.Pattern allowed) {
        return out.lines().map(String::strip).filter(s -> allowed.matcher(s).matches()).sorted().distinct().toList();
    }

    static String health(String status) {
        if (status == null) {
            return null;
        }
        if (status.contains("(unhealthy)")) {
            return "unhealthy";
        }
        if (status.contains("(healthy)")) {
            return "healthy";
        }
        if (status.contains("(health: starting)")) {
            return "starting";
        }
        return null;
    }

    static Double percent(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s.replace("%", "").strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<JsonNode> lines(String out) {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : out.split("\n")) {
            String l = line.strip();
            if (!l.startsWith("{")) {
                continue;
            }
            try {
                JsonNode n = JSON.readTree(l);
                if (n != null && n.isObject()) {
                    nodes.add(n);
                }
            } catch (Exception e) {
                // a line the hub does not understand is skipped, not fatal
            }
        }
        return nodes;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String stripSlash(String s) {
        return s != null && s.startsWith("/") ? s.substring(1) : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
