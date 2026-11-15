import { ICONS } from "./registry";
import { GROUP_RULES, MISC_RULES, PROJECT_TITLE_RULES, PROJECT_TYPE_RULES, SKILL_RULES, norm, type Rule } from "./rules";

export { VhIcon } from "./VhIcon";
export { ICONS } from "./registry";
export type { IconDef } from "./shape";

/** All valid icon keys. */
export const ICON_KEYS: readonly string[] = Object.keys(ICONS);
/** The icon used when nothing matches. */
export const FALLBACK_ICON = "diamond";

export const isIconKey = (key: unknown): key is string => typeof key === "string" && Object.prototype.hasOwnProperty.call(ICONS, key);
/** Human label for a key (the key itself when unknown). */
export const iconLabel = (key: string): string => (isIconKey(key) ? ICONS[key].l : key);

const first = (rules: Rule[], text: string | null | undefined): string | null => {
  if (!text) return null;
  const t = norm(text);
  for (const [re, key] of rules) if (re.test(t)) return key;
  return null;
};

/** Icon for a skill: an explicit key, else a keyword match on the label, else the group icon, else the diamond. */
export function resolveSkillIcon(label: string, groupName?: string, explicit?: string | null): string {
  if (isIconKey(explicit)) return explicit;
  return first(SKILL_RULES, label) ?? first(GROUP_RULES, groupName) ?? FALLBACK_ICON;
}

/** Icon for a project: an explicit key, else the title, else the type, else the diamond. */
export function resolveProjectIcon(title: string, type?: string | null, explicit?: string | null): string {
  if (isIconKey(explicit)) return explicit;
  return first(PROJECT_TITLE_RULES, title) ?? first(PROJECT_TYPE_RULES, type) ?? FALLBACK_ICON;
}

/** Icon for a device, container, service, education type or contact method, by name. */
export function resolveNameIcon(name: string, explicit?: string | null): string {
  if (isIconKey(explicit)) return explicit;
  return first(MISC_RULES, name) ?? FALLBACK_ICON;
}
