import { useEffect, useState } from "react";
import { api } from "../shared/api";
import type { RenderContext } from "../shared/docs/DocRenderer";
import type { WebappSettings } from "../shared/docs/types";
import { WebappFrame } from "../shared/docs/WebappFrame";
import { A, assetUrl } from "./docsApi";
import type { TreeItem } from "./treeModel";

/** A webapp shown inside the Docs app: through a short-lived signed preview path, because the sandboxed frame gets no cookie (D15). */
export function EmbedPreview({ pageId, title, height, seq, theme }: { pageId: number; title: string; height?: number | null; seq?: number; theme: "light" | "dark" }) {
  const [p, setP] = useState<{ base: string; settings: WebappSettings } | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    api(`${A}/pages/${pageId}/preview-token`, { method: "POST", body: seq ? { seq } : {} })
      .then((r) => { if (live) setP({ base: r.base, settings: r.settings }); })
      .catch((e) => { if (live) setErr(e.message); });
    return () => { live = false; };
  }, [pageId, seq]);
  if (err) return <div className="hint dx-pad">The app cannot be shown: {err}</div>;
  if (!p) return <div className="hint dx-pad">Loading the app…</div>;
  return <WebappFrame key={p.base} base={p.base} settings={p.settings} theme={theme} title={title} heightOverride={height ?? null} />;
}

export function internalContext(items: TreeItem[], theme: "light" | "dark"): RenderContext {
  const byId = new Map(items.map((i) => [i.id, i]));
  return {
    assetUrl,
    dark: theme === "dark",
    pageLink: (a) => {
      const t = byId.get(Number(a.pageId));
      if (!t) return { href: null, title: "This page no longer exists: visitors see plain text." };
      const isPublic = t.state !== "internal" && !!t.publicHost;
      return { href: `#/p/${t.id}`, title: isPublic ? `“${t.title}” is public: visitors go to https://${t.publicHost}/` : `“${t.title}” is internal: visitors see plain text.` };
    },
    embed: (a, key) => {
      const t = byId.get(Number(a.pageId));
      return (
        <div key={key} className="dx-embed">
          <div className="eh"><span className="dx-pill kapp">app</span><span>{t ? t.title : "Missing webapp page"}</span>
            {t && <a href={`#/p/${t.id}`} className="grow" style={{ textAlign: "right" }}>Open page</a>}</div>
          {t && t.kind === "webapp" ? <EmbedPreview pageId={t.id} title={t.title} height={a.height} theme={theme} /> : <div className="hint dx-pad">Not shown: the page is missing or not a webapp page.</div>}
        </div>
      );
    },
  };
}
