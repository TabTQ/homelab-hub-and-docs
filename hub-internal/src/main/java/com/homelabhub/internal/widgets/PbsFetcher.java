package com.homelabhub.internal.widgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.homelabhub.internal.config.DashboardConfig.WidgetSpec;
import com.homelabhub.internal.config.SecretStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Proxmox Backup Server datastore usage. Endpoint shapes follow the PBS API documentation; NOT yet verified on a live PBS. */
@Component
public class PbsFetcher implements WidgetFetcher {

    @Override
    public String type() {
        return "proxmoxbackupserver";
    }

    @Override
    public Map<String, Object> fetch(WidgetSpec spec, SecretStore secrets, WidgetHttp http) {
        String user = Support.credential(spec, "username", secrets);
        String secret = Support.credential(spec, "password", secrets);
        if (!user.contains("!")) {
            throw new WidgetException(WidgetException.Code.NOT_CONFIGURED,
                    "Use a PBS API token: the username looks like user@pbs!tokenname and the password is the token secret");
        }
        Map<String, String> headers = Map.of("Authorization", "PBSAPIToken=" + user + ":" + secret);
        String base = Support.base(spec) + "/api2/json";

        JsonNode usage = ProxmoxFetcher.data(http, base + "/status/datastore-usage", headers, "PBS datastore usage");
        List<Map<String, Object>> stores = new ArrayList<>();
        if (usage.isArray()) {
            for (JsonNode s : usage) {
                long total = s.path("total").asLong(0), used = s.path("used").asLong(0);
                stores.add(Map.of("name", s.path("store").asText("?"), "usedBytes", used, "totalBytes", total, "percent", Support.percent(used, total)));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datastores", stores);
        try { // node health is a bonus: a token without Sys.Audit still gets the datastores
            JsonNode node = ProxmoxFetcher.data(http, base + "/nodes/localhost/status", headers, "PBS node status");
            out.put("cpuPercent", Support.round1(node.path("cpu").asDouble(0) * 100));
            long used = node.path("memory").path("used").asLong(0), total = node.path("memory").path("total").asLong(0);
            out.put("memory", Map.of("usedBytes", used, "totalBytes", total, "percent", Support.percent(used, total)));
        } catch (WidgetException ignored) {
            // datastores are enough
        }
        return out;
    }
}
