package com.homelabhub.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The whitelist for rich page bodies (D8, plan 5.2). A body is a ProseMirror document; only the node types and marks listed
 * here are accepted, attributes are checked one by one, and unknown attributes are dropped (the editor adds some defaults
 * we do not store). Anything else is refused on save, so no HTML and no script can ever reach the renderer.
 */
public final class DocSchema {

    public static final int MAX_JSON_BYTES = 1024 * 1024;
    public static final int MAX_NODES = 5000;
    static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z0-9+#._-]{1,40}");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Node type → whether it is inline (text level). */
    private static final Map<String, Boolean> NODES = Map.ofEntries(
            Map.entry("doc", false), Map.entry("paragraph", false), Map.entry("heading", false), Map.entry("text", true),
            Map.entry("bulletList", false), Map.entry("orderedList", false), Map.entry("taskList", false), Map.entry("taskItem", false),
            Map.entry("listItem", false), Map.entry("blockquote", false), Map.entry("horizontalRule", false), Map.entry("hardBreak", true),
            Map.entry("codeBlock", false), Map.entry("table", false), Map.entry("tableRow", false), Map.entry("tableHeader", false),
            Map.entry("tableCell", false), Map.entry("image", false), Map.entry("callout", false), Map.entry("details", false),
            Map.entry("detailsSummary", false), Map.entry("detailsContent", false), Map.entry("attachment", false),
            Map.entry("mermaid", false), Map.entry("pageLink", true), Map.entry("webappEmbed", false));

    private static final Set<String> MARKS = Set.of("bold", "italic", "underline", "strike", "code", "link");
    private static final Set<String> CALLOUTS = Set.of("info", "tip", "warning", "danger");

    /** What a body points at: used for the publish check, asset copying and link rewriting. */
    public record Refs(Set<String> assets, Set<Long> pageLinks, Set<Long> embeds) {}

    public record Result(ObjectNode doc, Refs refs, int nodes) {}

    public static final class InvalidDocException extends IllegalArgumentException {
        public InvalidDocException(String message) {
            super(message);
        }
    }

    private DocSchema() {}

    /** An empty document: one empty paragraph. */
    public static ObjectNode empty() {
        ObjectNode doc = F.objectNode().put("type", "doc");
        doc.putArray("content").addObject().put("type", "paragraph");
        return doc;
    }

    /** Validates and cleans {@code body}. Throws {@link InvalidDocException} with a message fit for the editor. */
    public static Result validate(JsonNode body) {
        if (body == null || !body.isObject() || !"doc".equals(body.path("type").asText())) {
            throw new InvalidDocException("The page body must be a document.");
        }
        if (body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new InvalidDocException("The page is larger than 1 MB. Split it into several pages.");
        }
        Walk w = new Walk();
        ObjectNode clean = w.node(body, 0);
        return new Result(clean, new Refs(w.assets, w.links, w.embeds), w.count);
    }

    /** Collects references without changing anything (for bodies that were validated when saved). */
    public static Refs refs(JsonNode body) {
        return validate(body).refs();
    }

    static boolean safeHref(String href) {
        if (href == null || href.length() > 2000) {
            return false;
        }
        String h = href.strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < h.length(); i++) {
            if (h.charAt(i) < 0x20) {
                return false;
            }
        }
        return h.startsWith("http://") || h.startsWith("https://") || h.startsWith("mailto:");
    }

    private static final class Walk {
        final Set<String> assets = new LinkedHashSet<>();
        final Set<Long> links = new LinkedHashSet<>();
        final Set<Long> embeds = new LinkedHashSet<>();
        int count;

        ObjectNode node(JsonNode in, int depth) {
            if (++count > MAX_NODES) {
                throw new InvalidDocException("The page has more than " + MAX_NODES + " blocks. Split it into several pages.");
            }
            if (depth > 40) {
                throw new InvalidDocException("The page is nested too deeply.");
            }
            if (!in.isObject()) {
                throw new InvalidDocException("Unexpected content in the page.");
            }
            String type = in.path("type").asText("");
            if (!NODES.containsKey(type)) {
                throw new InvalidDocException("Unsupported block type: " + clip(type) + ".");
            }
            if (depth > 0 && type.equals("doc")) {
                throw new InvalidDocException("A document cannot contain another document.");
            }
            ObjectNode out = F.objectNode().put("type", type);
            JsonNode a = in.path("attrs");
            ObjectNode attrs = F.objectNode();
            switch (type) {
                case "text" -> {
                    String text = in.path("text").asText("");
                    if (text.isEmpty()) {
                        throw new InvalidDocException("Empty text node.");
                    }
                    out.put("text", text);
                    ArrayNode marks = marks(in.path("marks"));
                    if (!marks.isEmpty()) {
                        out.set("marks", marks);
                    }
                }
                case "heading" -> attrs.put("level", intIn(a.path("level"), 1, 4, "heading level"));
                case "orderedList" -> {
                    if (a.has("start") && !a.get("start").isNull()) {
                        attrs.put("start", intIn(a.get("start"), 0, 100000, "list start"));
                    }
                }
                case "taskItem" -> attrs.put("checked", a.path("checked").asBoolean(false));
                case "codeBlock" -> {
                    String lang = a.path("language").asText("");
                    if (!lang.isEmpty()) {
                        if (!LANGUAGE.matcher(lang.toLowerCase(Locale.ROOT)).matches()) {
                            throw new InvalidDocException("Invalid code language.");
                        }
                        attrs.put("language", lang.toLowerCase(Locale.ROOT));
                    }
                }
                case "tableCell", "tableHeader" -> {
                    attrs.put("colspan", intIn(a.has("colspan") ? a.get("colspan") : F.numberNode(1), 1, 50, "colspan"));
                    attrs.put("rowspan", intIn(a.has("rowspan") ? a.get("rowspan") : F.numberNode(1), 1, 500, "rowspan"));
                    JsonNode cw = a.path("colwidth");
                    if (cw.isArray()) {
                        ArrayNode widths = attrs.putArray("colwidth");
                        for (JsonNode x : cw) {
                            widths.add(x.isNull() ? 0 : intIn(x, 0, 4000, "column width"));
                        }
                    }
                }
                case "image" -> {
                    attrs.put("sha", sha(a.path("sha"), "image"));
                    attrs.put("alt", text(a.path("alt"), 500));
                    attrs.put("caption", text(a.path("caption"), 500));
                    if (a.hasNonNull("width")) {
                        attrs.put("width", intIn(a.get("width"), 10, 100, "image width (%)"));
                    }
                    assets.add(attrs.get("sha").asText());
                }
                case "callout" -> {
                    String t = a.path("type").asText("info");
                    if (!CALLOUTS.contains(t)) {
                        throw new InvalidDocException("Unknown callout type.");
                    }
                    attrs.put("type", t);
                }
                case "details" -> attrs.put("open", a.path("open").asBoolean(false));
                case "attachment" -> {
                    attrs.put("sha", sha(a.path("sha"), "attachment"));
                    attrs.put("name", nonEmpty(text(a.path("name"), 255), "attachment name"));
                    attrs.put("size", Math.max(0, a.path("size").asLong(0)));
                    assets.add(attrs.get("sha").asText());
                }
                case "mermaid" -> {
                    attrs.put("source", text(a.path("source"), 20000));
                    if (a.hasNonNull("lightSvg")) {
                        attrs.put("lightSvg", sha(a.get("lightSvg"), "diagram"));
                        assets.add(attrs.get("lightSvg").asText());
                    }
                    if (a.hasNonNull("darkSvg")) {
                        attrs.put("darkSvg", sha(a.get("darkSvg"), "diagram"));
                        assets.add(attrs.get("darkSvg").asText());
                    }
                }
                case "pageLink" -> {
                    long id = pageId(a.path("pageId"));
                    attrs.put("pageId", id);
                    attrs.put("label", nonEmpty(text(a.path("label"), 200), "page link label"));
                    links.add(id);
                }
                case "webappEmbed" -> {
                    long id = pageId(a.path("pageId"));
                    attrs.put("pageId", id);
                    if (a.hasNonNull("height")) {
                        attrs.put("height", intIn(a.get("height"), 150, 4000, "embed height"));
                    }
                    embeds.add(id);
                }
                default -> { /* no attributes */ }
            }
            if (!attrs.isEmpty()) {
                out.set("attrs", attrs);
            }
            JsonNode content = in.path("content");
            if (content.isArray() && !content.isEmpty()) {
                if (NODES.get(type) || type.equals("horizontalRule") || type.equals("image") || type.equals("attachment")
                        || type.equals("mermaid") || type.equals("webappEmbed")) {
                    throw new InvalidDocException("A " + type + " block cannot contain other blocks.");
                }
                ArrayNode kids = out.putArray("content");
                for (JsonNode c : content) {
                    kids.add(node(c, depth + 1));
                }
            } else if (!content.isMissingNode() && !content.isNull() && !content.isArray()) {
                throw new InvalidDocException("Unexpected content in a " + type + " block.");
            }
            return out;
        }

        private ArrayNode marks(JsonNode in) {
            ArrayNode out = F.arrayNode();
            if (in.isMissingNode() || in.isNull()) {
                return out;
            }
            if (!in.isArray()) {
                throw new InvalidDocException("Invalid text formatting.");
            }
            for (JsonNode m : in) {
                String t = m.path("type").asText("");
                if (!MARKS.contains(t)) {
                    throw new InvalidDocException("Unsupported formatting: " + clip(t) + ".");
                }
                ObjectNode mo = F.objectNode().put("type", t);
                if (t.equals("link")) {
                    String href = m.path("attrs").path("href").asText("");
                    if (!safeHref(href)) {
                        throw new InvalidDocException("Links must start with http://, https:// or mailto:.");
                    }
                    mo.putObject("attrs").put("href", href.strip());
                }
                out.add(mo);
            }
            return out;
        }
    }

    private static int intIn(JsonNode n, int min, int max, String what) {
        if (n == null || !n.canConvertToInt() || !(n.isIntegralNumber() || n.isTextual() && n.asText().matches("-?\\d{1,9}"))) {
            throw new InvalidDocException("Invalid " + what + ".");
        }
        int v = n.isTextual() ? Integer.parseInt(n.asText()) : n.asInt();
        if (v < min || v > max) {
            throw new InvalidDocException("Invalid " + what + ".");
        }
        return v;
    }

    private static long pageId(JsonNode n) {
        if (n == null || !(n.isIntegralNumber() || n.isTextual() && n.asText().matches("\\d{1,15}")) || n.asLong() <= 0) {
            throw new InvalidDocException("Invalid page reference.");
        }
        return n.asLong();
    }

    private static String sha(JsonNode n, String what) {
        String s = n == null ? "" : n.asText("");
        if (!SHA.matcher(s).matches()) {
            throw new InvalidDocException("Invalid " + what + " reference.");
        }
        return s;
    }

    private static String text(JsonNode n, int max) {
        String s = n == null || n.isNull() || n.isMissingNode() ? "" : n.asText("");
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String nonEmpty(String s, String what) {
        if (s.isBlank()) {
            throw new InvalidDocException("Missing " + what + ".");
        }
        return s;
    }

    private static String clip(String s) {
        String flat = s.replaceAll("[^A-Za-z0-9_-]", "?");
        return flat.length() > 40 ? flat.substring(0, 40) : flat;
    }
}
