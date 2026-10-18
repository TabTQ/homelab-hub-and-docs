package com.homelabhub.docspub;

/**
 * The optional helper for webapps (plan 5.6), served at {@code _hub/hub-embed.js} inside every bundle URL. It applies the
 * theme from {@code ?theme=} and from {@code hub:theme} messages, and reports the page height for "auto" height. The same
 * script lives in docs-internal (for the preview); HubEmbedTest there checks the two stay identical.
 */
public final class HubEmbed {

    public static final String PATH = "_hub/hub-embed.js";

    public static final String SCRIPT = """
            // hub-embed.js: optional helper for webapps shown in Homelab Hub Docs. Include with
            //   <script src="_hub/hub-embed.js"></script>
            // It sets <html data-theme="light|dark">, fires a "hub:theme" event on window when the theme changes,
            // and reports the page height to the Docs page (used when the height mode is "auto").
            (function () {
              var theme = new URLSearchParams(location.search).get("theme") === "dark" ? "dark" : "light";
              function apply(t) {
                theme = t === "dark" ? "dark" : "light";
                document.documentElement.setAttribute("data-theme", theme);
                window.dispatchEvent(new CustomEvent("hub:theme", { detail: theme }));
              }
              function report() {
                var px = Math.ceil(Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0));
                parent.postMessage({ type: "hub:height", px: px }, "*");
              }
              window.addEventListener("message", function (e) {
                if (e.source === parent && e.data && e.data.type === "hub:theme") apply(e.data.theme);
              });
              window.hubEmbed = { theme: function () { return theme; }, reportHeight: report };
              apply(theme);
              if (window.ResizeObserver) new ResizeObserver(report).observe(document.documentElement);
              window.addEventListener("load", report);
            })();
            """;

    private HubEmbed() {}
}
