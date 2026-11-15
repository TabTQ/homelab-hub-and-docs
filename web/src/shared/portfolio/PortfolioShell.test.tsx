import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "../components/Toast";
import type { Portfolio } from "../types";
import { PortfolioShell } from "./PortfolioShell";
import { coreSkills } from "./screens/CharacterScreen";
import { parseTab } from "./context";

const data = {
  profile: { name: "Ada Test", title: "Engineer", tagline: "Pipelines", location: "Here", status: "remote", bio: ["Hi"], socials: { github: "https://github.com/x" }, stats: [{ value: "4+", label: "Years", sub: "" }], skills: [], t: "" },
  experience: [], projects: [{ id: "p1", dbId: 1, kind: "flagship", title: "Proj One", description: "d", repo: null, live: null, type: null, skills: ["Py"], tags: [], t: "proj" }],
  education: [], homelab: [],
  skills: [{ id: 1, name: "G", icon: "", skills: [{ id: 1, l: "Py", n: 5 }, { id: 2, l: "Go", n: 0 }, { id: 3, l: "Sql", n: 2 }] }],
} as unknown as Portfolio;

vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} unobserve() {} });
window.scrollTo = () => {};
const mount = () => render(<ToastProvider><PortfolioShell data={data} /></ToastProvider>);
afterEach(() => { location.hash = ""; vi.useRealTimers(); });

describe("portfolio shell", () => {
  it("parses hash routes and defaults to character", () => {
    expect(parseTab("")).toBe("character");
    expect(parseTab("#/summary")).toBe("summary");
    expect(parseTab("#/nope")).toBe("character");
  });
  it("picks the core skills by linked count", () => {
    expect(coreSkills(data.skills, 4).map((s) => s.l)).toEqual(["Py", "Sql"]);
  });
  it("shows the character screen and moves with E and Q", async () => {
    mount();
    expect(screen.getByRole("heading", { name: "Ada Test", level: 1 })).toBeTruthy();
    fireEvent.keyDown(window, { key: "e" });
    expect(location.hash).toBe("#/skills");
    await waitFor(() => expect(screen.getByRole("link", { name: "Skills" }).getAttribute("aria-current")).toBe("page"));
    fireEvent.keyDown(window, { key: "q" });
    expect(location.hash).toBe("#/character");
  });
  it("ignores Q and E typed in a field", () => {
    mount();
    const input = document.createElement("input");
    document.body.append(input);
    fireEvent.keyDown(input, { key: "e" });
    expect(location.hash).toBe("");
    input.remove();
  });
  it("runs the tour and closes it with Escape", () => {
    mount();
    fireEvent.click(screen.getByRole("button", { name: /navigation tutorial/i }));
    expect(screen.getByRole("dialog", { name: "Navigation tutorial" })).toBeTruthy();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Navigation tutorial" })).toBeNull();
  });
  it("picking a core skill sets the shared filter and the route", () => {
    mount();
    fireEvent.click(screen.getByTitle(/Py: 5 linked/));
    // jsdom does not follow hash links, so move to the summary by hand; the filter is what is under test
    act(() => { location.hash = "#/summary"; window.dispatchEvent(new HashChangeEvent("hashchange")); });
    expect(screen.getAllByText("Py").length).toBeGreaterThan(0);
    expect(screen.getByRole("status").textContent).toContain("Showing what matches");
  });
});
