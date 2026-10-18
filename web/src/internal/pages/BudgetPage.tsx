import { useCallback, useEffect, useState } from "react";
import { api } from "../../shared/api";
import { useToast } from "../../shared/components/Toast";
import { addMonths, dateLabel, entryDay, monthLabel, parseMonth, thisMonth } from "../budget/dates";
import { ExpenseSheet } from "../budget/ExpenseSheet";
import { formatRupees } from "../budget/money";
import { ExportDialog, PeoplePanel, RecurringPanel, SettleDialog } from "../budget/Panels";
import { BUDGET, type Expense, type MonthView, type PersonRef, type Transfer } from "../budget/types";
import { describeError } from "../budget/ui";

// The Budget page (plan 5.7): shared monthly expenses. Loaded lazily; it makes requests only when the owner acts:
// no polling, no timers, no live connection.

type Dialog =
  | { kind: "expense"; expense?: Expense }
  | { kind: "people" }
  | { kind: "recurring" }
  | { kind: "export" }
  | { kind: "settle"; transfer: Transfer };

/** `#/budget/2026-09` -> "2026-09"; anything else -> the current month. */
function monthFromHash(): string {
  const m = /^#\/budget\/(\d{4}-\d{2})/.exec(location.hash);
  return (m && parseMonth(m[1])) || thisMonth();
}

const num = (paise: number, sign = false) => formatRupees(paise, { sign, symbol: false });
const tone = (paise: number) => (paise > 0 ? "bud-pos" : paise < 0 ? "bud-neg" : "bud-muted");

export default function BudgetPage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const toast = useToast();
  const [month, setMonth] = useState(monthFromHash);
  const [view, setView] = useState<MonthView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [pickText, setPickText] = useState(month);

  useEffect(() => { const h = () => setMonth(monthFromHash()); window.addEventListener("hashchange", h); return () => window.removeEventListener("hashchange", h); }, []);
  useEffect(() => setPickText(month), [month]);

  const load = useCallback(() => {
    api<MonthView>(`${BUDGET}/months/${month}/open`, { method: "POST" })
      .then((v) => { if (v.month === month) { setView(v); setError(null); } })
      .catch((e) => { const d = describeError(e); if (d.unauthorized) onUnauthorized(); else setError(d.fields.month ?? d.message); });
  }, [month, onUnauthorized]);
  useEffect(load, [load]);

  const go = (m: string) => { if (parseMonth(m)) location.hash = `#/budget/${m}`; };
  const close = () => setDialog(null);
  const saved = (msg: string) => { setDialog(null); toast(msg); load(); };

  const title = monthLabel(month);
  const monthBar = (
    <div className="bud-monthbar">
      <button className="iconbtn bud-arrow" aria-label="Previous month" onClick={() => go(addMonths(month, -1))}>‹</button>
      <h1 className="bud-title"><span className="bud-long">{title}</span><span className="bud-short">{monthLabel(month, true)}</span></h1>
      <button className="iconbtn bud-arrow" aria-label="Next month" onClick={() => go(addMonths(month, 1))}>›</button>
      <input className="bud-pick" type="month" aria-label="Pick a month" value={pickText} pattern="\d{4}-\d{2}" placeholder="YYYY-MM"
        onChange={(e) => { setPickText(e.target.value); if (parseMonth(e.target.value)) go(e.target.value); }} />
      <span className="grow" />
      <div className="bud-actions">
        <button className="btn" onClick={() => setDialog({ kind: "people" })}>People</button>
        <button className="btn" onClick={() => setDialog({ kind: "recurring" })}>Recurring</button>
        <button className="btn" onClick={() => setDialog({ kind: "export" })} disabled={!view}>Export PDF</button>
        <button className="btn pri" onClick={() => setDialog({ kind: "expense" })} disabled={!view || view.people.every((p) => p.archived)}>+ Add expense</button>
      </div>
    </div>
  );

  if (error && !view) {
    return <>{monthBar}<div className="banner bad" role="alert">{error} <button className="btn" onClick={load}>Try again</button></div></>;
  }
  if (!view || view.month !== month) return <>{monthBar}<p className="sub">Loading {title}…</p></>;

  const people = view.people;
  const name = (id: number) => people.find((p) => p.id === id)?.name ?? `#${id}`;

  return (
    <div className="bud">
      {monthBar}
      {error && <div className="banner bad" role="alert">{error}</div>}
      {people.length === 0 ? (
        <div className="card bud-card">
          <h2>Start with the people</h2>
          <p className="sub">Add everyone who shares expenses. They appear in every form, and you can add more at any time.</p>
          <button className="btn pri" onClick={() => setDialog({ kind: "people" })}>Add people</button>
        </div>
      ) : (
        <div className="bud-cols">
          <div className="bud-stack">
            <TransfersCard view={view} name={name} onMarkPaid={(t) => setDialog({ kind: "settle", transfer: t })} />
            <PeopleCard view={view} name={name} />
            {view.settlements.length > 0 && <SettlementsCard view={view} name={name} onUnauthorized={onUnauthorized} onChanged={saved} />}
            <HistoryCard view={view} />
          </div>
          <ExpensesCard view={view} name={name} month={month} onEdit={(e) => setDialog({ kind: "expense", expense: e })} onAdd={() => setDialog({ kind: "expense" })} />
        </div>
      )}
      {dialog?.kind === "expense" && <ExpenseSheet key={dialog.expense?.id ?? "new"} month={month} people={people} expense={dialog.expense}
        onClose={(peopleAdded) => { close(); if (peopleAdded) load(); }} onSaved={saved} onUnauthorized={onUnauthorized} />}
      {dialog?.kind === "people" && <PeoplePanel onClose={(changed) => { close(); if (changed) load(); }} onUnauthorized={onUnauthorized} />}
      {dialog?.kind === "recurring" && <RecurringPanel people={people} month={month} onClose={(changed) => { close(); if (changed) load(); }} onUnauthorized={onUnauthorized} />}
      {dialog?.kind === "export" && <ExportDialog month={month} today={view.today} onClose={close} onUnauthorized={onUnauthorized} />}
      {dialog?.kind === "settle" && <SettleDialog transfer={dialog.transfer} people={people} today={view.today} month={month} onClose={close} onSaved={saved} onUnauthorized={onUnauthorized} />}
    </div>
  );
}

// ---------------------------------------------------------------- cards

function TransfersCard({ view, name, onMarkPaid }: { view: MonthView; name: (id: number) => string; onMarkPaid: (t: Transfer) => void }) {
  const s = view.statement;
  const n = s.transfers.length;
  return (
    <section className="card bud-card" aria-labelledby="bud-h-who">
      <header><h2 className="tape" id="bud-h-who">Who pays whom</h2>{n > 0 && <span className="bud-hint">{n} payment{n === 1 ? "" : "s"} settle{n === 1 ? "s" : ""} everything</span>}</header>
      {n === 0 && <p className="none">Nobody owes anything.</p>}
      {s.transfers.map((t) => (
        <div className="bud-xfer" key={`${t.fromId}-${t.toId}`}>
          <div className="bud-grow"><b>{name(t.fromId)}</b> pays <b>{name(t.toId)}</b></div>
          <div className="bud-num bud-amt">{formatRupees(t.paise)}</div>
          <button className="btn sm pri" aria-label={`Mark paid: ${name(t.fromId)} pays ${name(t.toId)}`} onClick={() => onMarkPaid(t)}>Mark paid</button>
        </div>
      ))}
      {s.openingTransfers.length > 0 && (
        <p className="bud-hint bud-carried">Carried in from before: {s.openingTransfers.map((t) => `${name(t.fromId)} owes ${name(t.toId)} ${formatRupees(t.paise)}`).join("; ")}. Already included above.</p>
      )}
    </section>
  );
}

function PeopleCard({ view, name }: { view: MonthView; name: (id: number) => string }) {
  const s = view.statement;
  const settled = s.hasSettlements;
  return (
    <section className="card bud-card" aria-labelledby="bud-h-people">
      <header><h2 className="tape" id="bud-h-people">People</h2><span className="bud-hint">+ is owed money · − owes</span></header>
      <div className="bud-scrollx bud-wide">
        <table className="bud-table bud-compact">
          <thead><tr><th>Person</th><th className="bud-num">Paid</th><th className="bud-num">Share</th><th className="bud-num">This month</th>
            <th className="bud-num">Carried in</th>{settled && <th className="bud-num">Settled</th>}<th className="bud-num">To settle</th></tr></thead>
          <tbody>
            {s.rows.map((r) => (
              <tr key={r.personId}>
                <td>{name(r.personId)}</td>
                <td className="bud-num">{num(r.paidPaise)}</td>
                <td className="bud-num">{num(r.sharePaise)}</td>
                <td className={`bud-num ${tone(r.periodNetPaise)}`}>{num(r.periodNetPaise, true)}</td>
                <td className={`bud-num ${tone(r.carriedInPaise)}`}>{num(r.carriedInPaise, true)}</td>
                {settled && <td className={`bud-num ${tone(r.settledPaise)}`}>{num(r.settledPaise, true)}</td>}
                <td className={`bud-num ${tone(r.toSettlePaise)}`}><b>{num(r.toSettlePaise, true)}</b></td>
              </tr>
            ))}
          </tbody>
          <tfoot><tr><td>Total</td><td className="bud-num">{formatRupees(s.totals.paidPaise)}</td><td className="bud-num">{formatRupees(s.totals.sharePaise)}</td>
            <td className="bud-num">{num(s.totals.periodNetPaise)}</td><td className="bud-num">{num(s.totals.carriedInPaise)}</td>
            {settled && <td className="bud-num">{num(s.totals.settledPaise)}</td>}<td className="bud-num">{num(s.totals.toSettlePaise)}</td></tr></tfoot>
        </table>
      </div>
      {/* phones: one compact row per person */}
      <ul className="bud-narrow bud-plist">
        {s.rows.map((r) => (
          <li key={r.personId}>
            <span className="bud-grow"><b>{name(r.personId)}</b>
              <span className="bud-hint bud-mono">{num(r.paidPaise)} paid · {num(r.sharePaise)} share{r.carriedInPaise ? ` · ${num(r.carriedInPaise, true)} carried in` : ""}{r.settledPaise ? ` · ${num(r.settledPaise, true)} settled` : ""}</span></span>
            <b className={`bud-num ${tone(r.toSettlePaise)}`}>{num(r.toSettlePaise, true)}</b>
          </li>
        ))}
      </ul>
    </section>
  );
}

function SettlementsCard({ view, name, onChanged, onUnauthorized }: { view: MonthView; name: (id: number) => string; onChanged: (m: string) => void; onUnauthorized: () => void }) {
  const [confirm, setConfirm] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const remove = (id: number) => api(`${BUDGET}/settlements/${id}`, { method: "DELETE" }).then(() => onChanged("Payment removed."))
    .catch((e) => { const d = describeError(e); if (d.unauthorized) onUnauthorized(); else setError(d.message); });
  return (
    <section className="card bud-card" aria-labelledby="bud-h-settled">
      <header><h2 className="tape" id="bud-h-settled">Settlements</h2><span className="bud-hint">payments marked as paid in {monthLabel(view.month, true)}</span></header>
      {error && <div className="err" role="alert">{error}</div>}
      <ul className="bud-list">
        {view.settlements.map((x) => (
          <li key={x.id} className="bud-person">
            <span className="bud-grow"><span className="bud-mono">{dateLabel(x.date)}</span> · <b>{name(x.fromId)}</b> paid <b>{name(x.toId)}</b>
              {x.note && <span className="bud-hint"> · {x.note}</span>}</span>
            <span className="bud-num">{formatRupees(x.paise)}</span>
            {confirm === x.id
              ? <><button className="btn sm danger" onClick={() => remove(x.id)}>Remove</button><button className="btn sm" onClick={() => setConfirm(null)}>Keep</button></>
              : <button className="btn sm" aria-label={`Delete the payment of ${formatRupees(x.paise)} from ${name(x.fromId)}`} onClick={() => setConfirm(x.id)}>Delete</button>}
          </li>
        ))}
      </ul>
    </section>
  );
}

function HistoryCard({ view }: { view: MonthView }) {
  const months = view.history;
  if (months.length === 0 || months.every((m) => m.totalPaise === 0)) return null;
  const ids = new Set<number>();
  months.forEach((m) => Object.keys(m.paidPaise).forEach((k) => ids.add(Number(k))));
  const cols: PersonRef[] = view.people.filter((p) => ids.has(p.id) || !p.archived);
  return (
    <section className="card bud-card" aria-labelledby="bud-h-history">
      <header><h2 className="tape" id="bud-h-history">What everyone paid each month</h2></header>
      <div className="bud-scrollx">
        <table className="bud-table">
          <thead><tr><th>Month</th>{cols.map((p) => <th key={p.id} className="bud-num">{p.name}</th>)}<th className="bud-num">Total</th></tr></thead>
          <tbody>
            {months.map((m) => (
              <tr key={m.month}>
                <td>{m.month === view.month ? <b>{monthLabel(m.month, true)}</b> : monthLabel(m.month, true)}</td>
                {cols.map((p) => <td key={p.id} className="bud-num">{num(m.paidPaise[String(p.id)] ?? 0)}</td>)}
                <td className="bud-num">{m.month === view.month ? <b>{num(m.totalPaise)}</b> : num(m.totalPaise)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function ExpensesCard({ view, name, month, onEdit, onAdd }: { view: MonthView; name: (id: number) => string; month: string; onEdit: (e: Expense) => void; onAdd: () => void }) {
  const active = view.people.filter((p) => !p.archived);
  const split = (e: Expense) => {
    const ids = e.shares.map((s) => s.personId);
    return ids.length === active.length && ids.length > 1 && active.every((p) => ids.includes(p.id)) ? `All ${ids.length}` : ids.map(name).join(", ");
  };
  const s = view.statement;
  return (
    <section className="card bud-card" aria-labelledby="bud-h-expenses">
      <header><h2 className="tape" id="bud-h-expenses">Expenses · {monthLabel(month).split(" ")[0]}</h2>
        <span className="bud-hint">{s.expenseCount} entr{s.expenseCount === 1 ? "y" : "ies"} · {formatRupees(s.expenseTotalPaise)}</span></header>
      {view.expenses.length === 0 ? (
        <p className="none">No expenses in {monthLabel(month)} yet. <button className="linkbtn" onClick={onAdd}>Add one</button>.</p>
      ) : (
        <>
          <div className="bud-scrollx bud-wide">
            <table className="bud-table bud-compact">
              <thead><tr><th>Date</th><th>Payment</th><th>Paid by</th><th>Split among</th><th className="bud-num">Amount</th><th><span className="sr">Edit</span></th></tr></thead>
              <tbody>
                {view.expenses.map((e) => (
                  <tr key={e.id}>
                    <td className="bud-mono bud-nowrap">{e.daySet ? entryDay(e.date, true) : <>{entryDay(e.date, true).slice(3)}<span className="bud-pill grey">no day</span></>}</td>
                    <td>{e.name}{e.recurringId !== null && <span className="bud-pill">recurring</span>}</td>
                    <td>{name(e.paidBy)}</td>
                    <td>{split(e)}</td>
                    <td className="bud-num">{formatRupees(e.amountPaise)}</td>
                    <td><button className="btn sm" aria-label={`Edit ${e.name}`} onClick={() => onEdit(e)}>Edit</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {/* phones: two-line rows, tap to edit */}
          <ul className="bud-narrow bud-entries">
            {view.expenses.map((e) => (
              <li key={e.id}>
                <button type="button" onClick={() => onEdit(e)} aria-label={`Edit ${e.name}`}>
                  <span className="bud-t">{e.name}{e.recurringId !== null && <span className="bud-pill">recurring</span>}</span>
                  <span className="bud-num">{formatRupees(e.amountPaise)}</span>
                  <span className="bud-m">{entryDay(e.date, e.daySet)} · {name(e.paidBy)} paid · {split(e)}</span>
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
