import { useEffect, useRef, useState } from "react";
import { api } from "../../shared/api";
import { useToast } from "../../shared/components/Toast";
import { dateLabel, isDate, monthEnd, monthLabel, parseMonth, pdfName } from "./dates";
import { formatRupees, paiseToInput, parseRupees } from "./money";
import { BUDGET, type FieldErrors, type Person, type PersonRef, type Recurring, type StatementView, type Transfer } from "./types";
import { AmountInput, amountError, describeError, FieldError, PeopleChips, Sheet, SplitPreview } from "./ui";

const nameOf = (people: PersonRef[], id: number) => people.find((p) => p.id === id)?.name ?? `#${id}`;

// ---------------------------------------------------------------- People (mockup 5)

export function PeoplePanel({ onClose, onUnauthorized }: { onClose: (changed: boolean) => void; onUnauthorized: () => void }) {
  const [people, setPeople] = useState<Person[] | null>(null);
  const [changed, setChanged] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  const [rename, setRename] = useState("");
  const [newName, setNewName] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);

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
  const add = async () => {
    if (!newName.trim()) { setErrors({ add: "Enter a name." }); return; }
    try {
      await api(`${BUDGET}/people`, { method: "POST", body: { name: newName.trim() } });
      setNewName(""); setErrors({}); setChanged(true); load();
    } catch (e) { fail("add", e); }
  };
  const hint = (p: Person) => [p.recurringCount ? `${p.recurringCount} recurring` : "", p.expenseCount ? `${p.expenseCount} expense${p.expenseCount === 1 ? "" : "s"}` : ""].filter(Boolean).join(" · ");

  return (
    <Sheet title="People" sub="Set up once; they appear in “Paid by” and “Split among”." onClose={() => onClose(changed)}
      actions={<button className="btn pri" onClick={() => onClose(changed)}>Done</button>}>
      {message && <div className="banner bad" role="alert">{message}</div>}
      {!people ? <p className="bud-hint">Loading…</p> : (
        <ul className="bud-list">
          {people.length === 0 && <li className="bud-hint">Nobody yet. Add the people who share expenses.</li>}
          {people.map((p) => (
            <li key={p.id} className={p.archived ? "bud-muted" : undefined}>
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
                    {hint(p) && <span className="bud-hint"> {hint(p)}</span>}</span>
                  {!p.archived && <button className="btn sm" onClick={() => { setEditing(p.id); setRename(p.name); setErrors({}); }}>Rename</button>}
                  <button className="btn sm" onClick={() => update(p, p.name, !p.archived)}>{p.archived ? "Restore" : "Archive"}</button>
                </div>
              )}
              <FieldError text={errors[String(p.id)]} />
            </li>
          ))}
        </ul>
      )}
      <div className="bud-inline" style={{ marginTop: 12 }}>
        <label className="sr" htmlFor="bud-new-person">Add a person</label>
        <input id="bud-new-person" type="text" placeholder="Add a person" maxLength={60} value={newName} onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") add(); }} />
        <button className="btn pri" onClick={add}>Add</button>
      </div>
      <FieldError text={errors.add} />
      <p className="bud-hint">Archive hides a person from new forms but keeps their history and any balance. Renaming changes the name everywhere.</p>
    </Sheet>
  );
}

// ---------------------------------------------------------------- Recurring (mockup 4)

function recurringWhen(r: { day: number | null; startMonth: string; endMonth: string | null }): string {
  const day = r.day ? `day ${r.day}` : "no day";
  return `${day} · ${r.endMonth ? `${monthLabel(r.startMonth, true)} → ${monthLabel(r.endMonth, true)}` : `from ${monthLabel(r.startMonth, true)}`}`;
}

export function RecurringPanel({ people: initialPeople, month, onClose, onUnauthorized }: {
  people: PersonRef[]; month: string; onClose: (changed: boolean) => void; onUnauthorized: () => void;
}) {
  const [people, setPeople] = useState(initialPeople);
  const [list, setList] = useState<Recurring[] | null>(null);
  const [form, setForm] = useState<Recurring | "new" | null>(null);
  const [changed, setChanged] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const load = () => api<Recurring[]>(`${BUDGET}/recurring`).then(setList).catch((e) => { const d = describeError(e); if (d.unauthorized) onUnauthorized(); else setMessage(d.message); });
  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (form) {
    return <RecurringForm people={people} month={month} item={form === "new" ? undefined : form} onUnauthorized={onUnauthorized}
      onPersonAdded={(p) => { setPeople([...people, p]); setChanged(true); }}
      onDone={(saved) => { setForm(null); if (saved) { setChanged(true); load(); } }} />;
  }
  const active = people.filter((p) => !p.archived);
  const splitLabel = (ids: number[]) => ids.length === active.length && active.every((p) => ids.includes(p.id)) ? `All ${ids.length}` : ids.map((id) => nameOf(people, id)).join(", ");
  return (
    <Sheet title="Recurring expenses" sub="Set once; each month gets its entry when you open that month." wide onClose={() => onClose(changed)}
      actions={<><button className="btn" onClick={() => onClose(changed)}>Done</button><button className="btn pri" onClick={() => setForm("new")}>+ New</button></>}>
      {message && <div className="banner bad" role="alert">{message}</div>}
      {!list ? <p className="bud-hint">Loading…</p> : list.length === 0 ? <p className="none">No recurring expenses yet. Add rent, bills or anything that repeats every month.</p> : (
        <div className="bud-scrollx">
          <table className="bud-table">
            <thead><tr><th>Payment</th><th>Paid by</th><th>Split</th><th>When</th><th className="bud-num">Amount</th><th><span className="sr">Edit</span></th></tr></thead>
            <tbody>
              {list.map((r) => (
                <tr key={r.id} className={r.ended ? "bud-muted" : undefined}>
                  <td>{r.name}{r.ended && <span className="bud-pill grey">ended</span>}</td>
                  <td>{nameOf(people, r.paidBy)}</td>
                  <td>{splitLabel(r.splitIds)}</td>
                  <td>{recurringWhen(r)}</td>
                  <td className="bud-num">{formatRupees(r.amountPaise)}</td>
                  <td><button className="btn sm" aria-label={`Edit ${r.name}`} onClick={() => setForm(r)}>Edit</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="bud-hint">Editing changes months whose entries have not been created yet; months you already opened keep their entries. Nothing runs in the background.</p>
    </Sheet>
  );
}

function RecurringForm({ people, month, item, onDone, onPersonAdded, onUnauthorized }: {
  people: PersonRef[]; month: string; item?: Recurring; onDone: (saved: boolean) => void; onPersonAdded: (p: PersonRef) => void; onUnauthorized: () => void;
}) {
  const active = people.filter((p) => !p.archived);
  const [name, setName] = useState(item?.name ?? "");
  const [amount, setAmount] = useState(item ? paiseToInput(item.amountPaise) : "");
  const [paidBy, setPaidBy] = useState<number | "">(item?.paidBy ?? active[0]?.id ?? "");
  const [split, setSplit] = useState<number[]>(item?.splitIds ?? active.map((p) => p.id));
  const [start, setStart] = useState(item?.startMonth ?? month);
  const [end, setEnd] = useState(item?.endMonth ?? "");
  const [day, setDay] = useState(item?.day ? String(item.day) : "");
  const [errors, setErrors] = useState<FieldErrors>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const s = parseMonth(start), e = end.trim() ? parseMonth(end) : null;
  const d = day.trim() ? Number(day) : null;
  const preview = !s ? "" : `Creates an entry each month from ${monthLabel(s, true)}${e ? ` to ${monthLabel(e, true)}` : ", until you end it"}, `
    + (d && Number.isInteger(d) ? `on day ${d}${d > 28 ? " (the last day in shorter months)" : ""}.` : "counted as the 15th (no day).");

  const save = async () => {
    const x: FieldErrors = {};
    if (!name.trim()) x.name = "Payment name is required.";
    const a = amountError(amount);
    if (a) x.amountPaise = a;
    if (paidBy === "") x.paidBy = "Choose who pays.";
    if (split.length === 0) x.splitIds = "Choose at least one person to split with.";
    if (!s) x.startMonth = "Choose a start month (YYYY-MM).";
    if (end.trim() && !e) x.endMonth = "Choose an end month (YYYY-MM) or leave it empty.";
    else if (s && e && e < s) x.endMonth = "The end month cannot be before the start month.";
    if (day.trim() && (!Number.isInteger(d) || d! < 1 || d! > 31)) x.day = "A day between 1 and 31, or empty for the 15th.";
    setErrors(x);
    if (Object.keys(x).length) { setMessage("Some fields need attention."); return; }
    setBusy(true); setMessage(null);
    const body = { name: name.trim(), amountPaise: parseRupees(amount), paidBy, splitIds: split, startMonth: s, endMonth: e, day: d, version: item?.version };
    try {
      await api(item ? `${BUDGET}/recurring/${item.id}` : `${BUDGET}/recurring`, { method: item ? "PUT" : "POST", body });
      onDone(true);
    } catch (err) {
      const r = describeError(err);
      if (r.unauthorized) { onUnauthorized(); return; }
      setErrors(r.fields); setMessage(r.message);
    } finally { setBusy(false); }
  };
  const remove = async () => {
    setBusy(true);
    try { await api(`${BUDGET}/recurring/${item!.id}`, { method: "DELETE" }); onDone(true); }
    catch (err) { const r = describeError(err); if (r.unauthorized) onUnauthorized(); else setMessage(r.message); }
    finally { setBusy(false); }
  };

  const actions = confirmDelete ? (
    <>
      <span className="bud-grow bud-hint">Delete “{item!.name}”? Entries it already created stay.</span>
      <button className="btn" onClick={() => setConfirmDelete(false)}>Keep it</button>
      <button className="btn danger" disabled={busy} onClick={remove}>Delete</button>
    </>
  ) : (
    <>
      {item && <button className="btn bud-left" onClick={() => setConfirmDelete(true)}>Delete</button>}
      <button className="btn" onClick={() => onDone(false)}>Cancel</button>
      <button className="btn pri" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</button>
    </>
  );
  const payers = people.filter((p) => !p.archived || p.id === item?.paidBy);
  return (
    <Sheet title={item ? "Edit recurring expense" : "New recurring expense"} onClose={() => onDone(false)} actions={actions}>
      {message && <div className="banner bad" role="alert">{message}</div>}
      <div className="field">
        <label htmlFor="bud-r-name">Payment name</label>
        <input id="bud-r-name" type="text" maxLength={100} value={name} onChange={(ev) => setName(ev.target.value)} />
        <FieldError text={errors.name} />
      </div>
      <div className="field">
        <label htmlFor="bud-r-amount">Amount</label>
        <AmountInput id="bud-r-amount" value={amount} onChange={setAmount} invalid={!!errors.amountPaise} />
        <FieldError text={errors.amountPaise} />
      </div>
      <div className="field">
        <label htmlFor="bud-r-paid">Paid by</label>
        <select id="bud-r-paid" value={paidBy} onChange={(ev) => setPaidBy(ev.target.value === "" ? "" : Number(ev.target.value))}>
          {paidBy === "" && <option value="">Choose…</option>}
          {payers.map((p) => <option key={p.id} value={p.id}>{p.name}{p.archived ? " (archived)" : ""}</option>)}
        </select>
        <FieldError text={errors.paidBy} />
      </div>
      <div className="field">
        <PeopleChips id="bud-r-split" choices={people} selected={split} onChange={setSplit} error={errors.splitIds} onUnauthorized={onUnauthorized}
          onPersonAdded={(p) => { onPersonAdded(p); setSplit([...split, p.id]); if (paidBy === "") setPaidBy(p.id); }} />
        <SplitPreview amountText={amount} ids={split} people={people} />
      </div>
      <div className="field bud-row">
        <div className="bud-grow">
          <label htmlFor="bud-r-start">Starts</label>
          <input id="bud-r-start" type="month" value={start} pattern="\d{4}-\d{2}" placeholder="YYYY-MM" onChange={(ev) => setStart(ev.target.value)} />
          <FieldError text={errors.startMonth} />
        </div>
        <div className="bud-grow">
          <label htmlFor="bud-r-end">Ends (optional)</label>
          <input id="bud-r-end" type="month" value={end} pattern="\d{4}-\d{2}" placeholder="YYYY-MM" onChange={(ev) => setEnd(ev.target.value)} />
          <FieldError text={errors.endMonth} />
        </div>
      </div>
      <div className="field">
        <label htmlFor="bud-r-day">Day of month (optional)</label>
        <input id="bud-r-day" type="number" min={1} max={31} inputMode="numeric" placeholder="15 if empty" value={day} onChange={(ev) => setDay(ev.target.value)} />
        <FieldError text={errors.day} />
      </div>
      {preview && <div className="bud-preview">{preview}</div>}
    </Sheet>
  );
}

// ---------------------------------------------------------------- Mark paid (R8)

/**
 * `month` is the month on screen. Outside the current month an empty date would mean "today", which lands the payment
 * in another month and leaves this one looking unpaid, so the date starts at the viewed month's last day instead.
 */
export function SettleDialog({ transfer, people, today, month, onClose, onSaved, onUnauthorized }: {
  transfer: Transfer; people: PersonRef[]; today: string; month: string; onClose: () => void; onSaved: (message: string) => void; onUnauthorized: () => void;
}) {
  const current = month === today.slice(0, 7);
  const [amount, setAmount] = useState(paiseToInput(transfer.paise));
  const [date, setDate] = useState(current ? "" : monthEnd(month));
  const [note, setNote] = useState("");
  const [errors, setErrors] = useState<FieldErrors>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const paise = parseRupees(amount);
  const from = nameOf(people, transfer.fromId), to = nameOf(people, transfer.toId);
  const effective = date || (current ? today : monthEnd(month));
  const otherMonth = isDate(effective) && effective.slice(0, 7) !== month ? effective.slice(0, 7) : null;

  const save = async () => {
    const x: FieldErrors = {};
    const a = amountError(amount);
    if (a) x.amountPaise = a;
    if (date && !isDate(date)) x.date = current ? "Pick a valid date, or leave it empty for today." : "Pick a valid date.";
    if (note.length > 200) x.note = "At most 200 characters.";
    setErrors(x);
    if (Object.keys(x).length) return;
    setBusy(true);
    try {
      await api(`${BUDGET}/settlements`, { method: "POST", body: { fromId: transfer.fromId, toId: transfer.toId, amountPaise: paise, date: date || null, month, note: note.trim() || null } });
      onSaved(paise! < transfer.paise ? `Part payment of ${formatRupees(paise!)} recorded.` : "Payment recorded.");
    } catch (e) {
      const d = describeError(e);
      if (d.unauthorized) { onUnauthorized(); return; }
      setErrors(d.fields); setMessage(d.message);
    } finally { setBusy(false); }
  };
  return (
    <Sheet title="Mark paid" sub={`${from} pays ${to} · suggested ${formatRupees(transfer.paise)}`} onClose={onClose}
      actions={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn pri" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</button></>}>
      {message && <div className="banner bad" role="alert">{message}</div>}
      <div className="field">
        <label htmlFor="bud-s-amount">Amount paid</label>
        <AmountInput id="bud-s-amount" value={amount} onChange={setAmount} invalid={!!errors.amountPaise} />
        <div className="help">Change it for a part payment; the rest stays owed and carries over.</div>
        {paise !== null && paise > transfer.paise && <div className="bud-warn" role="status">More than the suggested amount: this will flip the balance.</div>}
        <FieldError text={errors.amountPaise} />
      </div>
      <div className="field">
        <label htmlFor="bud-s-date">Date (optional)</label>
        <input id="bud-s-date" type="date" value={date} placeholder={current ? "Today" : monthEnd(month)} onChange={(e) => setDate(e.target.value)} />
        <div className="help">{current
          ? <>Empty = today ({dateLabel(today)}). The payment counts on this date.</>
          : <>Defaults to the last day of {monthLabel(month)} ({dateLabel(monthEnd(month))}). The payment counts on this date.</>}</div>
        {otherMonth && <div className="bud-warn" role="status">This date is in {monthLabel(otherMonth)}, so the payment counts there, not in {monthLabel(month)}.</div>}
        <FieldError text={errors.date} />
      </div>
      <div className="field">
        <label htmlFor="bud-s-note">Note (optional)</label>
        <input id="bud-s-note" type="text" maxLength={200} value={note} placeholder="e.g. UPI" onChange={(e) => setNote(e.target.value)} />
        <FieldError text={errors.note} />
      </div>
    </Sheet>
  );
}

// ---------------------------------------------------------------- Export (mockup 6, D15)

type Mode = "this" | "month" | "range";

/** Fetches the PDF as a File (so it can be shared); errors carry the server's message. */
async function fetchPdf(from: string, to: string): Promise<File> {
  const res = await fetch(`${BUDGET}/export.pdf?from=${from}&to=${to}`, { credentials: "same-origin", headers: { Accept: "application/pdf, application/json" } });
  if (!res.ok) {
    let msg = `Export failed (HTTP ${res.status}).`;
    try { const b = await res.json(); msg = Object.values(b.errors ?? {})[0] as string ?? b.message ?? msg; } catch { /* not JSON */ }
    const err = new Error(msg) as Error & { status?: number };
    err.status = res.status;
    throw err;
  }
  const blob = await res.blob();
  return new File([blob], pdfName(from, to), { type: "application/pdf" });
}

function download(file: File) {
  const url = URL.createObjectURL(file);
  const a = document.createElement("a");
  a.href = url;
  a.download = file.name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export function ExportDialog({ month, today, onClose, onUnauthorized }: { month: string; today: string; onClose: () => void; onUnauthorized: () => void }) {
  const toast = useToast();
  const [mode, setMode] = useState<Mode>("this");
  const [pick, setPick] = useState(month);
  const [from, setFrom] = useState(`${month}-01`);
  const [to, setTo] = useState(monthEnd(month));
  const [preview, setPreview] = useState<StatementView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState<File | null>(null);
  const current = today.slice(0, 7);

  let range: [string, string] | null = null;
  let rangeError: string | null = null;
  if (mode === "this") range = [`${current}-01`, monthEnd(current)];
  else if (mode === "month") { const p = parseMonth(pick); if (p) range = [`${p}-01`, monthEnd(p)]; else rangeError = "Choose a month (YYYY-MM)."; }
  else if (!isDate(from) || !isDate(to)) rangeError = "Choose both dates.";
  else if (to < from) rangeError = "“To” cannot be before “From”.";
  else range = [from, to];
  const key = range ? range.join("_") : "";

  const seq = useRef(0);
  useEffect(() => {
    setPreview(null); setPending(null);
    if (!range) return;
    const n = ++seq.current;
    const t = window.setTimeout(() => {
      api<StatementView>(`${BUDGET}/statement?from=${range![0]}&to=${range![1]}`)
        .then((v) => { if (n === seq.current) { setPreview(v); setError(null); } })
        .catch((e) => { const d = describeError(e); if (d.unauthorized) onUnauthorized(); else if (n === seq.current) setError(Object.values(d.fields)[0] ?? d.message); });
    }, 250);
    return () => window.clearTimeout(t);
  }, [key]); // eslint-disable-line react-hooks/exhaustive-deps

  const get = async (): Promise<File | null> => {
    if (!range) return null;
    setBusy(true); setError(null);
    try { return await fetchPdf(range[0], range[1]); }
    catch (e) { const err = e as Error & { status?: number }; if (err.status === 401) onUnauthorized(); else setError(err.message); return null; }
    finally { setBusy(false); }
  };
  const share = async (file: File) => {
    const data = { files: [file], title: file.name.replace(/\.pdf$/, "") };
    if (!navigator.canShare?.(data)) { download(file); toast("This browser cannot share files, so the PDF was downloaded."); return; }
    try {
      await navigator.share(data);
      setPending(null);
    } catch (e) {
      const name = (e as DOMException)?.name;
      if (name === "AbortError") return; // the user closed the share sheet
      if (name === "NotAllowedError") { setPending(file); return; } // the tap "expired" while the PDF was fetched
      download(file);
    }
  };

  const people = preview?.people ?? [];
  const s = preview?.statement;
  const line = (ts: Transfer[], verb: string) => ts.map((t) => `${nameOf(people, t.fromId)} ${verb} ${nameOf(people, t.toId)} ${formatRupees(t.paise)}`).join(" · ");
  return (
    <Sheet title="Export PDF" onClose={onClose} actions={(
      <>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={!range || busy} onClick={async () => { const f = await get(); if (f) download(f); }}>Download</button>
        {pending
          ? <button className="btn pri" onClick={() => share(pending)}>Tap again to share</button>
          : <button className="btn pri" disabled={!range || busy} onClick={async () => { const f = await get(); if (f) await share(f); }}>{busy ? "Preparing…" : "Share PDF…"}</button>}
      </>
    )}>
      <div className="field">
        <div className="seg bud-seg" role="group" aria-label="What to export">
          <button type="button" aria-pressed={mode === "this"} onClick={() => setMode("this")}>This month</button>
          <button type="button" aria-pressed={mode === "month"} onClick={() => setMode("month")}>Choose month</button>
          <button type="button" aria-pressed={mode === "range"} onClick={() => setMode("range")}>Choose range</button>
        </div>
      </div>
      {mode === "this" && <p className="bud-hint">{monthLabel(current)} (today's month).</p>}
      {mode === "month" && (
        <div className="field">
          <label htmlFor="bud-e-month">Month</label>
          <input id="bud-e-month" type="month" value={pick} pattern="\d{4}-\d{2}" placeholder="YYYY-MM" onChange={(e) => setPick(e.target.value)} />
        </div>
      )}
      {mode === "range" && (
        <div className="field bud-row">
          <div className="bud-grow"><label htmlFor="bud-e-from">From</label><input id="bud-e-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
          <div className="bud-grow"><label htmlFor="bud-e-to">To</label><input id="bud-e-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
        </div>
      )}
      <FieldError text={rangeError ?? error ?? undefined} />
      {range && (
        <div className="bud-preview" aria-live="polite" data-testid="bud-export-preview">
          {!s ? "Working out the figures…" : (
            <>
              <div><b>Balances before {dateLabel(range[0])}</b> (carried in): {s.openingTransfers.length ? line(s.openingTransfers, "owes") + "." : "nothing carried in."}</div>
              <div><b>In the range:</b> {s.expenseCount} expense{s.expenseCount === 1 ? "" : "s"} · {formatRupees(s.expenseTotalPaise)} · {preview!.settlements.length} settlement{preview!.settlements.length === 1 ? "" : "s"}.</div>
              <div><b>Result:</b> {s.transfers.length ? line(s.transfers, "→") : "Nobody owes anything."}</div>
            </>
          )}
        </div>
      )}
      <p className="bud-hint">Entries with no day count as the 15th. The file is made now and not kept on the server{range ? `: ${pdfName(range[0], range[1])}` : ""}.</p>
    </Sheet>
  );
}
