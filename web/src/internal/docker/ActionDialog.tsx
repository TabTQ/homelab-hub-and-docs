import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, primeCsrf } from "../../shared/api";
import { useToast } from "../../shared/components/Toast";
import { Modal } from "../components/Modal";
import type { Confirmed, Job, Prepared } from "./types";

/** What to prepare: the POST path under /api/admin/docker (e.g. "hosts/pi/containers/<id>/restart/prepare"). */
export type ActionRequest = { path: string; body?: unknown };

type Phase =
  | { kind: "idle" }
  | { kind: "preparing" }
  | { kind: "confirm"; prepared: Prepared; typed: string; busy: boolean; error: string | null }
  | { kind: "job"; title: string; job: Job | null }
  | { kind: "done"; title: string; ok: boolean; message: string }
  | { kind: "error"; title: string; message: string };

/**
 * The Docker page's confirmation flow: prepare (shows exactly what will run, with a one-time token), confirm (typing the
 * name for destructive steps), then follow the job's output when the host takes a while (pull, deploy, run).
 */
export function useDockerAction(onDone: () => void, onUnauthorized: () => void) {
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });
  const [now, setNow] = useState(Date.now());
  const last = useRef<ActionRequest | null>(null);
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const out = useRef<HTMLPreElement>(null);

  const start = useCallback((req: ActionRequest) => {
    last.current = req;
    setPhase({ kind: "preparing" });
    primeCsrf().then(() => api<Prepared>("/api/admin/docker/" + req.path, { method: "POST", body: req.body }))
      .then((prepared) => setPhase({ kind: "confirm", prepared, typed: "", busy: false, error: null }))
      .catch((e: ApiError) => { if (e.status === 401) onUnauthorized(); setPhase({ kind: "error", title: "Cannot do that", message: e.message }); });
  }, [onUnauthorized]);

  const close = useCallback(() => setPhase((p) => (p.kind === "confirm" && p.busy ? p : { kind: "idle" })), []);

  useEffect(() => {
    if (phase.kind !== "confirm") return;
    input.current?.focus();
    const t = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(t);
  }, [phase.kind === "confirm" ? phase.prepared.token : ""]); // eslint-disable-line react-hooks/exhaustive-deps

  const jobId = phase.kind === "job" ? phase.job?.id ?? null : null;
  const jobRunning = phase.kind === "job" && (phase.job == null || phase.job.state === "running");
  useEffect(() => { if (out.current) out.current.scrollTop = out.current.scrollHeight; });

  async function follow(title: string, id: string) {
    for (;;) {
      await new Promise((r) => setTimeout(r, 1000));
      let job: Job;
      try { job = await api<Job>(`/api/admin/docker/jobs/${encodeURIComponent(id)}`); } catch { continue; }
      setPhase((p) => (p.kind === "job" ? { kind: "job", title, job } : p));
      if (job.state !== "running") {
        toast(job.message ?? (job.state === "ok" ? "Done." : "Failed."));
        onDone();
        return;
      }
    }
  }

  async function confirm(p: Prepared, typed: string) {
    setPhase({ kind: "confirm", prepared: p, typed, busy: true, error: null });
    try {
      const r = await api<Confirmed>("/api/admin/docker/confirm", { method: "POST", body: { token: p.token, typed } });
      if (r.jobId) {
        setPhase({ kind: "job", title: p.title, job: { id: r.jobId, hostId: "", title: p.title, state: "running", started: "", ended: null, message: null, output: "" } });
        follow(p.title, r.jobId);
        return;
      }
      setPhase({ kind: "done", title: p.title, ok: true, message: r.message });
      toast(r.message);
      onDone();
    } catch (e) {
      const err = e as ApiError;
      if (err.status === 401) onUnauthorized();
      if (err.status === 422 && err.body?.error === "typed_mismatch") {
        setPhase({ kind: "confirm", prepared: p, typed, busy: false, error: err.message });
        return;
      }
      setPhase({ kind: "error", title: p.title, message: err.message });
      onDone();
    }
  }

  let dialog: React.ReactNode = null;
  if (phase.kind === "preparing") {
    dialog = <Modal title="Checking…" onClose={close} actions={<button className="btn" onClick={close}>Cancel</button>}><p className="sub">Asking the host what this would do…</p></Modal>;
  } else if (phase.kind === "confirm") {
    const p = phase.prepared;
    const left = Math.max(0, Math.round((Date.parse(p.expiresAt) - now) / 1000));
    const expired = left <= 0;
    const okTyped = !p.word || phase.typed.trim() === p.word;
    const destructive = p.confirm === "type";
    dialog = (
      <Modal title={p.title} onClose={close} wide actions={<>
        <button className="btn" onClick={close} disabled={phase.busy}>Cancel</button>
        {expired
          ? <button className="btn pri" onClick={() => last.current && start(last.current)}>Start again</button>
          : <button className={"btn " + (destructive ? "danger" : "pri")} disabled={!okTyped || phase.busy} onClick={() => confirm(p, phase.typed)}>
            {phase.busy ? "Working…" : destructive ? "Yes, do it" : "Confirm"}</button>}
      </>}>
        <div className="impact dk-impact">{p.detail}</div>
        {p.warnings?.length > 0 && <div className="banner warn" role="alert">{p.warnings.map((w) => <div key={w}>⚠ {w}</div>)}</div>}
        {p.word && !expired && (
          <div className="field">
            <label htmlFor="dk-typed">Type <b className="mono">{p.word}</b> to confirm</label>
            <input id="dk-typed" ref={input} type="text" autoComplete="off" autoCapitalize="off" spellCheck={false} value={phase.typed}
              onChange={(e) => setPhase({ ...phase, typed: e.target.value, error: null })}
              onKeyDown={(e) => { if (e.key === "Enter" && okTyped && !phase.busy) confirm(p, phase.typed); }} />
            {phase.error && <div className="err" role="alert">{phase.error}</div>}
          </div>
        )}
        {expired && <p className="result bad" role="alert">This confirmation expired. Start again to get a fresh one.</p>}
        <div className="audit">Recorded in the Docker audit log. Expires in {left}s.</div>
      </Modal>
    );
  } else if (phase.kind === "job") {
    const j = phase.job;
    dialog = (
      <Modal title={phase.title} onClose={close} wide actions={<button className="btn pri" onClick={close}>{jobRunning ? "Hide (keeps running)" : "Close"}</button>}>
        <p className={"result " + (j?.state === "failed" ? "bad" : j?.state === "ok" ? "ok" : "")} role="status">
          {jobRunning ? "Running on the host…" : j?.message}
        </p>
        <pre ref={out} className="dk-out" aria-label="Output" data-job={jobId ?? ""}>{j?.output || " "}</pre>
      </Modal>
    );
  } else if (phase.kind === "done" || phase.kind === "error") {
    const ok = phase.kind === "done" && phase.ok;
    dialog = (
      <Modal title={phase.title} onClose={close} actions={<button className="btn pri" onClick={close}>Close</button>}>
        <p className={"result " + (ok ? "ok" : "bad")} role={ok ? "status" : "alert"} style={{ whiteSpace: "pre-wrap" }}>{phase.message}</p>
      </Modal>
    );
  }
  return { start, dialog };
}
