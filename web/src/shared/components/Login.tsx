import { useState } from "react";
import { api, ApiError } from "../api";

/** The sign-in form of the hub and of Docs (each with its own password and session, docs plan D-L3). */
export function Login({ passwordConfigured, onLoggedIn, brand = "Homelab Hub", hashVariable = "HUB_PASSWORD_HASH" }: {
  passwordConfigured: boolean; onLoggedIn: () => void; brand?: string; hashVariable?: string;
}) {
  const [pw, setPw] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr("");
    try {
      await api("/api/auth/login", { method: "POST", body: { password: pw } });
      onLoggedIn();
    } catch (x) {
      const er = x as ApiError;
      if (er.status === 401) setErr("Wrong password.");
      else if (er.status === 429) setErr(`Too many attempts. Try again in ${er.body?.retryAfterSeconds ?? "a few"} seconds.`);
      else if (er.status === 503) setErr(`No password is set on the server yet. See the setup notes (${hashVariable}).`);
      else setErr(er.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="wrap">
      <form className="login" onSubmit={submit}>
        <div className="brand"><i aria-hidden="true" />{brand}</div>
        <h2 style={{ marginTop: 18 }}>Sign in</h2>
        {!passwordConfigured && <p className="sub">The server has no password configured, so nobody can sign in yet.</p>}
        <label htmlFor="pw">Password</label>
        <input id="pw" type="password" autoComplete="current-password" autoFocus value={pw} onChange={(e) => setPw(e.target.value)} />
        <div className="err" role="alert">{err}</div>
        <div style={{ marginTop: 14 }}><button className="btn pri" type="submit" disabled={busy || pw === ""}>{busy ? "Signing in…" : "Sign in"}</button></div>
      </form>
    </div>
  );
}
