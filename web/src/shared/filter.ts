// The portfolio filter (skills + free text). Pure functions, mirrored from the approved mockup, so they are easy to test.
import type { Device, Portfolio } from "./types";

export type Mode = "all" | "any";
export interface FilterState { picked: Set<string>; mode: Mode; q: string }

export const tokens = (q: string): string[] => q.toLowerCase().split(/\s+/).filter(Boolean);
export const isActive = (f: FilterState): boolean => f.picked.size > 0 || tokens(f.q).length > 0;

export function skillsOk(f: FilterState, skills: string[]): boolean {
  if (f.picked.size === 0) return true;
  const p = [...f.picked];
  return f.mode === "all" ? p.every((s) => skills.includes(s)) : p.some((s) => skills.includes(s));
}
export const textOk = (f: FilterState, t: string): boolean => tokens(f.q).every((k) => t.includes(k));
export const matches = (f: FilterState, e: { skills: string[]; t: string }): boolean => skillsOk(f, e.skills) && textOk(f, e.t);

/** A device matches if it, or anything below it, matches; the children shown are only the matching ones. */
export function deviceKids(f: FilterState, d: Device) {
  return { containers: d.containers.filter((x) => matches(f, x)), services: d.services.filter((x) => matches(f, x)), network: d.network.filter((x) => matches(f, x)) };
}
export function deviceMatches(f: FilterState, d: Device): boolean {
  const k = deviceKids(f, d);
  return matches(f, d) || k.containers.length > 0 || k.services.length > 0 || k.network.length > 0;
}
export function homelabCount(f: FilterState, devices: Device[]): { hit: number; total: number } {
  let hit = 0, total = 0;
  for (const d of devices) {
    total += 1 + d.containers.length + d.services.length;
    const k = deviceKids(f, d);
    hit += (matches(f, d) ? 1 : 0) + k.containers.length + k.services.length;
  }
  return { hit, total };
}

export function sectionCounts(f: FilterState, p: Portfolio) {
  return {
    experience: p.experience.filter((e) => matches(f, e)).length,
    projects: p.projects.filter((e) => matches(f, e)).length,
    education: p.education.filter((e) => matches(f, e)).length,
    homelab: homelabCount(f, p.homelab).hit,
  };
}

/** Skills that appear next to the current selection, for the "related" highlight in the panel. */
export function relatedSkills(f: FilterState, p: Portfolio): Set<string> {
  const out = new Set<string>();
  p.experience.filter((e) => matches(f, e)).forEach((e) => e.skills.forEach((s) => out.add(s)));
  p.projects.filter((e) => matches(f, e)).forEach((e) => e.skills.forEach((s) => out.add(s)));
  for (const d of p.homelab) {
    if (matches(f, d)) d.skills.forEach((s) => out.add(s));
    const k = deviceKids(f, d);
    k.containers.forEach((c) => c.skills.forEach((s) => out.add(s)));
    k.services.forEach((c) => c.skills.forEach((s) => out.add(s)));
  }
  return out;
}

export function toggle<T>(set: Set<T>, v: T): Set<T> {
  const n = new Set(set);
  if (n.has(v)) n.delete(v); else n.add(v);
  return n;
}
