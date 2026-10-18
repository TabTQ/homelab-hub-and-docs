// Link and text helpers shared by the renderer and the editor. Only absolute http, https and mailto links are ever
// clickable: the same rule as docs-internal's DocSchema (stricter than util.safeHref, which allows relative links).

export function safeHref(href: unknown): string | null {
  if (typeof href !== "string" || href.length > 2000) return null;
  const h = href.trim();
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f]/.test(h)) return null;
  const lower = h.toLowerCase();
  return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") ? h : null;
}

export function isSha(s: unknown): s is string {
  return typeof s === "string" && /^[0-9a-f]{64}$/.test(s);
}

/** Heading text → an id for the "On this page" list. Stable and unique within one page. */
export function slugger(): (text: string) => string {
  const seen = new Map<string, number>();
  return (text) => {
    const base = text.toLowerCase().trim().replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-+|-+$/g, "").slice(0, 60) || "section";
    const n = seen.get(base) ?? 0;
    seen.set(base, n + 1);
    return n ? `${base}-${n + 1}` : base;
  };
}

export function textOf(node: { text?: string; content?: any[] } | undefined): string {
  if (!node) return "";
  if (typeof node.text === "string") return node.text;
  return (node.content ?? []).map((c) => textOf(c)).join("");
}
