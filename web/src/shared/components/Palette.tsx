import { useEffect, useMemo, useRef, useState } from "react";

export interface PaletteItem { group: string; title: string; hint?: string; run: () => void }

/** Command palette (Ctrl/Cmd+K). The parent decides what is searchable, so the public build only indexes portfolio content. */
export function Palette({ items, open, onClose }: { items: PaletteItem[]; open: boolean; onClose: () => void }) {
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) { setQ(""); setSel(0); setTimeout(() => input.current?.focus(), 0); }
  }, [open]);

  const shown = useMemo(() => {
    const t = q.toLowerCase().split(/\s+/).filter(Boolean);
    return items.filter((x) => t.every((k) => `${x.title} ${x.hint ?? ""} ${x.group}`.toLowerCase().includes(k))).slice(0, t.length ? 30 : 12);
  }, [items, q]);

  useEffect(() => { if (sel >= shown.length) setSel(Math.max(0, shown.length - 1)); }, [shown, sel]);

  if (!open) return null;
  const run = (x: PaletteItem | undefined) => { if (x) { onClose(); x.run(); } };
  let last = "";
  return (
    <div className="overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Search">
        <input ref={input} type="text" value={q} placeholder="Jump to a section, project, role, skill or service" autoComplete="off" aria-label="Search"
          onChange={(e) => { setQ(e.target.value); setSel(0); }}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown") { e.preventDefault(); setSel((s) => Math.min(s + 1, shown.length - 1)); }
            else if (e.key === "ArrowUp") { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)); }
            else if (e.key === "Enter") { e.preventDefault(); run(shown[sel]); }
            else if (e.key === "Escape") { e.preventDefault(); onClose(); }
          }} />
        <div className="res" role="listbox">
          {shown.length === 0 && <div className="gl">No matches</div>}
          {shown.map((x, i) => {
            const head = x.group !== last ? <div className="gl" key={"g" + i}>{x.group}</div> : null;
            last = x.group;
            return (
              <div key={i}>
                {head}
                <button role="option" aria-selected={i === sel} onClick={() => run(x)} onMouseMove={() => setSel(i)}>
                  <span>{x.title}</span><span className="hint">{x.hint}</span>
                </button>
              </div>
            );
          })}
        </div>
        <div className="pfoot2"><span>↑↓ move</span><span>Enter open</span><span>Esc close</span></div>
      </div>
    </div>
  );
}

/** Ctrl/Cmd+K toggles the palette. */
export function usePaletteHotkey(toggle: () => void): void {
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") { e.preventDefault(); toggle(); }
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [toggle]);
}

/** Fires the "open the palette" event that the page hosting a Palette listens for. */
export const openPalette = () => window.dispatchEvent(new Event("hub:open-palette"));
