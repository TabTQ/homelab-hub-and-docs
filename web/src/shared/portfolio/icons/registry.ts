import type { IconDef } from "./shape";
import { CLOUD_ICONS } from "./cloud";
import { LANG_ICONS } from "./lang";
import { DATA_ICONS } from "./data";
import { PYECO_ICONS } from "./pyeco";
import { AUTO_ICONS } from "./auto";
import { INFRA_ICONS } from "./infra";
import { AI_ICONS } from "./ai";
import { TOOL_ICONS } from "./tools";
import { EMBEDDED_ICONS } from "./embedded";
import { PROJECT_ICONS } from "./projects";

/** Every icon, by key. */
export const ICONS: Record<string, IconDef> = {
  ...CLOUD_ICONS, ...LANG_ICONS, ...DATA_ICONS, ...PYECO_ICONS, ...AUTO_ICONS, ...INFRA_ICONS, ...AI_ICONS, ...TOOL_ICONS, ...EMBEDDED_ICONS, ...PROJECT_ICONS,
};
export type { IconDef };
