import { lazy, Suspense, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError } from "../../shared/api";
import { Crest } from "../../shared/components/Crest";
import { useToast } from "../../shared/components/Toast";
import { applyTheme, loadTheme, nextTheme, themeLabel, type ThemeChoice } from "../../shared/theme";
import { Sheet } from "../budget/ui";
import type { MemberContext } from "./context";
import "./member.css";

// Member mode (plan 10): only the Ledger. Nothing of the admin app is imported here, so none of it is downloaded.
const BudgetPage = lazy(() => import("../pages/BudgetPage"));

const MEMBER = "/api/member";

function errorText(e: unknown, fallback: string): string {
  const er = e as ApiError;
  if (er?.status === 429) return `Too many attempts. Try again in ${er.body?.retryAfterSeconds ?? "a few"} seconds.`;
  return er?.message && er.status !== 401 && er.status !== 400 ? er.message : fallback;
}

/** The next-password rules the server also checks. */
export function passwordProblem(current: string, next: string, again: string): string | null {
  if (next.length < 8) return "Use at least 8 characters.";
  if (next.length > 200) return "Use at most 200 characters.";
  if (next === current) return "Choose a password different from the current one.";
  if (next !== again) return "The two new passwords do not match.";
  return null;
}

function MemberLogin({ onDone }: { onDone: () => void }) {
  const [forgot, setForgot] = useState(false);
  const [name, setName] = useState("");
  const [pw, setPw] = useState("");
  const [err, setErr] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  const signIn = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      await api(`${MEMBER}/login`, { method: "POST", body: { name: name.trim(), password: pw } });
      if (location.hash.startsWith("#/member")) location.hash = "#/budget";
      onDone();
    } catch (x) { setErr(errorText(x, "Wrong name or password.")); }
    finally { setBusy(false); }
  };
  const ask = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setErr("");
    try { await api(`${MEMBER}/forgot`, { method: "POST", body: { name: name.trim() } }); setSent(true); }
    catch (x) { setErr(errorText(x, "Could not send the request. Try again.")); }
    finally { setBusy(false); }
  };

  return (
    <div className="wrap mem-login">
      <form className="login" onSubmit={forgot ? ask : signIn}>
        <Crest className="crest" />
        <div className="eyebrow">Ledger</div>
        <h1>{forgot ? "Forgot password" : "Sign in"}</h1>
        <label htmlFor="mem-name">Name</label>
        <input id="mem-name" type="text" autoComplete="username" autoCapitalize="none" autoFocus value={name} onChange={(e) => { setName(e.target.value); setSent(false); }} />
        {!forgot && <>
          <label htmlFor="mem-pw">Password</label>
          <input id="mem-pw" type="password" autoComplete="current-password" value={pw} onChange={(e) => setPw(e.target.value)} />
        </>}
        <div className="err" role="alert">{err}</div>
        {forgot
          ? <button className="btn pri" type="submit" disabled={busy || name.trim() === ""}>{busy ? "Sending…" : "Ask for a reset"}</button>
          : <button className="btn pri" type="submit" disabled={busy || name.trim() === "" || pw === ""}>{busy ? "Signing in…" : "Sign in"}</button>}
        {forgot && sent && <p className="mem-note mem-ok" role="status">If that name has a login, the admin now sees your request and will give you a new password.</p>}
        {forgot && <p className="mem-note">After signing in you can change your password under your name, top right.</p>}
        <p className="mem-note"><button type="button" className="linkbtn" onClick={() => { setForgot(!forgot); setErr(""); setSent(false); }}>{forgot ? "Back to sign in" : "Forgot password?"}</button></p>
      </form>
    </div>
  );
}

function usePasswordForm(forced: boolean, onDone: () => void) {
  const [cur, setCur] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const submit = async (e?: React.FormEvent) => {
    e?.preventDefault();
    const p = passwordProblem(cur, next, again);
    if (p) { setErr(p); return; }
    setBusy(true); setErr("");
    try { await api(`${MEMBER}/password`, { method: "POST", body: { current: cur, next } }); onDone(); }
    catch (x) { const s = (x as ApiError).status; setErr(s === 401 || s === 403 ? "The current password is not right." : errorText(x, "Could not change the password.")); }
    finally { setBusy(false); }
  };
  const fields = (
    <div className="mem-pw">
      <label htmlFor="mem-cur">{forced ? "One-time password" : "Current password"}</label>
      <input id="mem-cur" type="password" autoComplete="current-password" autoFocus value={cur} onChange={(e) => setCur(e.target.value)} />
      <label htmlFor="mem-new">New password (8 or more characters)</label>
      <input id="mem-new" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
      <label htmlFor="mem-again">New password again</label>
      <input id="mem-again" type="password" autoComplete="new-password" value={again} onChange={(e) => setAgain(e.target.value)} />
      <div className="err" role="alert">{err}</div>
    </div>
  );
  return { fields, submit, busy };
}

/** First sign-in with a one-time password: nothing else is shown until a new one is chosen. */
function ForcedChange({ name, onDone, onSignOut }: { name: string | null; onDone: () => void; onSignOut: () => void }) {
  const f = usePasswordForm(true, onDone);
  return (
    <div className="wrap mem-login">
      <form className="login" onSubmit={f.submit}>
        <Crest className="crest" />
        <div className="eyebrow">Ledger</div>
        <h1>Choose a new password</h1>
        <p className="mem-note">{name ? `${name}, ` : ""}the password you were given works once. Choose your own to continue.</p>
        {f.fields}
        <button className="btn pri" type="submit" disabled={f.busy}>{f.busy ? "Saving…" : "Save and continue"}</button>
        <p className="mem-note"><button type="button" className="linkbtn" onClick={onSignOut}>Sign out</button></p>
      </form>
    </div>
  );
}

function ChangePasswordSheet({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const f = usePasswordForm(false, onSaved);
  return (
    <Sheet title="Change password" onClose={onClose}
      actions={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn pri" disabled={f.busy} onClick={() => f.submit()}>{f.busy ? "Saving…" : "Save"}</button></>}>
      <form onSubmit={f.submit}>{f.fields}<button type="submit" hidden /></form>
    </Sheet>
  );
}

export function MemberApp({ ctx, reload }: { ctx: MemberContext; reload: () => void }) {
  const toast = useToast();
  const [theme, setTheme] = useState<ThemeChoice>(loadTheme);
  useEffect(() => applyTheme(theme), [theme]);
  const [menu, setMenu] = useState(false);
  const [changing, setChanging] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menu) return;
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") setMenu(false); };
    const click = (e: MouseEvent) => { if (!wrap.current?.contains(e.target as Node)) setMenu(false); };
    window.addEventListener("keydown", key); window.addEventListener("mousedown", click);
    return () => { window.removeEventListener("keydown", key); window.removeEventListener("mousedown", click); };
  }, [menu]);

  const signOut = () => { api(`${MEMBER}/logout`, { method: "POST" }).catch(() => null).finally(() => { location.hash = "#/"; reload(); }); };
  const member = useMemo(() => (ctx.personId === null ? undefined : { personId: ctx.personId, canAdd: ctx.canAdd }), [ctx.personId, ctx.canAdd]);

  const signedIn = ctx.authenticated && ctx.role === "member";
  if (!signedIn) return <MemberLogin onDone={reload} />;
  if (ctx.mustChange) return <ForcedChange name={ctx.name} onDone={reload} onSignOut={signOut} />;

  return (
    <>
      <div className="wrap">
        <header className="top mem-top">
          <div className="brand"><Crest className="crest" />Ledger</div>
          <span className="grow" />
          <div className="mem-menu-wrap" ref={wrap}>
            <button className="btn" aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu(!menu)}>{ctx.name ?? "Account"} ▾</button>
            {menu && (
              <div className="mem-menu" role="menu">
                <button role="menuitem" onClick={() => { setMenu(false); setChanging(true); }}>Change password</button>
                <button role="menuitem" onClick={() => setTheme(nextTheme(theme))}>Theme: {themeLabel(theme)}</button>
                <button role="menuitem" onClick={signOut}>Sign out</button>
              </div>
            )}
          </div>
        </header>
      </div>
      <div className="wrap" style={{ paddingBottom: 80 }}>
        {member
          ? <Suspense fallback={<p className="sub">Loading the ledger…</p>}><BudgetPage member={member} onUnauthorized={reload} /></Suspense>
          : <div className="banner bad" role="alert">This login is not linked to a person. Ask the admin.</div>}
      </div>
      {changing && <ChangePasswordSheet onClose={() => setChanging(false)} onSaved={() => { setChanging(false); toast("Password changed."); }} />}
    </>
  );
}

export default MemberApp;
