import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api } from "../shared/api";
import { A, loadPage } from "./docsApi";
import { descendants, parentChoices, siblings, type TreeItem } from "./treeModel";
import type { Pending } from "./App";

/** A dialog on desktop, a bottom sheet on a phone (mockups tab 7). Esc and the backdrop close it. */
export function Sheet({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [onClose]);
  return (
    <div className="overlay dx-sheet" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className={`dialog${wide ? " dx-wide" : ""}`} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal"><h2>{title}</h2>{children}</div>
      </div>
    </div>
  );
}

/** Parent and position pickers, used by Move… and the publish dialog. */
export function PlacementFields({ items, movingId, parentId, afterId, onChange }: {
  items: TreeItem[]; movingId: number | null; parentId: number | null; afterId: number | null;
  onChange: (parentId: number | null, afterId: number | null) => void;
}) {
  const parents = useMemo(() => parentChoices(items, movingId), [items, movingId]);
  const sibs = siblings(items, parentId).filter((i) => i.id !== movingId);
  const positionValue = afterId === null ? "first" : String(afterId);
  return (
    <div className="dx-placement">
      <div className="field">
        <label htmlFor="dx-parent">New parent</label>
        <select id="dx-parent" value={parentId === null ? "" : String(parentId)}
          onChange={(e) => { const p = e.target.value === "" ? null : Number(e.target.value); const s = siblings(items, p).filter((i) => i.id !== movingId); onChange(p, s.length ? s[s.length - 1].id : null); }}>
          {parents.map((p) => <option key={p.id ?? "top"} value={p.id ?? ""}>{"  ".repeat(Math.max(0, p.depth - 1)) + p.label}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor="dx-pos">Position</label>
        <select id="dx-pos" value={positionValue} onChange={(e) => onChange(parentId, e.target.value === "first" ? null : Number(e.target.value))}>
          <option value="first">First</option>
          {sibs.map((s, i) => <option key={s.id} value={s.id}>{i === sibs.length - 1 ? `Last (after “${s.title}”)` : `After “${s.title}”`}</option>)}
        </select>
      </div>
    </div>
  );
}

export function MoveDialog({ item, items, onClose, onMove }: {
  item: TreeItem; items: TreeItem[]; onClose: () => void; onMove: (parentId: number | null, afterId: number | null) => void;
}) {
  const before = siblings(items, item.parentId);
  const at = before.findIndex((i) => i.id === item.id);
  const [parentId, setParentId] = useState<number | null>(item.parentId);
  const [afterId, setAfterId] = useState<number | null>(at > 0 ? before[at - 1].id : null);
  const kids = descendants(items, item.id).size - 1;
  return (
    <Sheet title={`Move “${item.title}”`} onClose={onClose}>
      {kids > 0 && <p className="hint">Children move with it ({kids} page{kids === 1 ? "" : "s"}).</p>}
      <PlacementFields items={items} movingId={item.id} parentId={parentId} afterId={afterId} onChange={(p, a) => { setParentId(p); setAfterId(a); }} />
      <p className="hint">Moving a page never changes whether it is public.</p>
      <div className="mrow dx-acts"><button className="btn" onClick={onClose}>Cancel</button><button className="btn pri" onClick={() => onMove(parentId, afterId)}>Move</button></div>
    </Sheet>
  );
}

export function NewPageDialog({ pending, items, onClose, onDone, fail }: {
  pending: Extract<Pending, { kind: "new" }>; items: TreeItem[]; onClose: () => void; onDone: (id: number) => void; fail: (e: any) => void;
}) {
  const [title, setTitle] = useState("");
  const [kind, setKind] = useState(pending.pageKind);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => input.current?.focus(), []);
  const parent = items.find((i) => i.id === pending.parentId);
  const create = (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim()) return;
    setBusy(true);
    api(`${A}/pages`, { method: "POST", body: { parentId: pending.parentId, afterId: pending.afterId, kind, title: title.trim() } })
      .then((p) => onDone(p.id)).catch((x) => { setBusy(false); fail(x); });
  };
  return (
    <Sheet title={parent ? `New page under “${parent.title}”` : "New page"} onClose={onClose}>
      <form onSubmit={create}>
        <div className="field"><label htmlFor="dx-title">Title</label>
          <input id="dx-title" ref={input} type="text" maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} /></div>
        <div className="field"><label>Kind</label>
          <div className="dx-seg" role="radiogroup">
            <button type="button" role="radio" aria-checked={kind === "rich"} onClick={() => setKind("rich")}>Rich page</button>
            <button type="button" role="radio" aria-checked={kind === "webapp"} onClick={() => setKind("webapp")}>Webapp page</button>
          </div>
          <div className="help">{kind === "rich" ? "Text, tables, images, code, diagrams…" : "Upload a static app (index.html or a .zip) that runs in a sandbox."}</div>
        </div>
        <div className="mrow dx-acts"><button type="button" className="btn" onClick={onClose}>Cancel</button><button className="btn pri" disabled={busy || !title.trim()}>Create</button></div>
      </form>
    </Sheet>
  );
}

export function RenameDialog({ item, onClose, onDone, fail }: { item: TreeItem; onClose: () => void; onDone: () => void; fail: (e: any) => void }) {
  const [title, setTitle] = useState(item.title);
  const save = (e: React.FormEvent) => {
    e.preventDefault();
    loadPage(item.id).then((p) => api(`${A}/pages/${item.id}`, { method: "PUT", body: { title: title.trim(), body: p.body, version: p.version } }))
      .then(onDone).catch(fail);
  };
  return (
    <Sheet title="Rename page" onClose={onClose}>
      <form onSubmit={save}>
        <div className="field"><label htmlFor="dx-rename">Title</label>
          <input id="dx-rename" type="text" autoFocus maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} /></div>
        <div className="mrow dx-acts"><button type="button" className="btn" onClick={onClose}>Cancel</button><button className="btn pri" disabled={!title.trim()}>Rename</button></div>
      </form>
    </Sheet>
  );
}

export function DeleteDialog({ item, items, onClose, onDone, fail }: {
  item: TreeItem; items: TreeItem[]; onClose: () => void; onDone: () => void; fail: (e: any) => void;
}) {
  const kids = descendants(items, item.id).size - 1;
  const [mode, setMode] = useState<"delete" | "lift">("lift");
  const del = () => {
    api(`${A}/pages/${item.id}${kids ? `?children=${mode}` : ""}`, { method: "DELETE" }).then(onDone).catch(fail);
  };
  return (
    <Sheet title={`Delete “${item.title}”?`} onClose={onClose}>
      <p>It goes to the Trash and can be restored for 30 days.{item.state !== "internal" && <> Its public page <b className="mono">{item.publicHost}</b> goes offline now.</>}</p>
      {kids > 0 && (
        <fieldset className="dx-choice">
          <legend>It has {kids} page{kids === 1 ? "" : "s"} under it.</legend>
          <label><input type="radio" checked={mode === "lift"} onChange={() => setMode("lift")} /> Keep them: move them up one level</label>
          <label><input type="radio" checked={mode === "delete"} onChange={() => setMode("delete")} /> Delete them too</label>
        </fieldset>
      )}
      <div className="mrow dx-acts"><button className="btn" onClick={onClose}>Cancel</button><button className="btn danger" onClick={del}>Delete</button></div>
    </Sheet>
  );
}
