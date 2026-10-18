import { describe, expect, it } from "vitest";
import { deviceKids, deviceMatches, homelabCount, isActive, matches, relatedSkills, sectionCounts, textOk, toggle, tokens } from "./filter";
import type { Device, Portfolio } from "./types";

const f = (picked: string[] = [], q = "", mode: "all" | "any" = "all") => ({ picked: new Set(picked), mode, q });
const e = (skills: string[], t = "") => ({ skills, t });

describe("filter matching", () => {
  it("matches everything when nothing is selected", () => {
    expect(matches(f(), e([], "anything"))).toBe(true);
    expect(isActive(f())).toBe(false);
  });
  it("match all needs every picked skill, match any needs one", () => {
    expect(matches(f(["A", "B"]), e(["A", "B", "C"]))).toBe(true);
    expect(matches(f(["A", "B"]), e(["A"]))).toBe(false);
    expect(matches(f(["A", "B"], "", "any"), e(["A"]))).toBe(true);
    expect(matches(f(["A", "B"], "", "any"), e(["C"]))).toBe(false);
  });
  it("text is a case-insensitive AND of words over the search text", () => {
    expect(textOk(f([], "Dead LOCK"), "fixed deadlock and lock issues")).toBe(true);
    expect(textOk(f([], "deadlock spark"), "fixed deadlock")).toBe(false);
    expect(tokens("  a   b ")).toEqual(["a", "b"]);
  });
  it("skills and text combine with AND", () => {
    expect(matches(f(["A"], "foo"), e(["A"], "foo bar"))).toBe(true);
    expect(matches(f(["A"], "zzz"), e(["A"], "foo bar"))).toBe(false);
  });
  it("items with no skills never match a skill filter", () => {
    expect(matches(f(["A"]), e([], "text"))).toBe(false);
  });
  it("toggle adds and removes without mutating", () => {
    const s = new Set(["a"]);
    const t = toggle(s, "b");
    expect([...s]).toEqual(["a"]);
    expect([...t].sort()).toEqual(["a", "b"]);
    expect([...toggle(t, "a")]).toEqual(["b"]);
  });
});

const device = (over: Partial<Device> = {}): Device => ({
  dbId: 1, id: "h-pi", key: "pi", cat: "raspberry_pi", name: "Pi", ip: null, summary: "", hw: [], skills: [], t: "pi",
  containers: [], services: [], network: [], ...over,
});
const svc = (name: string, skills: string[]) => ({ dbId: 1, id: "s-" + name, name, ct: null, port: null, fn: null, skills, t: name.toLowerCase() });

describe("homelab filtering", () => {
  const d = device({ skills: ["Docker"], services: [svc("Grafana", ["Grafana"]), svc("Portainer", ["Docker"])] });
  it("shows only the matching children", () => {
    const k = deviceKids(f(["Docker"]), d);
    expect(k.services.map((s) => s.name)).toEqual(["Portainer"]);
  });
  it("a device matches through a child even when it has no skill itself", () => {
    const bare = device({ services: [svc("Grafana", ["Grafana"])] });
    expect(deviceMatches(f(["Grafana"]), bare)).toBe(true);
    expect(deviceMatches(f(["Nope"]), bare)).toBe(false);
  });
  it("counts hits over the device, containers and services", () => {
    expect(homelabCount(f(["Docker"]), [d])).toEqual({ hit: 2, total: 3 });
    expect(homelabCount(f(), [d])).toEqual({ hit: 3, total: 3 });
  });
});

const portfolio = (): Portfolio => ({
  profile: { name: "N", title: "T", bio: [], socials: {}, stats: [], skills: [], t: "" },
  experience: [{ id: "e-1", dbId: 1, role: "R", company: "C", client: null, duration: "d", location: null, summary: "s", highlights: [], skills: ["Python", "Redshift"], tags: [], t: "r c python redshift" }],
  projects: [{ id: "p-1", dbId: 1, kind: "small", title: "P", description: "d", repo: null, live: null, type: null, skills: ["Python", "NumPy"], tags: [], t: "p python numpy" }],
  education: [{ id: "a-1", dbId: 1, type: "Degree", title: "M", inst: "I", date: "d", desc: null, url: null, skills: [], t: "m i" }],
  homelab: [], skills: [],
});

describe("section counts and related skills", () => {
  it("counts matches per section", () => {
    expect(sectionCounts(f(["Python"]), portfolio())).toEqual({ experience: 1, projects: 1, education: 0, homelab: 0 });
    expect(sectionCounts(f(["Redshift"]), portfolio())).toEqual({ experience: 1, projects: 0, education: 0, homelab: 0 });
  });
  it("education is hidden by any skill filter but found by text", () => {
    expect(sectionCounts(f(["Python"]), portfolio()).education).toBe(0);
    expect(sectionCounts(f([], "m i"), portfolio()).education).toBe(1);
  });
  it("related skills are those in the matching items", () => {
    expect([...relatedSkills(f(["Redshift"]), portfolio())].sort()).toEqual(["Python", "Redshift"]);
    expect([...relatedSkills(f(["NumPy"]), portfolio())].sort()).toEqual(["NumPy", "Python"]);
  });
});
