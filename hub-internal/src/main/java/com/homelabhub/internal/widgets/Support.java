package com.homelabhub.internal.widgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelabhub.internal.config.DashboardConfig.WidgetSpec;
import com.homelabhub.internal.config.SecretStore;

/** Small helpers shared by the fetchers. */
final class Support {

    static final ObjectMapper JSON = new ObjectMapper();

    private Support() {}

    /** Reads a credential option, resolving ${secret:NAME}. A missing secret is reported by NAME only. */
    static String credential(WidgetSpec spec, String key, SecretStore secrets) {
        String raw = spec.options().get(key);
        if (raw == null || raw.isBlank()) {
            throw new WidgetException(WidgetException.Code.NOT_CONFIGURED, "The widget has no '" + key + "'");
        }
        try {
            return secrets.resolve(raw);
        } catch (SecretStore.MissingSecretException e) {
            throw new WidgetException(WidgetException.Code.SECRET_MISSING, "Secret '" + e.secretName() + "' is not set");
        }
    }

    static String base(WidgetSpec spec) {
        return spec.url().replaceAll("/+$", "");
    }

    static JsonNode parse(String body, String what) {
        try {
            JsonNode n = JSON.readTree(body);
            if (n == null || n.isMissingNode()) {
                throw new WidgetException(WidgetException.Code.BAD_RESPONSE, what + " sent an empty answer");
            }
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new WidgetException(WidgetException.Code.BAD_RESPONSE, what + " sent an answer that is not JSON");
        }
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static double percent(double used, double total) {
        return total <= 0 ? 0 : round1(100.0 * used / total);
    }
}
