package com.homelabhub.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings of a webapp page (D9, plan 5.2): display full/framed, height fixed/auto/viewport, theme pass-through, the CDN
 * allowlist, and an optional rich-text intro shown above a framed app.
 */
public final class WebappSettings {

    /** The CDNs a webapp may be allowed to load scripts, styles, fonts and images from. */
    public static final Map<String, String> CDNS = new LinkedHashMap<>(Map.of(
            "cdnjs", "https://cdnjs.cloudflare.com", "jsdelivr", "https://cdn.jsdelivr.net", "unpkg", "https://unpkg.com"));

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private WebappSettings() {}

    public static ObjectNode defaults() {
        ObjectNode s = F.objectNode().put("display", "full");
        s.putObject("height").put("mode", "viewport").put("px", 600);
        s.put("theme", true);
        s.putArray("cdns");
        return s;
    }

    public static ObjectNode validate(JsonNode in) {
        if (in == null || !in.isObject()) {
            throw new DocSchema.InvalidDocException("Invalid webapp settings.");
        }
        ObjectNode out = F.objectNode();
        String display = in.path("display").asText("full");
        if (!List.of("full", "framed").contains(display)) {
            throw new DocSchema.InvalidDocException("Display must be full or framed.");
        }
        out.put("display", display);
        JsonNode h = in.path("height");
        String mode = h.path("mode").asText("viewport");
        if (!List.of("fixed", "auto", "viewport").contains(mode)) {
            throw new DocSchema.InvalidDocException("Height must be fixed, auto or viewport.");
        }
        int px = h.path("px").asInt(600);
        if (px < 150 || px > 4000) {
            throw new DocSchema.InvalidDocException("The height must be between 150 and 4000 px.");
        }
        out.putObject("height").put("mode", mode).put("px", px);
        out.put("theme", in.path("theme").asBoolean(true));
        ArrayNode cdns = out.putArray("cdns");
        for (JsonNode c : in.path("cdns")) {
            if (!CDNS.containsKey(c.asText())) {
                throw new DocSchema.InvalidDocException("Unknown CDN: only cdnjs, jsdelivr and unpkg can be allowed.");
            }
            if (!contains(cdns, c.asText())) {
                cdns.add(c.asText());
            }
        }
        if (in.hasNonNull("intro")) {
            out.set("intro", DocSchema.validate(in.get("intro")).doc());
        }
        return out;
    }

    /** The bundle files' CSP (D10): a sandbox even when a file is opened directly, and only the allowed CDNs. */
    public static String bundleCsp(JsonNode settings, String frameAncestors) {
        StringBuilder src = new StringBuilder("'self'");
        for (JsonNode c : settings == null ? F.arrayNode() : settings.path("cdns")) {
            String origin = CDNS.get(c.asText());
            if (origin != null) {
                src.append(' ').append(origin);
            }
        }
        return "sandbox allow-scripts; default-src " + src + " data: blob: 'unsafe-inline' 'unsafe-eval'; connect-src 'self'; "
                + "frame-ancestors " + frameAncestors + "; base-uri 'self'; form-action 'none'";
    }

    private static boolean contains(ArrayNode a, String s) {
        for (JsonNode x : a) {
            if (x.asText().equals(s)) {
                return true;
            }
        }
        return false;
    }
}
