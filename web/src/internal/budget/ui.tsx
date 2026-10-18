import { useState } from "react";
import { api, ApiError } from "../../shared/api";
import { Modal } from "../components/Modal";
import { formatRupees, parseRupees, splitPaise } from "./money";
import { BUDGET, type FieldErrors, type Person, type PersonRef } from "./types";

/** A dialog on desktop, a bottom sheet on phones (CSS: .bud-sheet). */
export function Sheet(props: { title: string; sub?: string; children: React.ReactNode; actions: React.ReactNode; onClose: () => void; wide?: boolean }) {
  return (
    <Modal title={props.title} onClose={props.onClose} actions={props.actions} wide={props.wide} className="bud-sheet">
      {props.sub && <div className="bud-hint">{props.sub}</div>}
      {props.children}
    </Modal>
  );
}

/** What a failed request means for a form: field messages, or one message for the whole form. */
export function describeError(e: unknown): { fields: FieldErrors; message: string | null; unauthorized: boolean } {
  const err = e as ApiError;
  if (err?.status === 401) return { fields: {}, message: null, unauthorized: true };
  const fields: FieldErrors = err?.body?.errors ?? {};
  if (err?.status === 422 && Object.keys(fields).length > 0) {
    return { fields, message: fields._ ?? "Some fields need attention.", unauthorized: false };
  }
  if (err?.status === 409) return { fields, message: err.body?.message ?? "Someone changed this while you were editing. Reload to see the latest.", unauthorized: false };
  if (err?.status === 404) return { fields, message: "That no longer exists. Reload the page.", unauthorized: false };
  return { fields, message: err?.message ?? "Something went wrong.", unauthorized: false };
}

export function FieldError({ text, id }: { text?: string; id?: string }) {
  return text ? <div className="err" id={id} role="alert">{text}</div> : null;
}

/** Amount in rupees: a text input (exact parsing, no float) with a ₹ prefix. */
export function AmountInput({ id, value, onChange, invalid, describedBy }: { id: string; value: string; onChange: (v: string) => void; invalid?: boolean; describedBy?: string }) {
  return (
    <div className="bud-rupee">
      <span aria-hidden="true">₹</span>
      <input id={id} type="text" inputMode="decimal" autoComplete="off" value={value} placeholder="0.00"
        aria-invalid={invalid || undefined} aria-describedby={describedBy} onChange={(e) => onChange(e.target.value)} />
    </div>
  );
}

/** Amount validation shared by the forms (the server checks again). */
export function amountError(text: string): string | null {
  if (text.trim() === "") return "Enter an amount.";
  return parseRupees(text) === null ? "Enter an amount above zero with at most 2 decimals (up to ₹10,00,00,000.00)." : null;
}

/**
 * "Split among" chips with All / None and an inline "+ Add person" that saves the person to the People list and ticks
 * them (plan R4). `choices` are the people offered; archived people only appear when already ticked.
 */
export function PeopleChips({ choices, selected, onChange, onPersonAdded, onUnauthorized, error, id }: {
  choices: PersonRef[]; selected: number[]; onChange: (ids: number[]) => void; onPersonAdded: (p: PersonRef) => void;
  onUnauthorized: () => void; error?: string; id: string;
}) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [addError, setAddError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const toggle = (pid: number) => onChange(selected.includes(pid) ? selected.filter((x) => x !== pid) : [...selected, pid]);
  const save = async () => {
    if (!name.trim()) { setAddError("Enter a name."); return; }
    setBusy(true);
    try {
      const p = await api<Person>(`${BUDGET}/people`, { method: "POST", body: { name: name.trim() } });
      onPersonAdded({ id: p.id, name: p.name, archived: false });
      setName(""); setAdding(false); setAddError(null);
    } catch (e) {
      const d = describeError(e);
      if (d.unauthorized) onUnauthorized(); else setAddError(d.fields.name ?? d.message);
    } finally { setBusy(false); }
  };
  const active = choices.filter((p) => !p.archived || selected.includes(p.id));
  return (
    <div>
      <div className="bud-label-row">
        <span className="bud-label" id={`${id}-label`}>Split among</span>
        <span className="bud-hint">(<button type="button" className="linkbtn" onClick={() => onChange(active.filter((p) => !p.archived).map((p) => p.id))}>All</button>
          {" · "}<button type="button" className="linkbtn" onClick={() => onChange([])}>None</button>)</span>
      </div>
      <div className="bud-chips" role="group" aria-labelledby={`${id}-label`}>
        {active.map((p) => (
          <button key={p.id} type="button" className="bud-chip" aria-pressed={selected.includes(p.id)} onClick={() => toggle(p.id)}>
            {p.name}{p.archived ? " (archived)" : ""}
          </button>
        ))}
        <button type="button" className="bud-chip add" aria-expanded={adding} onClick={() => setAdding(!adding)}>+ Add person</button>
      </div>
      {adding && (
        <div className="bud-inline">
          <label className="sr" htmlFor={`${id}-new`}>New person's name</label>
          <input id={`${id}-new`} type="text" placeholder="New person's name" maxLength={60} value={name} autoFocus
            onChange={(e) => setName(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); save(); } }} />
          <button type="button" className="btn pri" disabled={busy} onClick={save}>Add</button>
        </div>
      )}
      <FieldError text={addError ?? undefined} />
      <FieldError text={error} />
    </div>
  );
}

/** The grey line under a form: "₹1,850.50 ÷ 3 → Anu ₹616.84 · Ravi ₹616.83 · Meera ₹616.83". */
export function SplitPreview({ amountText, ids, people }: { amountText: string; ids: number[]; people: PersonRef[] }) {
  const paise = parseRupees(amountText);
  const name = (id: number) => people.find((p) => p.id === id)?.name ?? `#${id}`;
  const text = !paise || ids.length === 0 ? "Choose at least one person and an amount."
    : `${formatRupees(paise)} ÷ ${ids.length} → ` + splitPaise(paise, ids).map((s) => `${name(s.id)} ${formatRupees(s.paise)}`).join(" · ");
  return <div className="bud-preview" aria-live="polite">{text}</div>;
}
