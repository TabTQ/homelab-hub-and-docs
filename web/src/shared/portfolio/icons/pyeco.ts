import type { IconDef } from "./shape";

const C = "Python ecosystem";
export const PYECO_ICONS: Record<string, IconDef> = {
  plug: { l: "Connection pool", c: C, d: "M9 3v5 M15 3v5 M6 8h12v4a6 6 0 0 1-12 0z M12 18v3" },
  cloudcode: { l: "Cloud SDK", c: C, d: "M6.5 18a4 4 0 0 1-.4-8 6 6 0 0 1 11.6-1.4A4.5 4.5 0 0 1 17.5 18z M10 11.5l-2 2 2 2 M14 11.5l2 2-2 2" },
  threads: { l: "Concurrency", c: C, d: "M3 6h14 M14 3l3 3-3 3 M3 12h18 M18 9l3 3-3 3 M3 18h11 M11 15l3 3-3 3" },
  flag: { l: "Command line flags", c: C, d: "M5 21V4 M5 4h13l-3 4.5 3 4.5H5" },
  config: { l: "Configuration", c: C, d: "M4 7h9 M17 7h3 M13 5v4 M4 17h3 M11 17h9 M7 15v4" },
  lock: { l: "Encryption", c: C, d: "M6 11h12v10H6z M8.5 11V8a3.5 3.5 0 0 1 7 0v3 M12 15v3" },
  bars: { l: "Pandas", c: C, d: "M4 20h16 M6 20v-7h3v7 M11 20V5h3v15 M16 20v-10h3v10" },
  cube: { l: "NumPy", c: C, d: "M12 3l8 4.5v9L12 21l-8-4.5v-9z M4 7.5l8 4.5 8-4.5 M12 12v9 M8 5.25l8 4.5 M16 5.25l-8 4.5" },
};
