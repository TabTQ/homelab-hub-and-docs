import { ci, type IconDef } from "./shape";

const C = "Projects and general";
export const PROJECT_ICONS: Record<string, IconDef> = {
  trading: { l: "Trading bot", c: C, d: "M6 4v3 M6 15v3 M4.5 7h3v8h-3z M12 7v3 M12 18v3 M10.5 10h3v8h-3z M18 3v3 M18 13v3 M16.5 6h3v7h-3z" },
  ingest: { l: "File ingestion", c: C, d: "M5 3h9l4 4v5 M14 3v4h4 M5 3v18h7 M17 14v7 M14.5 18.5L17 21l2.5-2.5" },
  sprout: { l: "Greenhouse", c: C, d: "M12 21v-9 M12 12c0-4-3-6-7-6 0 4 3 6 7 6z M12 14c0-3 2-5 6-5 0 3-2 5-6 5z M7 21h10" },
  hub: { l: "Homelab Hub", c: C, d: `${ci(12, 12, 2.5)}M12 3l2 2-2 2-2-2z M12 17l2 2-2 2-2-2z M3 12l2-2 2 2-2 2z M17 12l2-2 2 2-2 2z` },
  homelab: { l: "Homelab", c: C, d: "M3 11l9-8 9 8 M5 9.5V21h14V9.5 M9 14h6 M9 17.5h6" },
  window: { l: "Web app", c: C, d: "M3 4h18v16H3z M3 9h18 M6 6.5h.01 M9 6.5h.01 M7 13h6 M7 16h4" },
  gauge: { l: "Monitoring", c: C, d: "M3 16a9 9 0 0 1 18 0 M12 16l3.5-5 M7 20h10" },
  diamond: { l: "Generic", c: C, d: "M12 2.5l9.5 9.5-9.5 9.5L2.5 12z M12 8l4 4-4 4-4-4z" },
  cert: { l: "Certification", c: C, d: "M12 3a6 6 0 1 1 0 12 6 6 0 0 1 0-12z M8.5 14l-2 7 5.5-3 5.5 3-2-7 M9.5 9l1.8 1.8L15 7.5" },
  cap: { l: "Degree", c: C, d: "M2 9l10-5 10 5-10 5z M6 11.5V16c2 2.5 10 2.5 12 0v-4.5 M22 9v6" },
  book: { l: "Publication", c: C, d: "M12 6c-2-2-5-2.5-8-2v14c3-.5 6 0 8 2 2-2 5-2.5 8-2V4c-3-.5-6 0-8 2z M12 6v14" },
  email: { l: "Email", c: C, d: "M3 5h18v14H3z M3 6l9 7 9-7" },
  phone: { l: "Phone", c: C, d: "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4c0 1-1 2-2 2C10 21 3 14 3 6c0-1 1-2 2-2z" },
  github: { l: "GitHub", c: C, d: "M5 4l3.5 3h7L19 4v7c0 5-3 9-7 9s-7-4-7-9z M9.5 11h.01 M14.5 11h.01 M10.5 15h3" },
  linkedin: { l: "LinkedIn", c: C, d: "M3 3h18v18H3z M8 10v7 M8 7h.01 M12 17v-7 M12 13c0-3 5-3 5 0v4" },
  globe: { l: "Website", c: C, d: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M3 12h18 M12 3c-3 3-3 15 0 18 M12 3c3 3 3 15 0 18" },
  pin: { l: "Location", c: C, d: "M12 21c-4-5-6-8-6-11a6 6 0 0 1 12 0c0 3-2 6-6 11z M12 8.5h.01" },
};
