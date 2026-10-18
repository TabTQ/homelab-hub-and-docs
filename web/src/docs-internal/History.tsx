import { useEffect, useMemo, useState } from "react";
import { api } from "../shared/api";
import { useToast } from "../shared/components/Toast";
import { DocRenderer } from "../shared/docs/DocRenderer";
import { useCurrentTheme } from "../shared/docs/theme";
import type { PMNode } from "../shared/docs/types";
import { blockDiff, type BlockChange } from "./docDiff";
import { A, go, loadPage, when, type PageDetail, type VersionItem } from "./docsApi";
import { internalContext } from "./internalRender";
import type { TreeItem } from "./treeModel";

interface VersionFull { version: number; kind: string; title: string; body: any; bundleSeq: number | null; note: string | null; createdAt: string }

const KIND_LABEL: Record<string, string> = { created: "created", saved: "saved", published: "published", restored: "restored", moved: "moved",
  renamed: "renamed", host: "hostname", bundle: "app upload", unpublished: "unpublished" };

/** Page history (mockups tab 5): pick a version, compare it with the one before or with what is live, restore it into the draft. */
export default function History({ id, items, onChanged, fail }: { id: number; items: TreeItem[]; onChanged: () => void; fail: (e: any) => void }) {
  const [page, setPage] = useState<PageDetail | null>(null);
  const [versions, setVersions] = useState<VersionItem[] | null>(null);
  const [sel, setSel] = useState<number | null>(null);
  const [against, setAgainst] = useState<"previous" | "live">("previous");
  const [pair, setPair] = useState<{ a: VersionFull | null; b: VersionFull } | null>(null);
  const toast = useToast();
  const theme = useCurrentTheme();
  const ctx = useMemo(() => internalContext(items, theme), [items, theme]);

  useEffect(() => {
    loadPage(id).then(setPage).catch(fail);
    api<VersionItem[]>(`${A}/pages/${id}/versions`).then((v) => { setVersions(v); setSel(v[0]?.version ?? null); }).catch(fail);
  }, [id, fail]);

  const otherVersion = useMemo(() => {
    if (!versions || sel === null) return null;
    if (against === "live") return page?.publishedVersion ?? null;
    const at = versions.findIndex((v) => v.version === sel);
    return versions[at + 1]?.version ?? null;
  }, [versions, sel, against, page]);

  useEffect(() => {
    if (sel === null) return;
    const getV = (v: number) => api<VersionFull>(`${A}/pages/${id}/versions/${v}`);
    Promise.all([getV(sel), otherVersion === null ? Promise.resolve(null) : getV(otherVersion)])
      .then(([b, a]) => setPair({ a, b })).catch(fail);
  }, [id, sel, otherVersion, fail]);

  const restore = () => {
    if (sel === null || !page) return;
    loadPage(id).then((p) => api(`${A}/pages/${id}/restore/${sel}`, { method: "POST", body: { version: p.version } }))
      .then(() => { toast(`v${sel} copied into the draft. It is not published yet.`); onChanged(); go(`#/p/${id}`); }).catch(fail);
  };

  if (!page || !versions) return <p className="hint">Loading…</p>;
  const rich = page.kind === "rich";
  return (
    <div className="dx-page">
      <div className="dx-pagehead"><h1>History · {page.title}</h1><div className="dx-headbtns"><button className="btn sm" onClick={() => go(`#/p/${id}`)}>← Back to page</button></div></div>
      <div className="dx-hist">
        <div className="dx-card dx-verlist" role="listbox" aria-label="Versions">
          {versions.map((v, i) => (
            <button key={v.version} role="option" aria-selected={v.version === sel} className={`dx-ver${v.version === sel ? " on" : ""}`} onClick={() => setSel(v.version)}>
              <b>v{v.version}</b> {i === 0 && <span className="dx-pill draft">draft</span>} {v.live && <span className="dx-pill pub">live</span>}
              {!v.live && v.kind === "published" && <span className="dx-pill">published</span>}
              <div className="w">{when(v.createdAt)} · {KIND_LABEL[v.kind] ?? v.kind}{v.note ? ` · ${v.note}` : ""}{v.bundleSeq ? ` · b${v.bundleSeq}` : ""}</div>
            </button>
          ))}
        </div>
        <div>
          <div className="dx-row" style={{ marginBottom: 12 }}>
            <span>Compare <b>v{sel}</b> with</span>
            <div className="dx-seg">
              <button aria-pressed={against === "previous"} onClick={() => setAgainst("previous")}>previous</button>
              <button aria-pressed={against === "live"} disabled={!page.publishedVersion} onClick={() => setAgainst("live")}>live{page.publishedVersion ? ` (v${page.publishedVersion})` : ""}</button>
            </div>
            <span className="grow" />
            <button className="btn sm" onClick={restore} disabled={sel === null}>Restore v{sel} into draft</button>
          </div>
          {!pair ? <p className="hint">Loading…</p> : !rich ? <WebappDiff a={pair.a} b={pair.b} />
            : <DiffView changes={blockDiff(pair.a?.body as PMNode, pair.b.body as PMNode)} ctx={ctx} titleA={pair.a?.title} titleB={pair.b.title} />}
        </div>
      </div>
    </div>
  );
}

function one(node: PMNode): PMNode {
  return { type: "doc", content: [node] };
}

function DiffView({ changes, ctx, titleA, titleB }: { changes: BlockChange[]; ctx: ReturnType<typeof internalContext>; titleA?: string; titleB: string }) {
  const textual = new Set(["paragraph", "heading", "blockquote", "codeBlock"]);
  return (
    <div className="doc dx-diff">
      {titleA !== undefined && titleA !== titleB && <p className="dx-blockchg"><span className="dx-pill draft">title</span> <del className="del">{titleA}</del> <ins className="ins">{titleB}</ins></p>}
      {changes.every((c) => c.type === "same") && <p className="hint">No changes in the content.</p>}
      {changes.map((c, i) => {
        if (c.type === "same") return <div key={i} className="dx-same"><DocRenderer doc={one(c.node)} ctx={ctx} /></div>;
        if (c.type === "add") return <div key={i} className="dx-blockchg add"><span className="dx-pill pub">added</span><div className="ins"><DocRenderer doc={one(c.node)} ctx={ctx} /></div></div>;
        if (c.type === "del") return <div key={i} className="dx-blockchg del"><span className="dx-pill">removed</span><div className="del"><DocRenderer doc={one(c.node)} ctx={ctx} /></div></div>;
        if (textual.has(c.after.type)) {
          return (
            <div key={i} className="dx-blockchg"><p>{c.words.map((w, j) => w.type === "same" ? <span key={j}>{w.text}</span>
              : w.type === "add" ? <ins key={j} className="ins">{w.text}</ins> : <del key={j} className="del">{w.text}</del>)}</p></div>
          );
        }
        return <div key={i} className="dx-blockchg"><span className="dx-pill draft">{c.after.type === "image" ? "image replaced" : `${c.after.type} changed`}</span><DocRenderer doc={one(c.after)} ctx={ctx} /></div>;
      })}
    </div>
  );
}

function WebappDiff({ a, b }: { a: VersionFull | null; b: VersionFull }) {
  const lines = (v: VersionFull | null) => v ? [`bundle: ${v.bundleSeq ? "b" + v.bundleSeq : "none"}`, `display: ${v.body?.display}`, `height: ${v.body?.height?.mode} ${v.body?.height?.px ?? ""}`,
    `theme: ${v.body?.theme ? "passed to the app" : "off"}`, `CDNs: ${(v.body?.cdns ?? []).join(", ") || "none"}`] : [];
  const la = lines(a), lb = lines(b);
  return (
    <div className="dx-diff">
      {lb.map((l, i) => <div key={i} className={la[i] !== l ? "dx-blockchg" : undefined}>{la[i] !== undefined && la[i] !== l && <><del className="del">{la[i]}</del><br /></>}
        <span className={la[i] !== l ? "ins" : undefined}>{l}</span></div>)}
    </div>
  );
}
