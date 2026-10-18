import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { api, ApiError, primeCsrf } from "../../shared/api";
import type { Executed, Prepared, TaskStatus } from "../../shared/types";
import { useToast } from "../../shared/components/Toast";

type Run = (actionId: string) => void;
const Ctx = createContext<Run>(() => {});
export const useRunAction = (): Run => useContext(Ctx);

type Phase =
  | { kind: "idle" }
  | { kind: "preparing"; id: string }
  | { kind: "confirm"; prepared: Prepared; typed: string; busy: boolean; error: string | null }
  | { kind: "running"; title: string; message: string }
  | { kind: "done"; title: string; ok: boolean; message: string }
  | { kind: "error"; title: string; message: string };

/**
 * The confirmation flow for power actions (plan 9.1): prepare (impact + one-time token), confirm (typing the name when
 * required), then follow the Proxmox task to its real outcome. Nothing is sent until the user confirms in this dialog.
 */
export function ActionRunner({ children, onDone }: { children: ReactNode; onDone: () => void }) {
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });
  const [now, setNow] = useState(Date.now());
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const confirmBtn = useRef<HTMLButtonElement>(null);
  const lastId = useRef("");

  const close = useCallback(() => setPhase((p) => (p.kind === "running" ? p : { kind: "idle" })), []);

  const run: Run = useCallback((id) => {
    lastId.current = id;
    setPhase({ kind: "preparing", id });
    primeCsrf().then(() => api<Prepared>(`/api/actions/${encodeURIComponent(id)}/prepare`, { method: "POST" }))
      .then((prepared) => {
        if (prepared.confirm === "none") return execute(prepared, "");
        setPhase({ kind: "confirm", prepared, typed: "", busy: false, error: null });
      })
      .catch((e: ApiError) => setPhase({ kind: "error", title: "Cannot prepare this action", message: e.message }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (phase.kind !== "confirm") return;
    const t = window.setInterval(() => setNow(Date.now()), 500);
    (phase.prepared.word ? input : confirmBtn).current?.focus();
    return () => window.clearInterval(t);
  }, [phase.kind === "confirm" ? phase.prepared.token : ""]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "Escape") close(); };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [close]);

  async function execute(prepared: Prepared, typed: string) {
    setPhase({ kind: "running", title: prepared.title, message: "Sending…" });
    try {
      const r = await api<Executed>(`/api/actions/${encodeURIComponent(prepared.actionId)}/confirm`, { method: "POST", body: { token: prepared.token, typed } });
      if (!r.taskId) {
        setPhase({ kind: "done", title: prepared.title, ok: true, message: r.message });
        toast(r.message);
        onDone();
        return;
      }
      setPhase({ kind: "running", title: prepared.title, message: r.message + " Waiting for Proxmox to finish…" });
      await followTask(prepared.title, r.taskId, r.message);
    } catch (e) {
      const err = e as ApiError;
      if (err.status === 422 && err.body?.error === "typed_mismatch") {
        setPhase({ kind: "confirm", prepared, typed, busy: false, error: err.message });
        return;
      }
      setPhase({ kind: "error", title: prepared.title, message: err.message });
    }
  }

  async function followTask(title: string, taskId: string, first: string) {
    for (let i = 0; i < 100; i++) {
      await new Promise((r) => setTimeout(r, 1000));
      try {
        const t = await api<TaskStatus>(`/api/actions/tasks/${encodeURIComponent(taskId)}`);
        if (t.state === "RUNNING") continue;
        const ok = t.state === "OK";
        setPhase({ kind: "done", title, ok, message: ok ? first.replace(/\.$/, "") + ": finished." : (t.detail ?? (t.state === "TIMEOUT" ? "Still running." : "The task failed.")) });
        onDone();
        return;
      } catch { /* keep waiting; the task record may not exist for a moment */ }
    }
    setPhase({ kind: "done", title, ok: false, message: "Still running after a long time. Check Proxmox." });
    onDone();
  }

  let body: ReactNode = null;
  if (phase.kind === "preparing") body = <div className="modal"><p className="sub">Checking what this would affect…</p></div>;
  if (phase.kind === "confirm") {
    const p = phase.prepared;
    const left = Math.max(0, Math.round((Date.parse(p.expiresAt) - now) / 1000));
    const expired = left <= 0;
    const okTyped = !p.word || phase.typed.trim() === p.word;
    body = (
      <div className="modal" role="alertdialog" aria-labelledby="ar-title">
        <div className="eyebrow">{p.kind === "wake" ? "Wake-on-LAN" : p.kind === "node" ? "Proxmox host power" : "Guest control"}</div>
        <h2 id="ar-title">{p.title}</h2>
        <div className="impact">{p.impact.map((l, i) => <div key={i}>{l}</div>)}</div>
        {p.word && !expired && (
          <div>
            <label htmlFor="ar-typed">Type <b className="mono">{p.word}</b> to confirm</label>
            <input id="ar-typed" ref={input} type="text" autoComplete="off" spellCheck={false} value={phase.typed}
              onChange={(e) => setPhase({ ...phase, typed: e.target.value, error: null })}
              onKeyDown={(e) => { if (e.key === "Enter" && okTyped) execute(p, phase.typed); }} />
            {phase.error && <div className="result bad" role="alert" style={{ marginTop: 6 }}>{phase.error}</div>}
          </div>
        )}
        {expired ? <p className="result bad" role="alert">This confirmation expired. Start again to get a fresh one.</p> : null}
        <div className="mrow">
          <button className="btn" onClick={close}>Cancel</button>
          {expired
            ? <button className="btn pri" onClick={() => run(lastId.current)}>Start again</button>
            : <button ref={confirmBtn} className={"btn " + (p.word || p.kind !== "wake" ? "danger" : "pri")} disabled={!okTyped} onClick={() => execute(p, phase.typed)}>{confirmLabel(p)}</button>}
        </div>
        <div className="audit">Recorded in the audit log with time, your address and this browser. Expires in {left}s.</div>
      </div>
    );
  }
  if (phase.kind === "running") body = <div className="modal" role="status"><h2>{phase.title}</h2><p className="sub">{phase.message}</p></div>;
  if (phase.kind === "done") body = (
    <div className="modal" role="status"><h2>{phase.title}</h2><p className={"result " + (phase.ok ? "ok" : "bad")}>{phase.message}</p><div className="mrow"><button className="btn pri" onClick={close}>Close</button></div></div>);
  if (phase.kind === "error") body = (
    <div className="modal" role="alert"><h2>{phase.title}</h2><p className="result bad">{phase.message}</p><div className="mrow"><button className="btn pri" onClick={close}>Close</button></div></div>);

  return (
    <Ctx.Provider value={run}>
      {children}
      {phase.kind !== "idle" && (
        <div className="overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}>
          <div className="dialog">{body}</div>
        </div>
      )}
    </Ctx.Provider>
  );
}

function confirmLabel(p: Prepared): string {
  if (p.kind === "wake") return "Send packet";
  if (p.kind === "node") return p.title.startsWith("Shut") ? "Shut down" : "Reboot";
  return p.desired === "running" ? "Start" : "Stop now";
}
