// The Terminal page (docs/design-history/terminal-feature-plan.md): a shell on a host declared in dashboard.yaml, over the hub's SSH relay.
// Lazy-loaded, so xterm.js is only downloaded when this page is opened. Nothing connects until the user clicks Connect and
// re-enters the dashboard password.
import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import "@xterm/xterm/css/xterm.css";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "../../shared/api";
import { Modal } from "../components/Modal";
import { applyModifiers, KEY_ROW, keySequence } from "../terminal/keys";
import { authMessage, closeText, duration, elapsed, inputMessages, parseDetail, parseServerMessage, pingMessage, resizeMessage, socketUrl } from "../terminal/protocol";

interface TerminalHost { id: string; label: string; user: string; host: string; port: number }
interface TerminalConfig { enabled: boolean; idleSeconds: number; warnSeconds: number; maxSeconds: number; maxSessions: number; activeSessions: number; terminals: TerminalHost[] }
interface AuditRow { id: number; ts: string; event: "open" | "close" | "denied"; hostId: string | null; clientIp: string | null; detail: string | null }

type Phase = "idle" | "connecting" | "connected" | "ended";
interface Ended { reason: string; message: string }

const FONT_KEY = "hub.terminal.fontSize";
const PING_MS = 20_000;

function loadFontSize(): number {
  try { const v = Number(localStorage.getItem(FONT_KEY)); return v >= 9 && v <= 28 ? v : 14; } catch { return 14; }
}
function saveFontSize(v: number) {
  try { localStorage.setItem(FONT_KEY, String(v)); } catch { /* per-viewer convenience only */ }
}

/** xterm colours from the page's own tokens, so light and dark themes match the rest of the app. */
function themeFromTokens() {
  const css = getComputedStyle(document.documentElement);
  const v = (n: string, d: string) => css.getPropertyValue(n).trim() || d;
  return {
    background: v("--surface", "#f6f8f9"), foreground: v("--ink", "#13202b"), cursor: v("--accent", "#c2410c"),
    cursorAccent: v("--surface", "#f6f8f9"), selectionBackground: v("--accent-soft", "#f6dccf"), selectionForeground: v("--ink", "#13202b"),
  };
}

function hostFromHash(): string | null {
  const m = location.hash.match(/^#\/terminal\/([a-z0-9][a-z0-9_-]{0,31})$/);
  return m ? m[1] : null;
}

export default function TerminalPage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [config, setConfig] = useState<TerminalConfig | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [hostId, setHostId] = useState<string | null>(hostFromHash);
  const [audit, setAudit] = useState<AuditRow[] | null>(null);
  const [phase, setPhase] = useState<Phase>("idle");
  const [stepUp, setStepUp] = useState(false);
  const [ended, setEnded] = useState<Ended | null>(null);
  const [warnLeft, setWarnLeft] = useState<number | null>(null);
  const [who, setWho] = useState<string>("");
  const [since, setSince] = useState<number | null>(null);
  const [now, setNow] = useState(Date.now());
  const [ctrl, setCtrl] = useState(false);
  const [alt, setAlt] = useState(false);
  const [fontSize, setFontSize] = useState(loadFontSize);
  const [note, setNote] = useState<string | null>(null);

  const pageRef = useRef<HTMLDivElement>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<Terminal | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const pingRef = useRef<number | undefined>(undefined);
  const mods = useRef({ ctrl: false, alt: false });
  mods.current = { ctrl, alt };

  const loadAudit = useCallback(() => {
    api<AuditRow[]>("/api/admin/terminal/audit?limit=20").then(setAudit).catch(() => setAudit(null));
  }, []);

  useEffect(() => {
    api<TerminalConfig>("/api/admin/terminal/config")
      .then((c) => { setConfig(c); setHostId((h) => (h && c.terminals.some((t) => t.id === h) ? h : c.terminals[0]?.id ?? null)); })
      .catch((e: ApiError) => { if (e.status === 401) onUnauthorized(); else setLoadError(e.message); });
    loadAudit();
  }, [onUnauthorized, loadAudit]);

  // ---- the xterm instance: created once, lives as long as the page ----
  useEffect(() => {
    const term = new Terminal({
      fontFamily: getComputedStyle(document.documentElement).getPropertyValue("--f-mono").trim() || "monospace",
      fontSize, scrollback: 5000, cursorBlink: true, theme: themeFromTokens(), allowProposedApi: false,
      convertEol: false, macOptionIsMeta: true, disableStdin: true,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(boxRef.current!);
    const ta = term.textarea;
    if (ta) {
      // Phone keyboards: no autocorrect, capitalisation or suggestions inside a shell.
      ta.setAttribute("autocapitalize", "off"); ta.setAttribute("autocorrect", "off"); ta.setAttribute("autocomplete", "off");
      ta.setAttribute("spellcheck", "false"); ta.setAttribute("inputmode", "text"); ta.setAttribute("aria-label", "Terminal input");
    }
    termRef.current = term;
    fitRef.current = fit;
    document.fonts?.ready.then(() => { try { fit.fit(); } catch { /* not visible yet */ } });

    const dataSub = term.onData((d) => {
      const m = mods.current;
      const out = m.ctrl || m.alt ? applyModifiers(d, m) : d;
      if (m.ctrl) setCtrl(false);
      if (m.alt) setAlt(false);
      send(out);
    });
    const resizeSub = term.onResize(({ cols, rows }) => {
      const ws = wsRef.current;
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(resizeMessage(cols, rows));
    });

    // Re-theme when the app theme (data-theme) or the system scheme changes.
    const retheme = () => { term.options.theme = themeFromTokens(); };
    const mo = new MutationObserver(retheme);
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    const mq = window.matchMedia?.("(prefers-color-scheme: dark)");
    mq?.addEventListener?.("change", retheme);

    return () => {
      dataSub.dispose(); resizeSub.dispose(); mo.disconnect(); mq?.removeEventListener?.("change", retheme);
      const ws = wsRef.current;
      if (ws) { ws.onclose = null; ws.onmessage = null; ws.close(1000); }
      wsRef.current = null;
      window.clearInterval(pingRef.current);
      term.dispose();
      termRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---- sizing: while a session is live the page fills what is left of the VISUAL viewport, so the phone keyboard never
  // hides the cursor and the page itself does not scroll. Otherwise it flows normally (recent sessions below). ----
  const live = phase === "connecting" || phase === "connected";
  useEffect(() => {
    const vv = window.visualViewport;
    const refit = () => { try { fitRef.current?.fit(); } catch { /* hidden */ } };
    const size = () => {
      const el = pageRef.current;
      if (!el) return;
      // On a phone the app header (nav, search) would take a third of the screen, so a live session goes full screen
      // over it, sized to the visible viewport (which shrinks when the keyboard opens). Larger screens keep the header.
      const phone = live && window.innerWidth < 700;
      el.classList.toggle("term-full", phone);
      el.style.top = phone && vv ? vv.offsetTop + "px" : "";
      if (!live) { el.style.height = ""; refit(); return; }
      const top = phone ? 0 : el.getBoundingClientRect().top;
      const h = (vv ? vv.height : window.innerHeight) - Math.max(0, top);
      el.style.height = Math.max(200, Math.floor(h)) + "px";
      refit();
    };
    size();
    vv?.addEventListener("resize", size);
    vv?.addEventListener("scroll", size);
    window.addEventListener("resize", size);
    const ro = new ResizeObserver(refit);
    if (boxRef.current) ro.observe(boxRef.current);
    return () => { vv?.removeEventListener("resize", size); vv?.removeEventListener("scroll", size); window.removeEventListener("resize", size); ro.disconnect(); };
  }, [config, live]);

  useEffect(() => {
    const term = termRef.current;
    if (!term) return;
    term.options.fontSize = fontSize;
    saveFontSize(fontSize);
    try { fitRef.current?.fit(); } catch { /* hidden */ }
  }, [fontSize]);

  // Clock for the status bar and the idle countdown: runs only while connected.
  useEffect(() => {
    if (phase !== "connected") return;
    const t = window.setInterval(() => { setNow(Date.now()); setWarnLeft((w) => (w === null ? null : Math.max(0, w - 1))); }, 1000);
    return () => window.clearInterval(t);
  }, [phase]);

  function send(data: string) {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN || !data) return;
    for (const m of inputMessages(data)) ws.send(m);
    setWarnLeft(null); // any key resets the server's idle timer
  }

  const finish = useCallback((e: Ended) => {
    setEnded(e);
    setPhase("ended");
    setWarnLeft(null);
    setCtrl(false); setAlt(false);
    const term = termRef.current;
    if (term) { term.options.disableStdin = true; term.options.cursorBlink = false; }
    loadAudit();
  }, [loadAudit]);

  // ---- connect: ticket from the step-up, then the WebSocket ----
  const connect = useCallback((ticket: string) => {
    const term = termRef.current!;
    setEnded(null); setNote(null);
    setPhase("connecting");
    term.reset();
    try { fitRef.current?.fit(); } catch { /* hidden */ }
    const ws = new WebSocket(socketUrl(location));
    ws.binaryType = "arraybuffer";
    wsRef.current = ws;
    let gotExit = false;
    ws.onopen = () => {
      ws.send(authMessage(ticket, term.cols, term.rows));
      window.clearInterval(pingRef.current);
      pingRef.current = window.setInterval(() => { if (ws.readyState === WebSocket.OPEN) ws.send(pingMessage()); }, PING_MS);
    };
    ws.onmessage = (ev) => {
      if (ev.data instanceof ArrayBuffer) { term.write(new Uint8Array(ev.data)); return; }
      const m = parseServerMessage(String(ev.data));
      if (!m) return;
      if (m.type === "ready") {
        setWho(`${m.user}@${m.label}`);
        setSince(Date.now()); setNow(Date.now());
        setPhase("connected");
        term.options.disableStdin = false; term.options.cursorBlink = true;
        ws.send(resizeMessage(term.cols, term.rows));
        term.focus();
      } else if (m.type === "warn") {
        setWarnLeft(m.secondsLeft);
      } else if (m.type === "exit") {
        gotExit = true;
        finish({ reason: m.reason, message: m.message || "The terminal was closed." });
      }
    };
    ws.onclose = (ev) => {
      window.clearInterval(pingRef.current);
      if (wsRef.current === ws) wsRef.current = null;
      if (!gotExit) finish({ reason: ev.code === 1008 ? "logout" : "error", message: closeText(ev.code) });
    };
  }, [finish]);

  const disconnect = () => {
    const ws = wsRef.current;
    if (ws) { ws.onclose = null; ws.onmessage = null; ws.close(1000); wsRef.current = null; }
    window.clearInterval(pingRef.current);
    finish({ reason: "closed", message: "You closed the terminal." });
  };

  // ---- key row ----
  const onKey = (id: string, sticky?: "ctrl" | "alt") => {
    if (sticky === "ctrl") { setCtrl((c) => !c); return; }
    if (sticky === "alt") { setAlt((a) => !a); return; }
    const term = termRef.current;
    const seq = keySequence(id, term?.modes.applicationCursorKeysMode ?? false);
    if (seq === null) return;
    const out = alt ? "\x1b" + seq : seq;
    if (alt) setAlt(false);
    send(out);
  };

  const paste = async () => {
    const term = termRef.current;
    try {
      const text = await navigator.clipboard.readText();
      if (text && term) term.paste(text); // honours bracketed paste, then arrives through onData
      term?.focus();
    } catch {
      setNote("The browser did not allow reading the clipboard. Long-press inside the terminal and choose Paste instead.");
    }
  };
  const copy = async () => {
    const sel = termRef.current?.getSelection() ?? "";
    if (!sel) { setNote("Select some text in the terminal first."); return; }
    try { await navigator.clipboard.writeText(sel); setNote("Copied."); }
    catch { setNote("The browser did not allow writing to the clipboard."); }
  };

  const host = config?.terminals.find((t) => t.id === hostId) ?? null;

  if (loadError) return <div className="wrap"><div className="banner bad" role="alert">{loadError}</div></div>;
  if (config && (!config.enabled || config.terminals.length === 0)) {
    return (
      <div className="wrap term-off">
        <h1>Terminal</h1>
        <div className="banner" role="alert">
          {!config.enabled
            ? <>The terminal is turned off on this hub. It needs <span className="mono">HUB_TERMINAL_ENABLED=true</span> and a dashboard login (see the README, "Terminal setup").</>
            : <>No terminals are declared yet. Add a <span className="mono">terminals:</span> entry in the Config page (see the README, "Terminal setup").</>}
        </div>
      </div>
    );
  }

  return (
    <div className={"term-page" + (live ? " live" : "")} ref={pageRef}>
      <div className="wrap term-bar">
        {config && config.terminals.length > 1 && !live ? (
          <select className="term-select" aria-label="Host" value={hostId ?? ""} onChange={(e) => { setHostId(e.target.value); history.replaceState(null, "", `#/terminal/${e.target.value}`); }}>
            {config.terminals.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
          </select>
        ) : (
          <span className="term-who mono">{phase === "connected" ? who : host ? `${host.user}@${host.label}` : "Loading…"}</span>
        )}
        {phase === "connected" && since !== null && <span className="term-time mono" aria-label="Connected for">{elapsed(now - since)}</span>}
        {phase === "connecting" && <span className="term-time mono">connecting…</span>}
        <span className="grow" />
        <button type="button" className="btn sm" aria-label="Smaller text" onClick={() => setFontSize((s) => Math.max(9, s - 1))}>A−</button>
        <button type="button" className="btn sm" aria-label="Larger text" onClick={() => setFontSize((s) => Math.min(28, s + 1))}>A+</button>
        <button type="button" className="btn sm" onClick={copy}>Copy</button>
        <button type="button" className="btn sm" onClick={paste} disabled={phase !== "connected"}>Paste</button>
        {live
          ? <button type="button" className="btn" onClick={disconnect}>Disconnect</button>
          : <button type="button" className="btn pri" disabled={!host} onClick={() => setStepUp(true)}>{phase === "ended" ? "Reconnect" : "Connect"}</button>}
      </div>

      {ended && (
        <div className="wrap"><div className={"banner term-ended" + (ended.reason === "closed" ? "" : " bad")} role="alert">{ended.message}</div></div>
      )}
      {note && <div className="wrap"><div className="term-note" role="status">{note} <button type="button" className="linkbtn" onClick={() => setNote(null)}>Dismiss</button></div></div>}

      <div className={"term-box" + (phase === "idle" ? " term-empty" : "")}>
        <div className="term-xterm" ref={boxRef} onClick={() => termRef.current?.focus()} />
        {/* Over the terminal, not above it: a banner in the flow would resize the terminal, and a resize counts as activity. */}
        {warnLeft !== null && phase === "connected" && (
          <div className="banner warn term-warn" role="alert">Idle: the session closes in {warnLeft} s. Press any key to keep it open.</div>
        )}
        {phase === "idle" && (
          <div className="term-intro">
            <p>{host ? <>Opens a shell as <b className="mono">{host.user}</b> on <b>{host.label}</b> ({host.host}:{host.port}).</> : "Loading…"}</p>
            <p className="sub">You will be asked for the dashboard password. Sessions close after {duration(config?.idleSeconds ?? 900)} without typing and after {duration(config?.maxSeconds ?? 7200)} at most; use tmux for long jobs.</p>
          </div>
        )}
      </div>

      <div className="term-keys" role="toolbar" aria-label="Terminal keys">
        {KEY_ROW.map((k) => (
          <button key={k.id} type="button" className={"term-key" + ((k.sticky === "ctrl" && ctrl) || (k.sticky === "alt" && alt) ? " on" : "")}
            aria-label={k.aria} aria-pressed={k.sticky ? (k.sticky === "ctrl" ? ctrl : alt) : undefined} disabled={phase !== "connected"}
            // pointerdown + preventDefault keeps the focus (and the phone keyboard) on the terminal
            onPointerDown={(e) => { e.preventDefault(); onKey(k.id, k.sticky); }}
            onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onKey(k.id, k.sticky); } }}>
            {k.label}
          </button>
        ))}
      </div>

      {!live && <RecentSessions rows={audit} />}

      {stepUp && host && (
        <StepUpDialog host={host} onCancel={() => setStepUp(false)} onUnauthorized={onUnauthorized}
          onTicket={(t) => { setStepUp(false); connect(t); }} />
      )}
    </div>
  );
}

function StepUpDialog({ host, onCancel, onTicket, onUnauthorized }: { host: TerminalHost; onCancel: () => void; onTicket: (t: string) => void; onUnauthorized: () => void }) {
  const [pw, setPw] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    if (!pw || busy) return;
    setBusy(true); setError(null);
    try {
      const r = await api<{ ticket: string }>("/api/admin/terminal/sessions", { method: "POST", body: { hostId: host.id, password: pw } });
      setPw("");
      onTicket(r.ticket);
    } catch (e) {
      const err = e as ApiError;
      setPw("");
      if (err.status === 401 && err.body?.error !== "invalid_credentials") { onUnauthorized(); return; }
      setError(err.body?.message ?? err.message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title={`Open a terminal on ${host.label}`} onClose={onCancel}
      actions={<><button type="button" className="btn" onClick={onCancel}>Cancel</button>
        <button type="submit" form="term-stepup" className="btn pri" disabled={!pw || busy}>{busy ? "Checking…" : "Connect"}</button></>}>
      <form id="term-stepup" onSubmit={(e) => { e.preventDefault(); submit(); }}>
        <p className="sub">A shell as <b className="mono">{host.user}</b> needs the dashboard password again, every time.</p>
        <div className="field">
          <label htmlFor="term-pw">Dashboard password</label>
          <input id="term-pw" className="term-pw" type="password" autoComplete="current-password" autoFocus value={pw} onChange={(e) => setPw(e.target.value)} />
          {error && <div className="err" role="alert">{error}</div>}
        </div>
      </form>
    </Modal>
  );
}

function RecentSessions({ rows }: { rows: AuditRow[] | null }) {
  if (!rows) return null;
  return (
    <div className="wrap">
      <div className="card term-recent">
        <h3>Recent sessions</h3>
        {rows.length === 0 ? <p className="sub">None yet.</p> : (
          <div className="tablewrap">
            <table>
              <thead><tr><th>Time</th><th>Event</th><th>Host</th><th>Details</th><th>From</th></tr></thead>
              <tbody>
                {rows.map((r) => {
                  const d = parseDetail(r.detail);
                  const parts = [d.reason, d.duration_s ? `${elapsed(Number(d.duration_s) * 1000)}` : null, d.code, d.exit ? `exit ${d.exit}` : null].filter(Boolean);
                  return (
                    <tr key={r.id}>
                      <td className="mono term-ts">{new Date(r.ts).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}</td>
                      <td><span className={"term-ev term-ev-" + r.event}>{r.event}</span></td>
                      <td className="mono">{r.hostId ?? "–"}</td>
                      <td>{parts.join(" · ") || "–"}</td>
                      <td className="mono">{r.clientIp ?? "–"}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
