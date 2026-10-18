import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError } from "../../shared/api";
import { shortTime, splitLogLine } from "./format";
import type { Container, DockerHost } from "./types";

const MAX_BUFFER = 10_000;
const TAILS = [200, 1000, 5000];

/** A container's logs: the last N lines, optionally followed live (SSE), with search, wrap and timestamps. */
export function LogsDrawer({ host, container, onClose, onUnauthorized }: {
  host: DockerHost; container: Container; onClose: () => void; onUnauthorized: () => void;
}) {
  const [tail, setTail] = useState(200);
  const [lines, setLines] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [follow, setFollow] = useState(false);
  const [followNote, setFollowNote] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [wrap, setWrap] = useState(true);
  const [stamps, setStamps] = useState(true);
  const [stick, setStick] = useState(true);
  const box = useRef<HTMLDivElement>(null);
  const base = `/api/admin/docker/hosts/${encodeURIComponent(host.id)}/containers/${container.id}`;

  const load = useCallback(() => {
    setLoading(true);
    api<{ lines: string[] }>(`${base}/logs?tail=${tail}`)
      .then((r) => { setLines(r.lines); setError(null); setStick(true); })
      .catch((e: ApiError) => { if (e.status === 401) onUnauthorized(); setError(e.message); })
      .finally(() => setLoading(false));
  }, [base, tail, onUnauthorized]);
  useEffect(load, [load]);

  useEffect(() => {
    if (!follow) return;
    setFollowNote(null);
    const es = new EventSource(`${base}/logs/stream`);
    es.addEventListener("lines", (ev) => {
      const add = JSON.parse((ev as MessageEvent).data) as string[];
      setLines((l) => { const next = l.concat(add); return next.length > MAX_BUFFER ? next.slice(next.length - MAX_BUFFER) : next; });
    });
    es.addEventListener("failed", (ev) => { setFollowNote(JSON.parse((ev as MessageEvent).data).message); setFollow(false); es.close(); });
    es.addEventListener("end", () => { setFollowNote("Following stopped (time limit or the container ended). Turn it on again to continue."); setFollow(false); es.close(); });
    es.onerror = () => { if (es.readyState === EventSource.CLOSED) { setFollowNote("The live connection closed."); setFollow(false); } };
    return () => es.close();
  }, [follow, base]);

  useEffect(() => { const h = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); }; window.addEventListener("keydown", h); return () => window.removeEventListener("keydown", h); }, [onClose]);
  useEffect(() => { if (stick && box.current) box.current.scrollTop = box.current.scrollHeight; }, [lines, stick, wrap, stamps]);

  const q = query.trim().toLowerCase();
  const shown = useMemo(() => {
    const parsed = lines.map((l) => splitLogLine(l));
    return q ? parsed.filter((p) => p.text.toLowerCase().includes(q)) : parsed;
  }, [lines, q]);

  const copy = () => {
    const text = shown.map((p) => (stamps && p.ts ? p.ts + " " : "") + p.text).join("\n");
    navigator.clipboard?.writeText(text).catch(() => {});
  };

  return (
    <div className="overlay dk-drawer-wrap" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <aside className="dk-drawer" role="dialog" aria-modal="true" aria-label={`Logs of ${container.name}`}>
        <header className="dk-drawer-head">
          <div>
            <div className="eyebrow">{host.label} · logs</div>
            <h2 className="mono">{container.name}</h2>
          </div>
          <span className="grow" />
          <button className="btn" onClick={onClose} aria-label="Close logs">Close</button>
        </header>
        <div className="dk-logbar">
          <div className="seg" role="group" aria-label="Lines">
            {TAILS.map((t) => <button key={t} aria-pressed={tail === t} onClick={() => setTail(t)}>{t}</button>)}
          </div>
          <label className="dk-check"><input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)} /> Follow</label>
          <label className="dk-check"><input type="checkbox" checked={wrap} onChange={(e) => setWrap(e.target.checked)} /> Wrap</label>
          <label className="dk-check"><input type="checkbox" checked={stamps} onChange={(e) => setStamps(e.target.checked)} /> Time</label>
          <input className="dk-search" type="search" placeholder="Find in logs" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Find in logs" />
          <button className="btn sm" onClick={load} disabled={loading}>Reload</button>
          <button className="btn sm" onClick={copy}>Copy</button>
        </div>
        {error && <div className="banner bad" role="alert">{error}</div>}
        {followNote && <div className="banner warn" role="status">{followNote}</div>}
        <div ref={box} className={"dk-log" + (wrap ? " dk-wrapped" : "")} aria-live="off"
          onScroll={(e) => { const el = e.currentTarget; setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 24); }}>
          {loading && lines.length === 0 ? <div className="dk-muted">Loading…</div>
            : shown.length === 0 ? <div className="dk-muted">{q ? "No line matches." : "No log output."}</div>
            : shown.map((p, i) => (
              <div key={i} className="dk-line">
                {stamps && p.ts && <span className="dk-ts" title={p.ts}>{shortTime(p.ts)}</span>}
                <span>{q ? highlight(p.text, q) : p.text}</span>
              </div>
            ))}
        </div>
        <div className="dk-logfoot">
          <span>{shown.length}{q ? ` of ${lines.length}` : ""} lines{follow ? " · following" : ""}</span>
          {!stick && <button className="btn sm" onClick={() => setStick(true)}>Jump to end</button>}
        </div>
      </aside>
    </div>
  );
}

function highlight(text: string, q: string): React.ReactNode {
  const out: React.ReactNode[] = [];
  const lower = text.toLowerCase();
  let i = 0;
  for (let k = 0; k < 50; k++) {
    const j = lower.indexOf(q, i);
    if (j < 0) break;
    out.push(text.slice(i, j), <mark key={j}>{text.slice(j, j + q.length)}</mark>);
    i = j + q.length;
  }
  out.push(text.slice(i));
  return out;
}
