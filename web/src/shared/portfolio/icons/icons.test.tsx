import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { ICON_KEYS, ICONS, VhIcon, iconLabel, resolveNameIcon, resolveProjectIcon, resolveSkillIcon, FALLBACK_ICON } from "./index";
import { INVENTORY_NAMES, INVENTORY_PROJECTS, INVENTORY_SKILLS } from "./inventory.fixture";

describe("icon library", () => {
  it("has no duplicate keys and every icon has a label and path", () => {
    expect(new Set(ICON_KEYS).size).toBe(ICON_KEYS.length);
    for (const k of ICON_KEYS) { expect(iconLabel(k)).not.toBe(k); expect(ICONS[k].d.length).toBeGreaterThan(10); }
  });
  it("keeps path data compact", () => {
    for (const k of ICON_KEYS) expect(ICONS[k].d.length + (ICONS[k].f?.length ?? 0), k).toBeLessThan(520);
  });
  it("renders every icon without throwing", () => {
    for (const k of ICON_KEYS) {
      const html = renderToStaticMarkup(<VhIcon name={k} size={32} className="x" />);
      expect(html).toContain("<svg");
      expect(html).toContain('aria-hidden="true"');
      expect(html).toContain('viewBox="0 0 24 24"');
    }
    expect(renderToStaticMarkup(<VhIcon name="nope" />)).toContain("<path");
  });
  it("resolves every inventory skill to a specific icon", () => {
    const used = new Set<string>();
    for (const [label, group] of INVENTORY_SKILLS) {
      const key = resolveSkillIcon(label);
      expect(ICON_KEYS, label).toContain(key);
      expect(key, label).not.toBe(FALLBACK_ICON);
      expect(resolveSkillIcon(label, group), label).toBe(key);
      used.add(key);
    }
    expect(INVENTORY_SKILLS.length).toBe(76);
    expect(used.size).toBeGreaterThanOrEqual(60);
  });
  it("picks sensible icons for well known labels", () => {
    expect(resolveSkillIcon("Amazon Redshift")).toBe("redshift");
    expect(resolveSkillIcon("Redshift Spectrum")).toBe("spectrum");
    expect(resolveSkillIcon("Python 3")).toBe("python");
    expect(resolveSkillIcon("PL/pgSQL")).toBe("plpgsql");
    expect(resolveSkillIcon("C++")).toBe("cpp");
    expect(resolveSkillIcon("Proxmox Backup Server")).toBe("backup");
  });
  it("honours an explicit key and ignores a bad one", () => {
    expect(resolveSkillIcon("Whatever", "Languages", "lambda")).toBe("lambda");
    expect(resolveSkillIcon("Amazon Redshift", undefined, "nope")).toBe("redshift");
    expect(resolveProjectIcon("Anything", null, "hub")).toBe("hub");
  });
  it("falls back to the group icon, then the diamond", () => {
    expect(resolveSkillIcon("Zork", "Languages")).toBe("code");
    expect(resolveSkillIcon("Zork", "Cloud & Data Warehousing")).toBe("cloud");
    expect(resolveSkillIcon("Zork", "Nothing")).toBe(FALLBACK_ICON);
    expect(resolveSkillIcon("Zork")).toBe(FALLBACK_ICON);
  });
  it("covers projects, devices, services and contact names", () => {
    for (const [title, type] of INVENTORY_PROJECTS) expect(resolveProjectIcon(title, type), title).not.toBe(FALLBACK_ICON);
    for (const n of INVENTORY_NAMES) expect(resolveNameIcon(n), n).not.toBe(FALLBACK_ICON);
    expect(resolveProjectIcon("Amazon Redshift Pipeline Optimization", null)).toBe("redshift");
  });
});
