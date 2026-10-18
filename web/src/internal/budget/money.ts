// Money is integer paise everywhere (plan D1). Rupee text is parsed exactly (no floating point) and shown with Indian
// digit grouping (₹1,25,000.50). Hand-written, not Intl: the Java twin (budget/Money.java) is tested with the same vectors.

/** ₹10,00,00,000.00, the largest amount of one expense, recurring item or settlement. */
export const MAX_PAISE = 10_000_000_000;
export const MINUS = "−";

const RUPEES = /^(\d{1,3}(?:,\d{2,3})*|\d+)?(?:\.(\d{0,2}))?$/;

/** Indian grouping: the last three digits, then groups of two ("12345678" -> "1,23,45,678"). */
export function group(digits: string): string {
  if (digits.length <= 3) return digits;
  const head = digits.slice(0, -3);
  const parts: string[] = [];
  let i = head.length % 2;
  if (i === 1) parts.push(head[0]);
  for (; i < head.length; i += 2) parts.push(head.slice(i, i + 2));
  return parts.join(",") + "," + digits.slice(-3);
}

function plain(paise: number): string {
  const rupees = Math.floor(paise / 100);
  const p = paise % 100;
  return group(String(rupees)) + "." + (p < 10 ? "0" : "") + p;
}

/**
 * 12345678 -> "₹1,23,456.78". `sign: true` adds "+" to positive amounts (net columns); `symbol: false` leaves out the ₹.
 * Negative amounts always get a real minus sign.
 */
export function formatRupees(paise: number, opts: { sign?: boolean; symbol?: boolean } = {}): string {
  const neg = paise < 0;
  const body = (opts.symbol === false ? "" : "₹") + plain(Math.abs(paise));
  return neg ? MINUS + body : opts.sign && paise > 0 ? "+" + body : body;
}

/**
 * Rupee text ("1850", "1850.5", "1,850.50", " 12 ") to paise, or null for anything else: empty, negative, letters,
 * exponents, more than two decimals, zero, or more than the cap.
 */
export function parseRupees(text: string): number | null {
  const t = (text ?? "").trim();
  if (t === "" || t === ".") return null;
  const m = RUPEES.exec(t);
  if (!m) return null;
  const whole = (m[1] ?? "0").replace(/,/g, "");
  const frac = m[2] ?? "";
  if (whole.length > 9) return null;
  const paise = Number(whole) * 100 + (frac === "" ? 0 : Number((frac + "0").slice(0, 2)));
  return paise <= 0 || paise > MAX_PAISE ? null : paise;
}

/** Paise to the plain text an amount input shows ("1850.50"). */
export function paiseToInput(paise: number): string {
  return Math.floor(paise / 100) + "." + String(paise % 100).padStart(2, "0");
}

/** The server's equal split (plan D2): leftover paise go one each to the lowest person ids. */
export function splitPaise(amount: number, ids: number[]): { id: number; paise: number }[] {
  const sorted = [...new Set(ids)].sort((a, b) => a - b);
  if (sorted.length === 0) return [];
  const base = Math.floor(amount / sorted.length);
  const extra = amount - base * sorted.length;
  return sorted.map((id, i) => ({ id, paise: base + (i < extra ? 1 : 0) }));
}
