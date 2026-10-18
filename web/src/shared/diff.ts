// Line diff (longest common subsequence), for the "what will change" view before saving. Inputs are config files, so the
// quadratic table is fine; a hard cap keeps a pathological paste from freezing the tab.
export type DiffLine = { type: "same" | "add" | "del"; text: string };

const MAX_LINES = 3000;

export function diffLines(a: string, b: string): DiffLine[] {
  const x = a.split("\n"), y = b.split("\n");
  if (x.length > MAX_LINES || y.length > MAX_LINES) {
    return [...x.map((t) => ({ type: "del" as const, text: t })), ...y.map((t) => ({ type: "add" as const, text: t }))];
  }
  // trim the common head and tail so the table only covers what changed
  let head = 0;
  while (head < x.length && head < y.length && x[head] === y[head]) head++;
  let tail = 0;
  while (tail < x.length - head && tail < y.length - head && x[x.length - 1 - tail] === y[y.length - 1 - tail]) tail++;
  const mid = { a: x.slice(head, x.length - tail), b: y.slice(head, y.length - tail) };

  const n = mid.a.length, m = mid.b.length;
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) {
    dp[i][j] = mid.a[i] === mid.b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  }
  const out: DiffLine[] = x.slice(0, head).map((t) => ({ type: "same", text: t }));
  let i = 0, j = 0;
  while (i < n && j < m) {
    if (mid.a[i] === mid.b[j]) { out.push({ type: "same", text: mid.a[i] }); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) out.push({ type: "del", text: mid.a[i++] });
    else out.push({ type: "add", text: mid.b[j++] });
  }
  while (i < n) out.push({ type: "del", text: mid.a[i++] });
  while (j < m) out.push({ type: "add", text: mid.b[j++] });
  out.push(...x.slice(x.length - tail).map((t) => ({ type: "same" as const, text: t })));
  return out;
}

export function hasChanges(d: DiffLine[]): boolean {
  return d.some((l) => l.type !== "same");
}
