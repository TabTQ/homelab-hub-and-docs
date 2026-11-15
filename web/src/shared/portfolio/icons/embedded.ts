import type { IconDef } from "./shape";

const C = "Embedded and BI";
export const EMBEDDED_ICONS: Record<string, IconDef> = {
  control: { l: "Control systems", c: C, d: "M2 9h5 M5 7l2 2-2 2 M7 5h8v8H7z M15 9h7 M19 9v9H4.5V9" },
  matlab: { l: "MATLAB", c: C, d: "M3 17c4 0 5-12 9-12s5 12 9 12 M3 17l4 3.5h10l4-3.5 M12 5v15.5 M7.5 12.5v8 M16.5 12.5v8" },
  fuzzy: { l: "Fuzzy logic", c: C, d: "M2 19l5-13h3l5 13 M9 19l5-13h3l5 13 M2 19h20" },
  arduino: { l: "Arduino", c: C, d: "M12 12c-2-3-3.5-4-5.5-4a4 4 0 0 0 0 8c2 0 3.5-1 5.5-4 2-3 3.5-4 5.5-4a4 4 0 0 1 0 8c-2 0-3.5-1-5.5-4z M5 12h3 M6.5 10.5v3 M16 12h3" },
  simulink: { l: "Simulink", c: C, d: "M3 4h8v6H3z M13 14h8v6h-8z M11 7h5v7 M14 12l2 2 2-2" },
  sqlserver: { l: "SQL Server", c: C, d: "M3 4h18v6H3z M3 14h18v6H3z M7 7h.01 M7 17h.01 M11 7h6 M11 17h6" },
  tableau: { l: "Tableau", c: C, d: "M12 2.5v5 M9.5 5h5 M12 16.5v5 M9.5 19h5 M2.5 12h5 M5 9.5v5 M16.5 12h5 M19 9.5v5 M12 10.5v3 M10.5 12h3" },
};
