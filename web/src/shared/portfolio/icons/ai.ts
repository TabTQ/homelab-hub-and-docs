import type { IconDef } from "./shape";

const C = "AI assisted development";
export const AI_ICONS: Record<string, IconDef> = {
  claude: { l: "Claude Code", c: C, d: "M8 5l-5 7 5 7 M16 5l5 7-5 7 M12 8l1 3 3 1-3 1-1 3-1-3-3-1 3-1z" },
  gemini: { l: "Gemini CLI", c: C, d: "M12 3c0 5 4 9 9 9-5 0-9 4-9 9 0-5-4-9-9-9 5 0 9-4 9-9z" },
  opencode: { l: "OpenCode", c: C, d: "M9 6L3.5 12 9 18 M15 6l5.5 6L15 18 M12 9.5a2.5 2.5 0 1 0 2.5 2.5" },
  chat: { l: "AI chat", c: C, d: "M4 4h16v12h-9l-5 4v-4H4z M12 7v6 M9 10h6" },
  spark: { l: "Spark", c: C, d: "M12 2l2.2 7.8L22 12l-7.8 2.2L12 22l-2.2-7.8L2 12l7.8-2.2z" },
};
