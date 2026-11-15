import { ci, type IconDef } from "./shape";

const C = "Automation";
export const AUTO_ICONS: Record<string, IconDef> = {
  jenkins: { l: "Jenkins", c: C, d: "M12 12L3.5 7.5v9L12 12l8.5 4.5v-9z M10.5 10.5h3v3h-3z" },
  n8n: { l: "n8n workflows", c: C, d: `${ci(4, 12, 2)}${ci(12, 12, 2)}${ci(20, 6, 2)}${ci(20, 18, 2)}M6 12h4 M13.5 10.7l5-3.4 M13.5 13.3l5 3.4` },
  systemd: { l: "systemd service", c: C, d: "M7 4H3.5v16H7 M17 4h3.5v16H17 M11 8l6 4-6 4z M7.5 12h.01" },
  cron: { l: "cron job", c: C, d: "M8.5 3h7L21 8.5v7L15.5 21h-7L3 15.5v-7z M12 7v5l3 2" },
  scheduler: { l: "Job scheduler", c: C, d: "M4 5h16v15H4z M4 10h16 M8 3v4 M16 3v4 M8 14h3 M13 14h3 M8 17h3" },
  ticket: { l: "ServiceNow ticket", c: C, d: "M3 7h18v3a2 2 0 0 0 0 4v3H3v-3a2 2 0 0 0 0-4z M14 8.5v1 M14 11.5v1 M14 14.5v1" },
  cog: { l: "Automation", c: C, d: `${ci(12, 12, 3)}M12 2.5v3 M12 18.5v3 M2.5 12h3 M18.5 12h3 M5.3 5.3l2.1 2.1 M16.6 16.6l2.1 2.1 M5.3 18.7l2.1-2.1 M16.6 7.4l2.1-2.1` },
  agent: { l: "Agent server", c: C, d: "M6 8h12v10H6z M12 4v4 M9 12h.01 M15 12h.01 M9.5 15h5 M3 12v3 M21 12v3" },
};
