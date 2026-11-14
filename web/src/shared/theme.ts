export type ThemeChoice = "system" | "light" | "dark";
const KEY = "hub-theme";
const QUERY = "(prefers-color-scheme: light)";

/** Stone (dark) is the default. A stored "system", "light" or "dark" wins. */
export function loadTheme(): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY);
    return v === "light" || v === "dark" || v === "system" ? v : "dark";
  } catch {
    return "dark";
  }
}

let stopWatching: (() => void) | null = null;

function osPrefersLight(): boolean {
  return !!window.matchMedia?.(QUERY).matches;
}

/**
 * Dark (Stone) removes the attribute, light (Parchment) sets data-theme="light",
 * and system sets it only while the OS prefers light. In system mode it also follows OS changes.
 */
export function applyTheme(t: ThemeChoice): void {
  const root = document.documentElement;
  stopWatching?.();
  stopWatching = null;
  const set = (light: boolean) => { if (light) root.dataset.theme = "light"; else root.removeAttribute("data-theme"); };
  if (t === "system") {
    set(osPrefersLight());
    const mq = window.matchMedia?.(QUERY);
    if (mq?.addEventListener) {
      const on = () => set(mq.matches);
      mq.addEventListener("change", on);
      stopWatching = () => mq.removeEventListener("change", on);
    }
  } else {
    set(t === "light");
  }
  try { localStorage.setItem(KEY, t); } catch { /* private window: the choice just is not remembered */ }
}

export function nextTheme(t: ThemeChoice): ThemeChoice {
  return t === "dark" ? "light" : t === "light" ? "system" : "dark";
}

/** Text for the theme button: Stone, Parchment or System. */
export function themeLabel(t: ThemeChoice): string {
  return t === "dark" ? "Stone" : t === "light" ? "Parchment" : "System";
}
