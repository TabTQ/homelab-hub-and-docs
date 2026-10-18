import { StrictMode, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import "../shared/fonts";
import "../shared/styles.css";
import "../shared/docs/docs.css";
import { DocRenderer, type RenderContext } from "../shared/docs/DocRenderer";
import { useCurrentTheme } from "../shared/docs/theme";
import type { PublicPage } from "../shared/docs/types";
import { WebappFrame } from "../shared/docs/WebappFrame";
import { applyTheme, loadTheme, nextTheme, type ThemeChoice } from "../shared/theme";

// The public view of one shared page (docs plan 5.5, D5): exactly this page, read-only. No tree, no search, no other pages.
// It only ever calls /api/public/page; files come from /a/<sha> and webapps from /app/<bundle>/ on this same hostname.

function formatDate(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

function App() {
  const [page, setPage] = useState<PublicPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [choice, setChoice] = useState<ThemeChoice>(loadTheme);
  useEffect(() => applyTheme(choice), [choice]);
  const theme = useCurrentTheme();

  useEffect(() => {
    fetch("/api/public/page", { headers: { Accept: "application/json" } })
      .then(async (r) => {
        if (!r.ok) throw new Error(r.status === 404 ? "Nothing here." : `This page could not be loaded (HTTP ${r.status}).`);
        return (await r.json()) as PublicPage;
      })
      .then((p) => { setPage(p); document.title = p.title; })
      .catch((e: Error) => setError(e.message));
  }, []);

  const ctx: RenderContext | null = useMemo(() => page && {
    assetUrl: (sha) => `/a/${sha}`,
    dark: theme === "dark",
    pageLink: (a) => ({ href: typeof a.href === "string" && a.href.startsWith("https://") ? a.href : null }),
    embed: (a, key) => {
      const e = page.embeds[a.bundle];
      if (!e) return null;
      return (
        <div key={key} className="dx-embed">
          <WebappFrame base={`/app/${a.bundle}/`} settings={e.settings} theme={theme} title="Embedded app" heightOverride={a.height ?? null} />
        </div>
      );
    },
  }, [page, theme]);

  if (error) return <div className="wrap"><div className="login" role="alert"><h2>{error}</h2></div></div>;
  if (!page || !ctx) return <div className="wrap"><p className="sub" style={{ marginTop: 40 }}>Loading…</p></div>;

  if (page.kind === "webapp" && page.settings?.display !== "framed" && page.bundle) {
    return <div className="dx-full"><WebappFrame base={`/app/${page.bundle.sha}/`} settings={page.settings!} theme={theme} title={page.title} fill /></div>;
  }

  const updated = formatDate(page.updatedAt);
  return (
    <>
      <header className="dx-pubhead">
        <span className="t">{page.title}</span>
        <button className="btn" onClick={() => setChoice(nextTheme(choice))} aria-label="Change theme">◐ Theme</button>
      </header>
      <main className="dx-pubbody">
        <h1 className="title">{page.title}</h1>
        {updated && <div className="updated">Updated {updated}</div>}
        <article className="doc">
          {page.kind === "rich" ? <DocRenderer doc={page.body} ctx={ctx} /> : (
            <>
              <DocRenderer doc={page.intro} ctx={ctx} />
              {page.bundle && <div className="dx-embed"><WebappFrame base={`/app/${page.bundle.sha}/`} settings={page.settings!} theme={theme} title={page.title} /></div>}
            </>
          )}
        </article>
      </main>
      <footer className="dx-pubfoot"><span>{updated ? `Last updated ${updated}` : ""}</span><span>Shared page · read only</span></footer>
    </>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
