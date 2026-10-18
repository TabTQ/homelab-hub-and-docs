package com.homelabhub.internal.widgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.homelabhub.internal.config.DashboardConfig.WidgetSpec;
import com.homelabhub.internal.config.SecretStore;
import java.util.Map;
import org.springframework.stereotype.Component;

/** File Browser disk usage. Endpoint shapes from memory of the File Browser API; NOT yet verified on a live instance. */
@Component
public class FileBrowserFetcher implements WidgetFetcher {

    @Override
    public String type() {
        return "filebrowser";
    }

    @Override
    public Map<String, Object> fetch(WidgetSpec spec, SecretStore secrets, WidgetHttp http) {
        String user = Support.credential(spec, "username", secrets);
        String pass = Support.credential(spec, "password", secrets);
        String base = Support.base(spec);
        String creds;
        try {
            creds = Support.JSON.writeValueAsString(Map.of("username", user, "password", pass));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new WidgetException(WidgetException.Code.NOT_CONFIGURED, "The credentials could not be encoded");
        }
        WidgetHttp.Response login = http.postJson(base + "/api/login", creds, Map.of());
        WidgetHttp.requireOk(login, "File Browser login");
        String token = login.body().strip();
        if (token.isEmpty() || token.length() > 4096) {
            throw new WidgetException(WidgetException.Code.BAD_RESPONSE, "File Browser sent no login token");
        }
        WidgetHttp.Response r = http.get(base + "/api/usage/", Map.of("X-Auth", token));
        WidgetHttp.requireOk(r, "File Browser usage");
        JsonNode n = Support.parse(r.body(), "File Browser");
        long total = n.path("total").asLong(0), used = n.path("used").asLong(0);
        return Map.of("usedBytes", used, "totalBytes", total, "percent", Support.percent(used, total));
    }
}
