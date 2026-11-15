import { useEffect, useState } from "react";
import { api } from "../../shared/api";
import { useToast } from "../../shared/components/Toast";
import { copyText, credentialsText } from "./credentials";
import { BUDGET, type Account, type OneTimePassword, type Person } from "./types";
import { describeError, FieldError, Sheet } from "./ui";

// The People panel with logins (mockup "Ledger 4"). Admin only.

interface Secret { name: string; password: string; created: boolean }

const when = (iso: string | null) => {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "";
  const mins = Math.round((Date.now() - d.getTime()) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins} min ago`;
  if (mins < 60 * 24) return `${Math.round(mins / 60)} h ago`;
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short" });
};

function accountLine(a: Account | null | undefined, archived: boolean): React.ReactNode {
  if (!a) return "No login yet";
  const bits: React.ReactNode[] = [];
  if (a.disabled) bits.push(archived ? "Login off (archived)" : "Login disabled");
  if (a.resetRequestedAt) bits.push(<span key="r" className="bud-warn">Asked for a password reset {when(a.resetRequestedAt)}</span>);
  if (a.mustChange && !a.resetRequestedAt) bits.push("must choose a new password");
  bits.push(a.lastLoginAt ? `Last signed in ${when(a.lastLoginAt)}` : "Never signed in");
  bits.push(a.canAdd ? "can add entries" : "view only");
  return bits.flatMap((b, i) => (i ? [" · ", b] : [b]));
}

export function PeoplePanel({ onClose, onUnauthorized }: { onClose: (changed: boolean) => void; onUnauthorized: () => void }) {
  const toast = useToast();
  const [people, setPeople] = useState<Person[] | null>(null);
  const [changed, setChanged] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  const [access, setAccess] = useState<number | null>(null);
  const [confirmReset, setConfirmReset] = useState<number | null>(null);
  const [rename, setRename] = useState("");
  const [newName, setNewName] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [secret, setSecret] = useState<Secret | null>(null);

  const load = () => api<Person[]>(`${BUDGET}/people`).then(setPeople).catch((e) => { const d = describeError(e); if (d.unauthorized) onUnauthorized(); else setMessage(d.message); });
  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const fail = (key: string, e: unknown) => {
    const d = describeError(e);
    if (d.unauthorized) { onUnauthorized(); return; }
    setErrors({ [key]: d.fields.name ?? d.fields.archived ?? d.message ?? "Could not save." });
  };
  const update = async (p: Person, name: string, archived: boolean) => {
    try {
      await api(`${BUDGET}/people/${p.id}`, { method: "PUT", body: { name, archived, version: p.version } });
      setErrors({}); setEditing(null); setChanged(true); load();
    } catch (e) { fail(String(p.id), e); }
  };
  const add = async (createLogin: boolean) => {
    if (!newName.trim()) { setErrors({ add: "Enter a name." }); return; }
    try {
      const r = await api<Person & Partial<OneTimePassword>>(`${BUDGET}/people`, { method: "POST", body: createLogin ? { name: newName.trim(), createLogin: true } : { name: newName.trim() } });
      setNewName(""); setErrors({}); setChanged(true); load();
      if (createLogin && r.password) setSecret({ name: r.name, password: r.password, created: true });
    } catch (e) { fail("add", e); }
  };
  // Create or reset a login: the server returns the password once, so show it straight away.
  const issue = async (p: Person, reset: boolean) => {
    try {
      const r = await api<OneTimePassword>(`${BUDGET}/people/${p.id}/account${reset ? "/reset" : ""}`, { method: "POST" });
      setErrors({}); setConfirmReset(null); setChanged(true); load();
      setSecret({ name: p.name, password: r.password, created: !reset });
    } catch (e) { fail(String(p.id), e); }
  };
  const patch = async (p: Person, body: { canAdd?: boolean; disabled?: boolean }) => {
    try {
      await api(`${BUDGET}/people/${p.id}/account`, { method: "PUT", body });
      setErrors({}); setChanged(true); load();
    } catch (e) { fail(String(p.id), e); }
  };
  const hint = (p: Person) => [p.recurringCount ? `${p.recurringCount} recurring` : "", p.expenseCount ? `${p.expenseCount} expense${p.expenseCount === 1 ? "" : "s"}` : ""].filter(Boolean).join(" · ");

  if (secret) {
    const text = credentialsText(secret.name, secret.password);
    return (
      <Sheet title={secret.created ? `Login created for ${secret.name}` : `New password for ${secret.name}`} sub={`Pass these on to ${secret.name}. The password is shown only now.`}
        onClose={() => setSecret(null)}
        actions={<><button className="btn" onClick={async () => toast((await copyText(text)) ? "Copied." : "Could not copy. Select the text and copy it.")}>Copy all</button>
          <span className="bud-grow" /><button className="btn pri" onClick={() => setSecret(null)}>Done</button></>}>
        <div className="bud-secret">
          <div className="bud-cap">Name</div><code>{secret.name}</code>
          <div className="bud-cap">One-time password</div><code data-testid="bud-secret-password">{secret.password}</code>
        </div>
        <p className="bud-hint">{secret.name} must choose a new password at the next sign-in. Access starts as view only; change it under Access.</p>
      </Sheet>
    );
  }

  return (
    <Sheet title="People" sub="Set up once; they appear in “Paid by” and “Split among”. A login lets someone see the Ledger on the member address." onClose={() => onClose(changed)}
      actions={<button className="btn pri" onClick={() => onClose(changed)}>Done</button>}>
      {message && <div className="banner bad" role="alert">{message}</div>}
      {!people ? <p className="bud-hint">Loading…</p> : (
        <ul className="bud-list bud-accts">
          {people.length === 0 && <li className="bud-hint">Nobody yet. Add the people who share expenses.</li>}
          {people.map((p) => {
            const a = p.account ?? null;
            return (
              <li key={p.id} className={`${p.archived ? "bud-muted " : ""}${a?.resetRequestedAt ? "bud-req" : ""}`}>
                {editing === p.id ? (
                  <div className="bud-inline">
                    <label className="sr" htmlFor={`bud-rn-${p.id}`}>New name for {p.name}</label>
                    <input id={`bud-rn-${p.id}`} type="text" maxLength={60} value={rename} autoFocus onChange={(e) => setRename(e.target.value)}
                      onKeyDown={(e) => { if (e.key === "Enter") update(p, rename.trim(), p.archived); if (e.key === "Escape") { e.stopPropagation(); setEditing(null); } }} />
                    <button className="btn pri sm" onClick={() => update(p, rename.trim(), p.archived)}>Save</button>
                    <button className="btn sm" onClick={() => setEditing(null)}>Cancel</button>
                  </div>
                ) : (
                  <div className="bud-person">
                    <span className="bud-grow"><b>{p.name}</b>{p.archived && <span className="bud-pill grey">archived</span>}
                      <span className="bud-hint bud-meta">{accountLine(a, p.archived)}{hint(p) && ` · ${hint(p)}`}</span></span>
                    <span className="bud-btns">
                      {!a && !p.archived && <button className="btn sm" onClick={() => issue(p, false)}>Create login</button>}
                      {a && !p.archived && (confirmReset === p.id
                        ? <><button className="btn sm danger" onClick={() => issue(p, true)}>Reset now</button><button className="btn sm" onClick={() => setConfirmReset(null)}>Keep</button></>
                        : <button className={`btn sm${a.resetRequestedAt ? " pri" : ""}`} onClick={() => setConfirmReset(p.id)}>Reset password</button>)}
                      {a && !p.archived && <button className="btn sm" aria-expanded={access === p.id} onClick={() => setAccess(access === p.id ? null : p.id)}>Access ▾</button>}
                      {!p.archived && <button className="btn sm" onClick={() => { setEditing(p.id); setRename(p.name); setErrors({}); }}>Rename</button>}
                      <button className="btn sm" onClick={() => update(p, p.name, !p.archived)}>{p.archived ? "Restore" : "Archive"}</button>
                    </span>
                  </div>
                )}
                {a && !p.archived && access === p.id && (
                  <div className="bud-access">
                    <div className="pillseg bud-seg" role="group" aria-label={`Access for ${p.name}`}>
                      <button type="button" aria-pressed={!a.canAdd} onClick={() => a.canAdd && patch(p, { canAdd: false })}>View only</button>
                      <button type="button" aria-pressed={a.canAdd} onClick={() => !a.canAdd && patch(p, { canAdd: true })}>Can add entries</button>
                    </div>
                    <button className="btn sm" onClick={() => patch(p, { disabled: !a.disabled })}>{a.disabled ? "Enable login" : "Disable login"}</button>
                  </div>
                )}
                <FieldError text={errors[String(p.id)]} />
              </li>
            );
          })}
        </ul>
      )}
      <div className="bud-inline" style={{ marginTop: 12 }}>
        <label className="sr" htmlFor="bud-new-person">Add a person</label>
        <input id="bud-new-person" type="text" placeholder="Add a person" maxLength={60} value={newName} onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") add(false); }} />
        <button className="btn" onClick={() => add(false)}>Add</button>
        <button className="btn pri" onClick={() => add(true)}>Add and create login</button>
      </div>
      <FieldError text={errors.add} />
      <p className="bud-hint">Archive hides a person from new forms, switches their login off, and keeps their history and any balance. Renaming changes the name everywhere.</p>
    </Sheet>
  );
}
