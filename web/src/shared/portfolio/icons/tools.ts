import { ci, type IconDef } from "./shape";

const C = "Tooling and frameworks";
export const TOOL_ICONS: Record<string, IconDef> = {
  git: { l: "Git", c: C, d: `${ci(6, 5, 2)}${ci(6, 19, 2)}${ci(18, 8, 2)}M6 7v10 M18 10v1c0 3-3 4-7 4.5-3 .4-5 1-5 3` },
  confluence: { l: "Confluence", c: C, d: "M3 9c3-3 6-3 9 0s6 3 9 0 M3 15c3 3 6 3 9 0s6-3 9 0" },
  jira: { l: "Jira", c: C, d: "M4 20l8-6 8 6 M4 14l8-6 8 6 M4 8l8-6 8 6" },
  vscode: { l: "VS Code", c: C, d: "M17 3l4 2v14l-4 2z M17 3L7 12l10 9 M7 12L3.5 9 M7 12l-3.5 3" },
  react: { l: "React", c: C, d: "M12 3.5c4 3 4 14 0 17-4-3-4-14 0-17z M12 12h.01", r: [0, 60, 120] },
  spring: { l: "Spring", c: C, d: "M5 20c0-9 5-16 16-16 0 10-6 16-14 15 M5 20c3-5 6-8 10-10" },
  jsp: { l: "JSP pages", c: C, d: "M6 3h9l4 4v14H6z M15 3v4h4 M10 12l-2 2 2 2 M14 12l2 2-2 2" },
  hibernate: { l: "Hibernate", c: C, d: "M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z M15 4h4l-4 4h4" },
  wrench: { l: "Tooling", c: C, d: "M15 3.5a5 5 0 0 0-4.5 6.7L4 16.7 7.3 20l6.5-6.5A5 5 0 0 0 20.5 9l-3 3-3-.5-.5-3z" },
};
