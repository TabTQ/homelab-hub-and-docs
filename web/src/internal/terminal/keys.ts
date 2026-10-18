// Key row and sticky-modifier logic for the Terminal page (plan 5.6). Pure functions, tested in keys.test.ts.

/** The control code for Ctrl+<key>, as a terminal sends it; null when Ctrl does not change that key. */
export function ctrlCode(key: string): string | null {
  if (key.length !== 1) return null;
  const c = key.toLowerCase();
  if (c >= "a" && c <= "z") return String.fromCharCode(c.charCodeAt(0) - 96);
  switch (c) {
    case "@": case " ": case "2": return "\x00";
    case "[": case "3": return "\x1b";
    case "\\": case "4": return "\x1c";
    case "]": case "5": return "\x1d";
    case "^": case "6": return "\x1e";
    case "_": case "-": case "7": return "\x1f";
    case "?": case "8": return "\x7f";
    default: return null;
  }
}

export interface Modifiers { ctrl: boolean; alt: boolean }

/**
 * Applies sticky Ctrl/Alt (from the key row) to what the keyboard typed. Ctrl only applies to a single typed character;
 * Alt prefixes ESC, like a terminal's "meta sends escape".
 */
export function applyModifiers(data: string, mods: Modifiers): string {
  let out = data;
  if (mods.ctrl && [...data].length === 1) out = ctrlCode(data) ?? data;
  if (mods.alt) out = "\x1b" + out;
  return out;
}

export interface KeyDef { id: string; label: string; aria: string; sticky?: "ctrl" | "alt" }

export const KEY_ROW: KeyDef[] = [
  { id: "esc", label: "Esc", aria: "Escape" },
  { id: "tab", label: "Tab", aria: "Tab" },
  { id: "ctrl", label: "Ctrl", aria: "Control (applies to the next key)", sticky: "ctrl" },
  { id: "alt", label: "Alt", aria: "Alt (applies to the next key)", sticky: "alt" },
  { id: "left", label: "←", aria: "Left arrow" },
  { id: "up", label: "↑", aria: "Up arrow" },
  { id: "down", label: "↓", aria: "Down arrow" },
  { id: "right", label: "→", aria: "Right arrow" },
  { id: "home", label: "Home", aria: "Home" },
  { id: "end", label: "End", aria: "End" },
  { id: "pgup", label: "PgUp", aria: "Page up" },
  { id: "pgdn", label: "PgDn", aria: "Page down" },
  { id: "pipe", label: "|", aria: "Pipe" },
  { id: "tilde", label: "~", aria: "Tilde" },
  { id: "slash", label: "/", aria: "Slash" },
  { id: "dash", label: "-", aria: "Dash" },
];

/**
 * The bytes a key-row button sends. Arrows, Home and End follow the terminal's cursor-key mode (full-screen programs such
 * as vim and less switch it to "application" mode, where they expect ESC O x instead of ESC [ x).
 */
export function keySequence(id: string, applicationCursor: boolean): string | null {
  const csi = applicationCursor ? "\x1bO" : "\x1b[";
  switch (id) {
    case "esc": return "\x1b";
    case "tab": return "\t";
    case "up": return csi + "A";
    case "down": return csi + "B";
    case "right": return csi + "C";
    case "left": return csi + "D";
    case "home": return csi + "H";
    case "end": return csi + "F";
    case "pgup": return "\x1b[5~";
    case "pgdn": return "\x1b[6~";
    case "pipe": return "|";
    case "tilde": return "~";
    case "slash": return "/";
    case "dash": return "-";
    default: return null;
  }
}
