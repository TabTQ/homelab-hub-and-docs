import { useState } from "react";
import { api } from "../../shared/api";
import { dateLabel, daysIn, isDate, monthLabel, parseMonth } from "./dates";
import { paiseToInput, parseRupees } from "./money";
import { BUDGET, type Expense, type FieldErrors, type PersonRef } from "./types";
import { AmountInput, amountError, describeError, FieldError, PeopleChips, Sheet, SplitPreview } from "./ui";

/** Add or edit one expense (mockup 3). Only the month is required; no day = stored as the 15th, shown "Sep (no day)". */
export function ExpenseSheet({ month, people: initialPeople, expense, onClose, onSaved, onUnauthorized }: {
  month: string; people: PersonRef[]; expense?: Expense; onClose: (peopleAdded: boolean) => void; onSaved: (message: string) => void; onUnauthorized: () => void;
}) {
  const [people, setPeople] = useState(initialPeople);
  const cancel = () => onClose(people.length !== initialPeople.length); // a person added inline should show on the page too
  const active = people.filter((p) => !p.archived);
  const [name, setName] = useState(expense?.name ?? "");
  const [amount, setAmount] = useState(expense ? paiseToInput(expense.amountPaise) : "");
  const startMonth = expense ? expense.date.slice(0, 7) : month;
  const [m, setM] = useState(startMonth);
  const [dayMode, setDayMode] = useState<"none" | "day">(expense?.daySet ? "day" : "none");
  const [date, setDate] = useState(expense?.daySet ? expense.date : `${startMonth}-${String(Math.min(new Date().getDate(), daysIn(startMonth))).padStart(2, "0")}`);
  const [paidBy, setPaidBy] = useState<number | "">(expense?.paidBy ?? active[0]?.id ?? "");
  const [split, setSplit] = useState<number[]>(expense ? expense.shares.map((s) => s.personId) : active.map((p) => p.id));
  const [errors, setErrors] = useState<FieldErrors>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const validMonth = parseMonth(m);
  // Keep the picked day inside the chosen month (31 Aug -> 30 Sep when the month changes).
  const changeMonth = (v: string) => {
    setM(v);
    const pm = parseMonth(v);
    if (pm && !date.startsWith(pm)) {
      const d = Number(date.slice(8, 10)) || 1;
      setDate(`${pm}-${String(Math.min(d, daysIn(pm))).padStart(2, "0")}`);
    }
  };
  const payers = people.filter((p) => !p.archived || p.id === expense?.paidBy);
  const savedAs = !validMonth ? "" : dayMode === "none" ? `Saved as ${dateLabel(`${validMonth}-15`)} and shown as “${monthLabel(validMonth, true).slice(0, 3)} (no day)”.`
    : isDate(date) && date.startsWith(validMonth) ? `Saved as ${dateLabel(date)}.` : "";

  const check = (): FieldErrors => {
    const e: FieldErrors = {};
    if (!name.trim()) e.name = "Payment name is required.";
    else if (name.trim().length > 100) e.name = "At most 100 characters.";
    const a = amountError(amount);
    if (a) e.amountPaise = a;
    if (!validMonth) e.month = "Choose a month (YYYY-MM).";
    else if (dayMode === "day" && (!isDate(date) || !date.startsWith(validMonth))) e.day = `Pick a day in ${monthLabel(validMonth)}.`;
    if (paidBy === "") e.paidBy = "Choose who paid.";
    if (split.length === 0) e.splitIds = "Choose at least one person to split with.";
    return e;
  };

  const save = async () => {
    const e = check();
    setErrors(e);
    if (Object.keys(e).length) { setMessage("Some fields need attention."); return; }
    setBusy(true);
    setMessage(null);
    const body: Record<string, unknown> = {
      name: name.trim(), amountPaise: parseRupees(amount), paidBy, month: validMonth, splitIds: split,
      day: dayMode === "day" ? Number(date.slice(8, 10)) : null,
    };
    try {
      if (expense) await api(`${BUDGET}/expenses/${expense.id}`, { method: "PUT", body: { ...body, version: expense.version } });
      else await api(`${BUDGET}/expenses`, { method: "POST", body });
      onSaved(expense ? "Expense saved." : "Expense added.");
    } catch (x) {
      const d = describeError(x);
      if (d.unauthorized) { onUnauthorized(); return; }
      setErrors(d.fields); setMessage(d.message);
    } finally { setBusy(false); }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await api(`${BUDGET}/expenses/${expense!.id}`, { method: "DELETE" });
      onSaved("Expense deleted.");
    } catch (x) {
      const d = describeError(x);
      if (d.unauthorized) onUnauthorized(); else setMessage(d.message);
    } finally { setBusy(false); }
  };

  const actions = confirmDelete ? (
    <>
      <span className="bud-grow bud-hint">Delete “{expense!.name}”?{expense!.recurringId ? " It will not be created again for this month." : ""}</span>
      <button className="btn" onClick={() => setConfirmDelete(false)}>Keep it</button>
      <button className="btn danger" disabled={busy} onClick={remove}>Delete</button>
    </>
  ) : (
    <>
      {expense && <button className="btn bud-left" onClick={() => setConfirmDelete(true)}>Delete</button>}
      <button className="btn" onClick={cancel}>Cancel</button>
      <button className="btn pri" disabled={busy} onClick={save}>{busy ? "Saving…" : expense ? "Save changes" : "Save expense"}</button>
    </>
  );

  return (
    <Sheet title={expense ? "Edit expense" : "Add expense"} sub={validMonth ? monthLabel(validMonth) : undefined} onClose={cancel} actions={actions}>
      {expense?.recurringId && <p className="bud-hint">Created from a recurring expense. Changes here apply to this month only.</p>}
      {message && <div className="banner bad" role="alert">{message}</div>}
      <div className="field">
        <label htmlFor="bud-x-name">Payment name</label>
        <input id="bud-x-name" type="text" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} aria-invalid={!!errors.name || undefined} />
        <FieldError text={errors.name} />
      </div>
      <div className="field">
        <label htmlFor="bud-x-amount">Amount</label>
        <AmountInput id="bud-x-amount" value={amount} onChange={setAmount} invalid={!!errors.amountPaise} />
        <FieldError text={errors.amountPaise} />
      </div>
      <div className="field">
        <label htmlFor="bud-x-month">When</label>
        <div className="bud-row">
          <input id="bud-x-month" type="month" className="bud-grow" value={m} pattern="\d{4}-\d{2}" placeholder="YYYY-MM"
            onChange={(e) => changeMonth(e.target.value)} aria-invalid={!!errors.month || undefined} />
          <div className="seg" role="group" aria-label="Day or month only">
            <button type="button" aria-pressed={dayMode === "none"} onClick={() => setDayMode("none")}>No day</button>
            <button type="button" aria-pressed={dayMode === "day"} onClick={() => setDayMode("day")}>Pick day</button>
          </div>
        </div>
        {dayMode === "day" && validMonth && (
          <div className="bud-row" style={{ marginTop: 8 }}>
            <label className="sr" htmlFor="bud-x-date">Day</label>
            <input id="bud-x-date" type="date" className="bud-grow" value={date} min={`${validMonth}-01`} max={`${validMonth}-${daysIn(validMonth)}`}
              onChange={(e) => setDate(e.target.value)} aria-invalid={!!errors.day || undefined} />
          </div>
        )}
        {savedAs && <div className="help">{savedAs}</div>}
        <FieldError text={errors.month ?? errors.day} />
      </div>
      <div className="field">
        <label htmlFor="bud-x-paid">Paid by</label>
        <select id="bud-x-paid" value={paidBy} onChange={(e) => setPaidBy(e.target.value === "" ? "" : Number(e.target.value))} aria-invalid={!!errors.paidBy || undefined}>
          {paidBy === "" && <option value="">Choose…</option>}
          {payers.map((p) => <option key={p.id} value={p.id}>{p.name}{p.archived ? " (archived)" : ""}</option>)}
        </select>
        <FieldError text={errors.paidBy} />
      </div>
      <div className="field">
        <PeopleChips id="bud-x-split" choices={people} selected={split} onChange={setSplit} error={errors.splitIds} onUnauthorized={onUnauthorized}
          onPersonAdded={(p) => { setPeople([...people, p]); setSplit([...split, p.id]); if (paidBy === "") setPaidBy(p.id); }} />
        <SplitPreview amountText={amount} ids={split} people={people} />
      </div>
    </Sheet>
  );
}
