// The postMessage protocol between a page and a sandboxed webapp frame (docs plan 5.6).

export const MIN_HEIGHT = 150;
export const MAX_HEIGHT = 4000;
export const FALLBACK_HEIGHT = 600;

/**
 * A height report is accepted only from the frame it is about (the source window, since a sandboxed frame has the
 * origin "null" and cannot be told apart by origin), and is clamped. Anything else returns null.
 */
export function acceptHeight(e: { source: unknown; data: unknown }, frameWindow: unknown): number | null {
  if (!frameWindow || e.source !== frameWindow) return null;
  const d = e.data as { type?: unknown; px?: unknown } | null;
  if (!d || typeof d !== "object" || d.type !== "hub:height" || typeof d.px !== "number" || !Number.isFinite(d.px)) return null;
  return Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, Math.round(d.px)));
}

/** The frame's src: the bundle's index.html, with ?theme= when the app asked for the theme. */
export function frameSrc(base: string, theme: "light" | "dark", passTheme: boolean): string {
  const b = base.endsWith("/") ? base : base + "/";
  return passTheme ? `${b}index.html?theme=${theme}` : `${b}index.html`;
}
