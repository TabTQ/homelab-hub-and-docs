import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Portfolio } from "../../types";
import { PortfolioContext, type PortfolioContextValue } from "../context";
import { linkedWork, SkillsScreen } from "./SkillsScreen";

const data = {
  profile: {}, experience: [{ id: "e", role: "Engineer", company: "Acme", skills: ["Py"] }], projects: [{ id: "p", title: "Proj", skills: ["Py", "Sql"] }],
  education: [], homelab: [{ name: "Host", skills: [], containers: [{ name: "ct", skills: ["Py"] }], services: [], network: [] }],
  skills: [{ id: 1, name: "G", icon: "", skills: [{ id: 1, l: "Py", n: 3 }, { id: 2, l: "Go", n: 0 }, { id: 3, l: "Sql", n: 1 }] }],
} as unknown as Portfolio;

function mount(picked: string[] = []) {
  const ctx = { data, filter: { picked: new Set(picked), mode: "all", q: "" }, setFilter: vi.fn(), pickSkill: vi.fn(), clear: vi.fn(), go: vi.fn() } as unknown as PortfolioContextValue;
  render(<PortfolioContext.Provider value={ctx}><SkillsScreen /></PortfolioContext.Provider>);
  return ctx;
}

describe("skills screen", () => {
  // Reduced motion jumps straight to the target view, so the zoom can be checked without waiting for frames.
  beforeEach(() => { vi.stubGlobal("matchMedia", (q: string) => ({ matches: q.includes("reduced-motion"), media: q, addEventListener: () => {}, removeEventListener: () => {} })); });
  afterEach(() => { vi.unstubAllGlobals(); });
  it("finds linked work from the skills arrays", () => {
    const w = linkedWork(data, "Py");
    expect(w.roles).toEqual(["Engineer · Acme"]);
    expect(w.projects).toEqual(["Proj"]);
    expect(w.homelab).toEqual(["ct (Host)"]);
  });
  it("shows badges and keyboard-focusable stars", () => {
    mount(["Go"]);
    const star = screen.getByRole("button", { name: "Py, 3 linked items" });
    expect(star.getAttribute("tabindex")).toBe("0");
    expect(screen.getByRole("button", { name: "Go, 0 linked items" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByText("Linked work")).toBeTruthy();
  });
  it("selects with Enter, offers actions, and clears with Escape", () => {
    const ctx = mount();
    fireEvent.keyDown(screen.getByRole("button", { name: "Py, 3 linked items" }), { key: "Enter" });
    expect(screen.getByText("Engineer · Acme")).toBeTruthy();
    fireEvent.click(screen.getByText("View in quests"));
    expect(ctx.pickSkill).toHaveBeenCalledWith("Py");
    expect(ctx.go).toHaveBeenCalledWith("quests");
    fireEvent.click(screen.getByText("Filter site by this skill"));
    expect(ctx.go).toHaveBeenCalledWith("summary");
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByText("Engineer · Acme")).toBeNull();
  });
  it("hides View in quests when no role uses the skill, and does not re-pick a picked skill", () => {
    const ctx = mount(["Sql"]);
    fireEvent.click(screen.getByRole("button", { name: "Sql, 1 linked item" }));
    expect(screen.queryByText("View in quests")).toBeNull();
    fireEvent.click(screen.getByText("Filter site by this skill"));
    expect(ctx.pickSkill).not.toHaveBeenCalled();
    expect(ctx.go).toHaveBeenCalledWith("summary");
  });
  it("toggles list view and chips toggle the filter", () => {
    const ctx = mount();
    fireEvent.click(screen.getByRole("button", { name: "List view" }));
    fireEvent.click(screen.getByTitle("0 linked items"));
    expect(ctx.pickSkill).toHaveBeenCalledWith("Go");
  });
  it("shows a new skill as a star in its group without any other change", () => {
    const more = { ...data, skills: [{ ...data.skills[0], skills: [...data.skills[0].skills, { id: 9, l: "Rust", n: 2 }] }] } as unknown as Portfolio;
    const ctx = { data: more, filter: { picked: new Set<string>(), mode: "all", q: "" }, setFilter: vi.fn(), pickSkill: vi.fn(), clear: vi.fn(), go: vi.fn() } as unknown as PortfolioContextValue;
    render(<PortfolioContext.Provider value={ctx}><SkillsScreen /></PortfolioContext.Provider>);
    expect(screen.getByRole("button", { name: "Rust, 2 linked items" })).toBeTruthy();
    expect(document.querySelectorAll(".sky .star")).toHaveLength(4);
  });
  it("names the constellation under the centre node", () => {
    mount();
    expect(document.querySelector(".sky .gname")?.textContent).toBe("G");
    expect(document.querySelector(".sky .cname")?.textContent).toBeTruthy();
  });
  it("zooms with the buttons and resets", () => {
    mount();
    const sky = () => document.querySelector("svg.sky") as SVGSVGElement;
    expect(sky().getAttribute("data-zoom")).toBe("1.0");
    fireEvent.click(screen.getByRole("button", { name: "Zoom in" }));
    expect(Number(sky().getAttribute("data-zoom"))).toBeGreaterThan(1);
    fireEvent.click(screen.getByRole("button", { name: "Reset zoom" }));
    expect(sky().getAttribute("data-zoom")).toBe("1.0");
  });
  it("zooms with the keyboard and the wheel, and stops the page scrolling", () => {
    mount();
    const sky = document.querySelector("svg.sky") as SVGSVGElement;
    fireEvent.keyDown(sky, { key: "+" });
    expect(Number(sky.getAttribute("data-zoom"))).toBeGreaterThan(1);
    fireEvent.keyDown(sky, { key: "0" });
    expect(sky.getAttribute("data-zoom")).toBe("1.0");
    const ev = new WheelEvent("wheel", { deltaY: -120, cancelable: true, bubbles: true });
    act(() => { sky.dispatchEvent(ev); });
    expect(ev.defaultPrevented).toBe(true);
    expect(Number(sky.getAttribute("data-zoom"))).toBeGreaterThan(1);
  });
  it("zooms to a constellation from its centre node and back with Escape", () => {
    mount();
    const sky = document.querySelector("svg.sky") as SVGSVGElement;
    const node = screen.getByRole("button", { name: /^G, .*Zoom to this constellation$/ });
    fireEvent.keyDown(node, { key: "Enter" });
    expect(Number(sky.getAttribute("data-zoom"))).toBeGreaterThan(1.5);
    expect(screen.getByRole("button", { name: /Zoom back out$/ }).getAttribute("aria-pressed")).toBe("true");
    // skill labels are visible once zoomed in
    expect(document.querySelectorAll(".sky .star.lbl").length).toBeGreaterThan(0);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(sky.getAttribute("data-zoom")).toBe("1.0");
  });
});
