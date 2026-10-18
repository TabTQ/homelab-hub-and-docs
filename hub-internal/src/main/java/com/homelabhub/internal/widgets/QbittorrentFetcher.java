package com.homelabhub.internal.widgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.homelabhub.internal.config.DashboardConfig.WidgetSpec;
import com.homelabhub.internal.config.SecretStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** qBittorrent Web API v2: login sets an SID cookie, then transfer/info and torrents/info. NOT yet verified on a live instance. */
@Component
public class QbittorrentFetcher implements WidgetFetcher {

    private static final Set<String> LEECH = Set.of("downloading", "forcedDL", "stalledDL", "metaDL", "forcedMetaDL", "queuedDL", "checkingDL", "allocating");
    private static final Set<String> SEED = Set.of("uploading", "forcedUP", "stalledUP", "queuedUP", "checkingUP");

    @Override
    public String type() {
        return "qbittorrent";
    }

    @Override
    public Map<String, Object> fetch(WidgetSpec spec, SecretStore secrets, WidgetHttp http) {
        String user = Support.credential(spec, "username", secrets);
        String pass = Support.credential(spec, "password", secrets);
        String base = Support.base(spec);

        WidgetHttp.Response login = http.postForm(base + "/api/v2/auth/login", Map.of("username", user, "password", pass),
                Map.of("Referer", base));
        WidgetHttp.requireOk(login, "qBittorrent login");
        if (!login.body().strip().equalsIgnoreCase("Ok.")) {
            throw new WidgetException(WidgetException.Code.UNAUTHORIZED, "qBittorrent refused the user name or password");
        }
        String sid = sessionCookie(login.headers().getOrDefault("Set-Cookie", login.headers().getOrDefault("set-cookie", java.util.List.of())));
        if (sid == null) {
            throw new WidgetException(WidgetException.Code.BAD_RESPONSE, "qBittorrent sent no session cookie");
        }
        Map<String, String> auth = Map.of("Cookie", "SID=" + sid, "Referer", base);

        WidgetHttp.Response info = http.get(base + "/api/v2/transfer/info", auth);
        WidgetHttp.requireOk(info, "qBittorrent transfer info");
        JsonNode t = Support.parse(info.body(), "qBittorrent");
        WidgetHttp.Response list = http.get(base + "/api/v2/torrents/info", auth);
        WidgetHttp.requireOk(list, "qBittorrent torrent list");
        JsonNode torrents = Support.parse(list.body(), "qBittorrent");

        int leech = 0, seed = 0, total = 0;
        if (torrents.isArray()) {
            for (JsonNode x : torrents) {
                total++;
                String s = x.path("state").asText();
                if (LEECH.contains(s)) {
                    leech++;
                } else if (SEED.contains(s)) {
                    seed++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("downloadBytesPerSec", t.path("dl_info_speed").asLong(0));
        out.put("uploadBytesPerSec", t.path("up_info_speed").asLong(0));
        out.put("leech", leech);
        out.put("seed", seed);
        out.put("total", total);
        return out;
    }

    static String sessionCookie(java.util.List<String> setCookies) {
        for (String c : setCookies) {
            for (String part : c.split(";")) {
                String p = part.strip();
                if (p.startsWith("SID=") && p.length() > 4) {
                    return p.substring(4);
                }
            }
        }
        return null;
    }
}
