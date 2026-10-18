package com.homelabhub.internal.widgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.homelabhub.internal.config.DashboardConfig.WidgetSpec;
import com.homelabhub.internal.config.SecretStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Proxmox VE node summary (verified against a real Proxmox 8 node, see contract/proxmox-fixtures). API token auth only. */
@Component
public class ProxmoxFetcher implements WidgetFetcher {

    @Override
    public String type() {
        return "proxmox";
    }

    @Override
    public Map<String, Object> fetch(WidgetSpec spec, SecretStore secrets, WidgetHttp http) {
        String user = Support.credential(spec, "username", secrets);
        String secret = Support.credential(spec, "password", secrets);
        if (!user.contains("!")) {
            throw new WidgetException(WidgetException.Code.NOT_CONFIGURED,
                    "Use a Proxmox API token: the username looks like user@pam!tokenname and the password is the token secret");
        }
        String node = spec.options().get("node");
        Map<String, String> headers = Map.of("Authorization", "PVEAPIToken=" + user + "=" + secret);
        String base = Support.base(spec) + "/api2/json/nodes/" + node;

        JsonNode status = data(http, base + "/status", headers, "Proxmox node status");
        JsonNode lxc = data(http, base + "/lxc", headers, "Proxmox container list");
        JsonNode qemu = data(http, base + "/qemu", headers, "Proxmox VM list");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuPercent", Support.round1(status.path("cpu").asDouble(0) * 100));
        long used = status.path("memory").path("used").asLong(0), total = status.path("memory").path("total").asLong(0);
        out.put("memory", Map.of("usedBytes", used, "totalBytes", total, "percent", Support.percent(used, total)));
        out.put("uptimeSeconds", status.path("uptime").asLong(0));
        out.put("lxc", counts(lxc));
        out.put("vms", counts(qemu));
        return out;
    }

    private static Map<String, Object> counts(JsonNode list) {
        int running = 0, total = 0;
        if (list.isArray()) {
            for (JsonNode g : list) {
                total++;
                if ("running".equals(g.path("status").asText())) {
                    running++;
                }
            }
        }
        return Map.of("running", running, "total", total);
    }

    static JsonNode data(WidgetHttp http, String url, Map<String, String> headers, String what) {
        WidgetHttp.Response r = http.get(url, headers);
        WidgetHttp.requireOk(r, what);
        JsonNode data = Support.parse(r.body(), what).path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new WidgetException(WidgetException.Code.BAD_RESPONSE, what + " had no data");
        }
        return data;
    }
}
