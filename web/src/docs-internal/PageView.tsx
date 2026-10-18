import { useEffect, useMemo, useState } from "react";
import { api } from "../shared/api";
import { DocRenderer, headings } from "../shared/docs/DocRenderer";
import { useCurrentTheme } from "../shared/docs/theme";
import type { PMNode } from "../shared/docs/types";
import { blockDiff, changeCount } from "./docDiff";
import { A, ago, go, loadPage, when, type DocsConfig, type PageDetail, type PublishCheck } from "./docsApi";
import { internalContext } from "./internalRender";
import { PublishDialog } from "./PublishDialog";
import type { TreeItem } from "./treeModel";
import { WebappPage } from "./WebappPage";

/** Reading a page (mockups tab 1): the draft, with a banner when it differs from what the public sees. */
export function PageView({ id, items, config, onChanged, fail, publish }: {
  id: number; items: TreeItem[]; config: DocsConfig | null; onChanged: () => void; fail: (e: any) => void; publish?: boolean;
}) {
  const [page, setPage] = useState<PageDetail | null>(null);
  const [missing, setMissing] = useState(false);
  const [pending, setPending] = useState<number | null>(null);
  const theme = useCurrentTheme();
  const reload = () => loadPage(id).then(setPage).catch((e) => { if (e.status === 404) setMissing(true); else fail(e); });
  useEffect(() => { reload(); }, [id]); // eslint-disable-line react-hooks/exhaustive-deps

  // How many blocks the draft changed since the live version (the banner's "3 changes").
  useEffect(() => {
    if (!page || page.state !== "public-changed" || page.kind !== "rich") { setPending(null); return; }
    api<PublishCheck>(`${A}/pages/${id}/publish-check`)
      .then((c) => setPending(Math.max(1, changeCount(blockDiff(c.liveBody as PMNode, page.body as PMNode)) + (c.liveTitle !== page.title ? 1 : 0))))
      .catch(() => setPending(null));
  }, [page, id]);

  const ctx = useMemo(() => internalContext(items, theme), [items, theme]);
  const toc = useMemo(() => (page?.kind === "rich" ? headings(page.body as PMNode) : []), [page]);

  if (missing) return <div className="dx-home"><h1>Not found</h1><p className="sub">This page does not exist or is in the Trash.</p><a href="#/trash">Open the Trash</a></div>;
  if (!page) return <p className="hint">Loading…</p>;

  const changed = () => { reload(); onChanged(); };
  return (
    <div className="dx-page">
      <nav className="dx-crumbs" aria-label="Breadcrumbs">
        {page.breadcrumbs.map((b) => <span key={b.id}><a href={`#/p/${b.id}`}>{b.title}</a> / </span>)}{page.title}
      </nav>
      <div className="dx-pagehead">
        <h1>{page.title} {page.kind === "webapp" && <span className="dx-pill kapp">webapp</span>}</h1>
        <div className="dx-headbtns">
          <button className="btn sm" onClick={() => go(`#/p/${id}/history`)}>History</button>
          <button className="btn sm" onClick={() => go(`#/p/${id}/publish`)}>Settings</button>
          {page.kind === "rich" && <button className="btn sm" onClick={() => go(`#/p/${id}/edit`)}>Edit</button>}
          <button className="btn sm pri" onClick={() => go(`#/p/${id}/publish`)}>Publish…</button>
        </div>
      </div>
      <div className="dx-meta">
        {page.state === "internal" ? <span className="dx-pill">internal</span> : <span className="dx-pill pub">public</span>}
        {page.publicHost && <a className="mono" href={page.state === "internal" ? undefined : `https://${page.publicHost}/`} target="_blank" rel="noreferrer">{page.publicHost}</a>}
        {page.publicHost && page.state === "internal" && <span>(reserved, not published)</span>}
        <span>· Draft saved {ago(page.updatedAt)}</span>
        {page.publishedAt && page.state !== "internal" && <span>· Published {when(page.publishedAt)}</span>}
      </div>
      {page.state === "public-changed" && (
        <div className="dx-banner">The draft has {pending === null ? "changes" : <b>{pending} change{pending === 1 ? "" : "s"}</b>} that the public page does not show yet.{" "}
          <a href={`#/p/${id}/history`}>Compare</a> · <a href={`#/p/${id}/publish`}>Publish</a></div>
      )}
      {page.kind === "webapp" ? <WebappPage page={page} config={config} onChanged={changed} fail={fail} theme={theme} /> : (
        <div className={toc.length > 1 ? "dx-withtoc" : undefined}>
          <article className="doc"><DocRenderer doc={page.body as PMNode} ctx={ctx} /></article>
          {toc.length > 1 && (
            <nav className="dx-toc" aria-label="On this page"><div className="caption">On this page</div>
              {toc.map((h) => <a key={h.id} href={`#/p/${id}`} style={{ paddingLeft: (h.level - 2) * 10 }}
                onClick={(e) => { e.preventDefault(); document.getElementById(h.id)?.scrollIntoView({ behavior: "smooth" }); }}>{h.text}</a>)}
            </nav>
          )}
        </div>
      )}
      {publish && <PublishDialog page={page} items={items} config={config} onClose={() => go(`#/p/${id}`)} onChanged={changed} fail={fail} />}
    </div>
  );
}
