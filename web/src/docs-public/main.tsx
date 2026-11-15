import { StrictMode, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import "../shared/fonts";
import "../shared/styles.css";
import "../shared/docs/docs.css";
import { DocRenderer, type RenderContext } from "../shared/docs/DocRenderer";
import { useCurrentTheme } from "../shared/docs/theme";
import type { PublicPage } from "../shared/docs/types";
import { assetHref, bundleBase, staticDataFile } from "../shared/docs/static";
import { WebappFrame } from "../shared/docs/WebappFrame";
import { applyTheme, loadTheme, nextTheme, themeLabel, type ThemeChoice } from "../shared/theme";
import { Crest } from "../shared/components/Crest";

// The public view of one shared page (docs plan 5.5, D5): exactly this page, read-only. No tree, no search, no other pages.
// It only ever calls /api/public/page; files come from /a/<sha> and webapps from /app/<bundle>/ on this same hostname.
// Static copy (GitHub Pages): a static-data tag in the shell names a JSON file next to the page. Then that file is read
// instead of the API, files use the "file" path the JSON lists, and webapps are at app/<bundle>/ relative to the page.
let STATIC_FILE: string | null = null;
let STATIC_ERROR: string | null = null;
try { STATIC_FILE = staticDataFile(); } catch (e) { STATIC_ERROR = (e as Error).message; }
const STATIC = STATIC_FILE !== null;

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
    if (STATIC_ERROR) { setError(STATIC_ERROR); return; }
    fetch(STATIC_FILE ?? "/api/public/page", { headers: { Accept: "application/json" } })
      .then(async (r) => {
        if (!r.ok) throw new Error(r.status === 404 ? "Nothing here." : `This page could not be loaded (HTTP ${r.status}).`);
        return (await r.json()) as PublicPage;
      })
      .then((p) => { setPage(p); document.title = p.title; })
      .catch((e: Error) => setError(e.message));
  }, []);

  const ctx: RenderContext | null = useMemo(() => page && {
    assetUrl: (sha) => assetHref(sha, page.assets, STATIC),
    dark: theme === "dark",
    pageLink: (a) => ({ href: typeof a.href === "string" && a.href.startsWith("https://") ? a.href : null }),
    embed: (a, key) => {
      const e = page.embeds[a.bundle];
      if (!e) return null;
      return (
        <div key={key} className="dx-embed">
          <WebappFrame base={bundleBase(a.bundle, STATIC)} settings={e.settings} theme={theme} title="Embedded app" heightOverride={a.height ?? null} />
        </div>
      );
    },
  }, [page, theme]);

  if (error) return <div className="wrap"><div className="login" role="alert"><h2>{error}</h2></div></div>;
  if (!page || !ctx) return <div className="wrap"><p className="sub" style={{ marginTop: 40 }}>Loading…</p></div>;

  if (page.kind === "webapp" && page.settings?.display !== "framed" && page.bundle) {
    return <div className="dx-full"><WebappFrame base={bundleBase(page.bundle.sha, STATIC)} settings={page.settings!} theme={theme} title={page.title} fill /></div>;
  }

  const updated = formatDate(page.updatedAt);
  return (
    <div className="dx-pubpage">
      <header className="vh-top dx-pubhead">
        <span className="vh-brand"><Crest className="crest" /><span className="t">{page.title}</span></span>
        <div className="vh-actions">
          <button className="btn sm" onClick={() => setChoice(nextTheme(choice))} aria-label="Change theme">Theme: {themeLabel(choice)}</button>
        </div>
      </header>
      <main className="dx-pubbody">
        {updated && <div className="eyebrow">Updated {updated}</div>}
        <h1 className="title">{page.title}</h1>
        <article className="doc dx-paper">
          {page.kind === "rich" ? <DocRenderer doc={page.body} ctx={ctx} /> : (
            <>
              <DocRenderer doc={page.intro} ctx={ctx} />
              {page.bundle && <div className="dx-embed"><WebappFrame base={bundleBase(page.bundle.sha, STATIC)} settings={page.settings!} theme={theme} title={page.title} /></div>}
            </>
          )}
        </article>
      </main>
      <footer className="dx-pubfoot">
        <div className="rule" aria-hidden="true"><i /><i /><i /></div>
        <span>{updated ? `Last updated ${updated} · ` : ""}Shared page, read only</span>
      </footer>
    </div>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
