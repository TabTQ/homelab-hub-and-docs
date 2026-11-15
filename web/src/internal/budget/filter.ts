import type { Expense, PersonRef, Transfer } from "./types";

// The view filter of the Ledger page (mockup "Ledger 6"). Pure functions, so they are easy to test.

export interface LedgerFilter { split: boolean; people: number[] }
export const NO_FILTER: LedgerFilter = { split: false, people: [] };
export const FILTER_KEY = "hub-ledger-filter";

export const isFiltered = (f: LedgerFilter) => f.split || f.people.length > 0;

/** An entry is "split" when more than one person has a share in it. */
export const isSplit = (e: Expense) => e.shares.length > 1;

/** Split only, people (paid it or has a share in it), or both. No filter keeps everything. */
export function matchesFilter(e: Expense, f: LedgerFilter): boolean {
  if (f.split && !isSplit(e)) return false;
  if (f.people.length === 0) return true;
  return f.people.includes(e.paidBy) || e.shares.some((s) => f.people.includes(s.personId));
}

export const filterExpenses = (list: Expense[], f: LedgerFilter) => (isFiltered(f) ? list.filter((e) => matchesFilter(e, f)) : list);

/** Drops people who no longer exist, so an old saved filter cannot hide everything. */
export function cleanFilter(f: LedgerFilter, known: PersonRef[]): LedgerFilter {
  const ids = new Set(known.map((p) => p.id));
  const people = f.people.filter((id) => ids.has(id));
  return people.length === f.people.length ? f : { split: f.split, people };
}

export function parseFilter(text: string | null): LedgerFilter {
  if (!text) return NO_FILTER;
  try {
    const v = JSON.parse(text);
    const people = Array.isArray(v?.people) ? v.people.filter((x: unknown) => Number.isInteger(x)) as number[] : [];
    return { split: v?.split === true, people };
  } catch { return NO_FILTER; }
}

export function loadFilter(): LedgerFilter {
  try { return parseFilter(localStorage.getItem(FILTER_KEY)); } catch { return NO_FILTER; }
}
export function saveFilter(f: LedgerFilter): void {
  try { localStorage.setItem(FILTER_KEY, JSON.stringify(f)); } catch { /* private window: the filter just is not remembered */ }
}

export function toggleId(ids: number[], id: number): number[] {
  return ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id];
}

/** Query string tail for export.pdf: "" when nothing is filtered, otherwise "&split=true&people=1,2". */
export function filterQuery(f: LedgerFilter): string {
  return (f.split ? "&split=true" : "") + (f.people.length ? `&people=${f.people.join(",")}` : "");
}

/** "Split entries · Aisha, Rahul", or "" when nothing is filtered. */
export function filterLabel(f: LedgerFilter, people: PersonRef[]): string {
  const names = f.people.map((id) => people.find((p) => p.id === id)?.name ?? `#${id}`);
  return [f.split ? "Split entries" : "", names.join(", ")].filter(Boolean).join(" · ");
}

// ---- Figures worked out from the filtered entries only (what the page shows while a filter is on).

export interface ViewRow { personId: number; paidPaise: number; sharePaise: number; netPaise: number }

/** Paid, share and net (paid minus share) per person, in the order of `ids`. */
export function viewRows(list: Expense[], ids: number[]): ViewRow[] {
  const paid = new Map<number, number>();
  const share = new Map<number, number>();
  for (const e of list) {
    paid.set(e.paidBy, (paid.get(e.paidBy) ?? 0) + e.amountPaise);
    for (const s of e.shares) share.set(s.personId, (share.get(s.personId) ?? 0) + s.paise);
  }
  return ids.map((personId) => {
    const paidPaise = paid.get(personId) ?? 0, sharePaise = share.get(personId) ?? 0;
    return { personId, paidPaise, sharePaise, netPaise: paidPaise - sharePaise };
  });
}

/** "Who pays whom" for these rows: the same rule as the server (largest debtor pays the largest creditor, ties by id). */
export function viewTransfers(rows: ViewRow[]): Transfer[] {
  const side = (sign: number) => rows.filter((r) => r.netPaise * sign > 0).map((r) => ({ id: r.personId, left: Math.abs(r.netPaise) }));
  const debtors = side(-1), creditors = side(1);
  const order = (a: { id: number; left: number }, b: { id: number; left: number }) => b.left - a.left || a.id - b.id;
  const out: Transfer[] = [];
  while (debtors.length && creditors.length) {
    debtors.sort(order); creditors.sort(order);
    const d = debtors[0], c = creditors[0], paise = Math.min(d.left, c.left);
    out.push({ fromId: d.id, toId: c.id, paise });
    d.left -= paise; c.left -= paise;
    if (d.left === 0) debtors.shift();
    if (c.left === 0) creditors.shift();
  }
  return out;
}

/** The month row of "What everyone paid each month", from the filtered entries. */
export function viewMonthRow(list: Expense[]): { paidPaise: Record<string, number>; totalPaise: number } {
  const paidPaise: Record<string, number> = {};
  let totalPaise = 0;
  for (const e of list) { paidPaise[e.paidBy] = (paidPaise[e.paidBy] ?? 0) + e.amountPaise; totalPaise += e.amountPaise; }
  return { paidPaise, totalPaise };
}
