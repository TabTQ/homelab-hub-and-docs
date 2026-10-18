export type ThemeChoice = "system" | "light" | "dark";
const KEY = "hub-theme";

export function loadTheme(): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY);
    return v === "light" || v === "dark" ? v : "system";
  } catch {
    return "system";
  }
}

export function applyTheme(t: ThemeChoice): void {
  const root = document.documentElement;
  if (t === "system") root.removeAttribute("data-theme"); else root.dataset.theme = t;
  try { localStorage.setItem(KEY, t); } catch { /* private window: the choice just is not remembered */ }
}

export function nextTheme(t: ThemeChoice): ThemeChoice {
  return t === "system" ? "light" : t === "light" ? "dark" : "system";
}
