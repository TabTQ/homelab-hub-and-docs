import type { IconDef } from "./shape";

const C = "Languages and databases";
const CYL = "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6";
export const LANG_ICONS: Record<string, IconDef> = {
  python: { l: "Python", c: C, d: "M8 7.5V6a3 3 0 0 1 3-3h2a3 3 0 0 1 3 3v3.5a2 2 0 0 1-2 2H9a3 3 0 0 0-3 3V16 M16 16.5V18a3 3 0 0 1-3 3h-2a3 3 0 0 1-3-3v-3.5a2 2 0 0 1 2-2h5a3 3 0 0 0 3-3V8 M11 6h.01 M13 18h.01" },
  sql: { l: "SQL", c: C, d: `${CYL} M9.5 12l2.5 2-2.5 2 M13.5 16.5h3` },
  database: { l: "Database", c: C, d: `${CYL} M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3` },
  plpgsql: { l: "PL/pgSQL", c: C, d: "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z M4 6v4 M20 6v4 M9 14l-3 3 3 3 M15 14l3 3-3 3 M13 13l-2 8" },
  sqlproc: { l: "Stored procedures", c: C, d: "M8 4c-2 0-2.5 1-2.5 2.5v2c0 1.5-.5 2-2 3.5 1.5 1.5 2 2 2 3.5v2C5.5 19 6 20 8 20 M16 4c2 0 2.5 1 2.5 2.5v2c0 1.5.5 2 2 3.5-1.5 1.5-2 2-2 3.5v2c0 1.5-.5 2.5-2.5 2.5 M12 12h.01" },
  bash: { l: "Bash", c: C, d: "M3 5h18v14H3z M7 10l3 2-3 2 M12 15h5" },
  java: { l: "Java", c: C, d: "M5 11h12v5a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4z M17 12h1.5a2.5 2.5 0 0 1 0 5H17 M8.5 3c-1 1.5 1 2.5 0 4 M12.5 3c-1 1.5 1 2.5 0 4 M5 21.5h12" },
  cpp: { l: "C++", c: C, d: "M12 2.5l8.5 5v9L12 21.5l-8.5-5v-9z M10.5 9.2a3.2 3.2 0 1 0 0 5.6 M14 12h3.5 M15.75 10.2v3.6" },
  code: { l: "Code", c: C, d: "M8 7l-5 5 5 5 M16 7l5 5-5 5 M13.5 5l-3 14" },
};
