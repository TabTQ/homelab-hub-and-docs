// Month and date helpers for the Budget page. English names only (the owner asked for English), no Intl needed.

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

const MONTH_RE = /^(\d{4})-(\d{2})$/;
const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** "2026-09" if valid (2000-01..2100-12), else null. */
export function parseMonth(s: string | null | undefined): string | null {
  const m = MONTH_RE.exec((s ?? "").trim());
  if (!m) return null;
  const y = Number(m[1]), mo = Number(m[2]);
  if (mo < 1 || mo > 12 || y < 2000 || y > 2100) return null;
  return `${m[1]}-${m[2]}`;
}

export function isDate(s: string): boolean {
  const m = DATE_RE.exec(s);
  if (!m) return false;
  const y = Number(m[1]), mo = Number(m[2]), d = Number(m[3]);
  return y >= 2000 && y <= 2100 && mo >= 1 && mo <= 12 && d >= 1 && d <= daysIn(`${m[1]}-${m[2]}`);
}

export function daysIn(month: string): number {
  const [y, m] = month.split("-").map(Number);
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

export function addMonths(month: string, n: number): string {
  const [y, m] = month.split("-").map(Number);
  const total = y * 12 + (m - 1) + n;
  return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, "0")}`;
}

/** The browser's current month. */
export function thisMonth(now: Date = new Date()): string {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
}

export function monthEnd(month: string): string {
  return `${month}-${String(daysIn(month)).padStart(2, "0")}`;
}

/** "2026-09" -> "September 2026" (or "Sep 2026" when short). */
export function monthLabel(month: string, short = false): string {
  const [y, m] = month.split("-").map(Number);
  const name = MONTHS[m - 1];
  return `${short ? name.slice(0, 3) : name} ${y}`;
}

/** "2026-09-01" -> "1 Sep 2026". */
export function dateLabel(date: string): string {
  const [y, m, d] = date.split("-").map(Number);
  return `${d} ${MONTHS[m - 1].slice(0, 3)} ${y}`;
}

/** An expense's date as the list shows it: "01 Sep", or "Sep (no day)" for a month-only entry. */
export function entryDay(date: string, daySet: boolean): string {
  const [, m, d] = date.split("-").map(Number);
  const mon = MONTHS[m - 1].slice(0, 3);
  return daySet ? `${String(d).padStart(2, "0")} ${mon}` : `${mon} (no day)`;
}

/** The server's PDF file name rule: a whole month is budget-2026-09.pdf, anything else budget-FROM_to_TO.pdf. */
export function pdfName(from: string, to: string): string {
  const month = from.slice(0, 7);
  return from.endsWith("-01") && to === monthEnd(month) ? `budget-${month}.pdf` : `budget-${from}_to_${to}.pdf`;
}
