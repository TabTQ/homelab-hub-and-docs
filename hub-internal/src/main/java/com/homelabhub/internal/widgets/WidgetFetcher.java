package com.homelabhub.internal.widgets;

import com.homelabhub.internal.config.DashboardConfig.WidgetSpec;
import com.homelabhub.internal.config.SecretStore;
import java.util.Map;

public interface WidgetFetcher {
    String type();

    /** @return plain JSON-able data. Throws {@link WidgetException} for every expected failure. */
    Map<String, Object> fetch(WidgetSpec spec, SecretStore secrets, WidgetHttp http);
}
