import { useEffect, useMemo, useRef, useState } from "react";
import { buildTree, dropPlacement, filterTree, stepPlacement, visibleOrder, type DropZone, type TreeItem, type TreeNode } from "./treeModel";
import { go } from "./docsApi";

export type TreeAction = "new" | "newChild" | "newWebapp" | "move" | "duplicate" | "rename" | "delete" | "copyLink" | "settings";

const COLLAPSED_KEY = "docs-tree-collapsed";

function loadCollapsed(): Set<number> {
  try { return new Set(JSON.parse(localStorage.getItem(COLLAPSED_KEY) ?? "[]")); } catch { return new Set(); }
}

/**
 * The page tree (mockups tab 1). Drag a page onto the top or bottom edge of another to put it before or after, onto the
 * middle to make it a child. The ⋯ menu (or right-click) has the same moves for keyboard and phone users. Keys: ↑/↓ move
 * the focus, ←/→ collapse and expand, Enter opens, F2 renames, Alt+↑/↓ moves the page among its siblings.
 */
export function Tree({ items, currentId, onAction, onMove }: {
  items: TreeItem[]; currentId: number | null; onAction: (a: TreeAction, item: TreeItem | null) => void;
  onMove: (id: number, parentId: number | null, afterId: number | null) => void;
}) {
  const [q, setQ] = useState("");
  const [collapsed, setCollapsed] = useState<Set<number>>(loadCollapsed);
  const [drag, setDrag] = useState<number | null>(null);
  const [over, setOver] = useState<{ id: number; zone: DropZone } | null>(null);
  const [menu, setMenu] = useState<{ item: TreeItem; x: number; y: number } | null>(null);
  const [focusId, setFocusId] = useState<number | null>(currentId);
  const listRef = useRef<HTMLUListElement>(null);

  const nodes = useMemo(() => filterTree(buildTree(items), q), [items, q]);
  const filtering = q.trim() !== "";
  const order = useMemo(() => visibleOrder(nodes, filtering ? new Set() : collapsed), [nodes, collapsed, filtering]);

  useEffect(() => { try { localStorage.setItem(COLLAPSED_KEY, JSON.stringify([...collapsed])); } catch { /* not remembered */ } }, [collapsed]);
  useEffect(() => { if (currentId !== null) setFocusId(currentId); }, [currentId]);
  useEffect(() => {
    const close = () => setMenu(null);
    window.addEventListener("click", close);
    window.addEventListener("scroll", close, true);
    return () => { window.removeEventListener("click", close); window.removeEventListener("scroll", close, true); };
  }, []);

  const focus = (id: number) => {
    setFocusId(id);
    listRef.current?.querySelector<HTMLElement>(`[data-id="${id}"]`)?.focus();
  };
  const toggle = (id: number, open?: boolean) => setCollapsed((c) => {
    const n = new Set(c);
    if (open ?? n.has(id)) n.delete(id); else n.add(id);
    return n;
  });

  const onKey = (e: React.KeyboardEvent, n: TreeNode) => {
    const at = order.indexOf(n.id);
    if (e.altKey && (e.key === "ArrowUp" || e.key === "ArrowDown")) {
      e.preventDefault();
      const p = stepPlacement(items, n.id, e.key === "ArrowUp" ? -1 : 1);
      if (p) onMove(n.id, p.parentId, p.afterId);
    } else if (e.key === "ArrowDown") { e.preventDefault(); if (at + 1 < order.length) focus(order[at + 1]); }
    else if (e.key === "ArrowUp") { e.preventDefault(); if (at > 0) focus(order[at - 1]); }
    else if (e.key === "ArrowRight") { e.preventDefault(); if (n.children.length && collapsed.has(n.id)) toggle(n.id, true); else if (n.children[0]) focus(n.children[0].id); }
    else if (e.key === "ArrowLeft") { e.preventDefault(); if (n.children.length && !collapsed.has(n.id)) toggle(n.id, false); else if (n.parentId !== null) focus(n.parentId); }
    else if (e.key === "Enter") { e.preventDefault(); go(`#/p/${n.id}`); }
    else if (e.key === "F2") { e.preventDefault(); onAction("rename", n); }
    else if (e.key === "Delete") { e.preventDefault(); onAction("delete", n); }
    else if (e.key === "ContextMenu" || (e.shiftKey && e.key === "F10")) {
      e.preventDefault();
      const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
      setMenu({ item: n, x: r.left + 24, y: r.bottom });
    }
  };

  const zoneOf = (e: React.DragEvent): DropZone => {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const y = (e.clientY - r.top) / r.height;
    return y < 0.28 ? "before" : y > 0.72 ? "after" : "into";
  };

  const render = (list: TreeNode[]) => list.map((n) => {
    const isOpen = filtering || !collapsed.has(n.id);
    const dropCls = over?.id === n.id ? ` drop-${over.zone}` : "";
    return (
      <li key={n.id} role="treeitem" aria-expanded={n.children.length ? isOpen : undefined} aria-selected={n.id === currentId}>
        <div className={`dx-node${n.id === currentId ? " on" : ""}${drag === n.id ? " ghost" : ""}${dropCls}`}
          data-id={n.id} tabIndex={n.id === (focusId ?? order[0]) ? 0 : -1} draggable
          onClick={() => go(`#/p/${n.id}`)} onKeyDown={(e) => onKey(e, n)} onFocus={() => setFocusId(n.id)}
          onContextMenu={(e) => { e.preventDefault(); setMenu({ item: n, x: e.clientX, y: e.clientY }); }}
          onDragStart={(e) => { setDrag(n.id); e.dataTransfer.effectAllowed = "move"; e.dataTransfer.setData("text/plain", String(n.id)); }}
          onDragEnd={() => { setDrag(null); setOver(null); }}
          onDragOver={(e) => {
            if (drag === null) return;
            const zone = zoneOf(e);
            if (!dropPlacement(items, drag, n.id, zone)) { setOver(null); return; }
            e.preventDefault();
            e.dataTransfer.dropEffect = "move";
            if (over?.id !== n.id || over.zone !== zone) setOver({ id: n.id, zone });
          }}
          onDragLeave={() => setOver((o) => (o?.id === n.id ? null : o))}
          onDrop={(e) => {
            e.preventDefault();
            const p = drag === null ? null : dropPlacement(items, drag, n.id, zoneOf(e));
            if (p && drag !== null) { onMove(drag, p.parentId, p.afterId); if (p.parentId !== null) toggle(p.parentId, true); }
            setDrag(null); setOver(null);
          }}>
          <span className="tw" onClick={(e) => { if (n.children.length) { e.stopPropagation(); toggle(n.id); } }}
            aria-hidden="true">{n.children.length ? (isOpen ? "▾" : "▸") : ""}</span>
          <i className={`dx-dot${n.state === "public" ? " pub" : n.state === "public-changed" ? " draft" : ""}`}
            title={n.state === "internal" ? "internal" : n.state === "public" ? `public: ${n.publicHost}` : `public with unpublished changes: ${n.publicHost}`} />
          <span className="t">{n.title}</span>
          {n.kind === "webapp" && <span className="kind">app</span>}
          <button className="dx-more" aria-label={`Actions for ${n.title}`} tabIndex={-1}
            onClick={(e) => { e.stopPropagation(); const r = (e.currentTarget as HTMLElement).getBoundingClientRect(); setMenu({ item: n, x: r.left, y: r.bottom }); }}>⋯</button>
        </div>
        {n.children.length > 0 && isOpen && <ul role="group">{render(n.children)}</ul>}
      </li>
    );
  });

  const act = (a: TreeAction) => { const m = menu; setMenu(null); if (m) onAction(a, m.item); };
  return (
    <>
      <div className="dx-sidetop">
        <input className="dx-filter" type="search" placeholder="Filter pages…" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Filter pages" />
        <button className="btn sm pri" title="New page" aria-label="New page" onClick={() => onAction("new", null)}>＋</button>
      </div>
      {nodes.length === 0 ? <p className="hint">{filtering ? "No page matches." : "No pages yet."}</p> : (
        <ul className="dx-tree" role="tree" aria-label="Pages" ref={listRef}
          onDragOver={(e) => { if (drag !== null && e.target === e.currentTarget) e.preventDefault(); }}>
          {render(nodes)}
        </ul>
      )}
      {menu && (
        <div className="dx-ctx" role="menu" style={{ left: Math.min(menu.x, window.innerWidth - 230), top: Math.min(menu.y, window.innerHeight - 320) }}
          onClick={(e) => e.stopPropagation()}>
          <button role="menuitem" onClick={() => act("newChild")}>Add child page</button>
          <button role="menuitem" onClick={() => act("newWebapp")}>Add webapp page</button>
          <button role="menuitem" onClick={() => act("move")}>Move to…</button>
          <button role="menuitem" onClick={() => act("rename")}>Rename</button>
          <button role="menuitem" onClick={() => act("duplicate")}>Duplicate</button>
          <hr />
          <button role="menuitem" onClick={() => act("settings")}>Page settings…</button>
          {menu.item.publicHost && menu.item.state !== "internal" && <button role="menuitem" onClick={() => act("copyLink")}>Copy public link</button>}
          <hr />
          <button role="menuitem" className="bad" onClick={() => act("delete")}>Delete…</button>
        </div>
      )}
    </>
  );
}
