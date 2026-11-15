import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "../../components/Toast";
import type { Portfolio } from "../../types";
import { PortfolioShell } from "../PortfolioShell";

const dev = (id: string, name: string, extra = {}) => ({ dbId: 1, id, key: id, cat: id, name, ip: null, summary: "sum " + name, url: null, hw: [{ component: "CPU", spec: "4 cores" }], skills: [], t: name.toLowerCase(), containers: [], services: [], network: [], ...extra });
const data = {
  profile: { name: "Ada Test", title: "Engineer", location: "Here", status: "remote", bio: ["Bio one", "Bio two"], email: "a@example.com", socials: { github: "https://github.com/x", githubLabel: "github.com/x", evil: "javascript:alert(1)" }, stats: [], skills: [], t: "" },
  experience: [
    { id: "e1", dbId: 1, role: "Lead Role", company: "Acme", client: null, duration: "2024 – Present", location: "Town", summary: "Lead summary", highlights: ["Did a big thing"], skills: ["Py"], tags: [], t: "lead py" },
    { id: "e2", dbId: 2, role: "Old Role", company: "Beta", client: null, duration: "2020 – 2022", location: null, summary: "Old summary", highlights: ["Did an old thing"], skills: ["Go"], tags: [], t: "old go" },
  ],
  projects: [
    { id: "p1", dbId: 1, kind: "flagship", title: "Alpha", description: "alpha d", repo: "https://github.com/x/a", live: null, type: "Data", skills: ["Py"], tags: [], t: "alpha py" },
    { id: "p2", dbId: 2, kind: "small", title: "Beta", description: "beta d", repo: null, live: null, type: "Code", skills: ["Go"], tags: [], t: "beta go" },
  ],
  education: [
    { id: "d1", dbId: 1, type: "Degree", title: "BTech", inst: "Uni", date: "2020", desc: "Studied things", url: null, skills: [], t: "btech" },
    { id: "d2", dbId: 2, type: "Certification", title: "Cert", inst: "Org", date: "2022", desc: null, url: "https://example.com/c", skills: [], t: "cert" },
  ],
  homelab: [
    dev("proxmox", "Node One", { containers: [{ dbId: 1, id: "lxc-100", name: "Box", ip: null, purpose: "Web", res: "2C", status: "Active", skills: [], t: "box" }] }),
    dev("pi", "Pi Two", { services: [{ dbId: 1, id: "s1", name: "Svc", ct: null, port: "80", fn: "Serve", skills: [], t: "svc" }] }),
  ],
  skills: [{ id: 1, name: "G", icon: "", skills: [{ id: 1, l: "Py", n: 2 }] }],
} as unknown as Portfolio;

vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} unobserve() {} });
window.scrollTo = () => {};
const mount = (tab: string, d = data) => { location.hash = "#/" + tab; return render(<ToastProvider><PortfolioShell data={d} /></ToastProvider>); };
afterEach(() => { location.hash = ""; });

describe("quests", () => {
  it("selects the first role and changes the detail on click and arrow keys", async () => {
    mount("quests");
    await waitFor(() => screen.getByRole("heading", { name: "Quest log" }));
    expect(screen.getByRole("heading", { name: "Lead Role", level: 2 })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Old Role/ }));
    expect(screen.getByRole("heading", { name: "Old Role", level: 2 })).toBeTruthy();
    expect(screen.getByText("Did an old thing")).toBeTruthy();
    fireEvent.keyDown(screen.getByRole("list", { name: "Roles" }), { key: "ArrowUp" });
    expect(screen.getByRole("heading", { name: "Lead Role", level: 2 })).toBeTruthy();
  });
  it("marks Present roles Active and sorts matches first when filtered", async () => {
    mount("quests");
    await waitFor(() => screen.getByRole("heading", { name: "Quest log" }));
    expect(screen.getByRole("button", { name: /Lead Role/ }).textContent).toContain("Active");
    fireEvent.click(screen.getByRole("button", { name: /Old Role/ }));
    fireEvent.click(screen.getByRole("button", { name: "Go" }));
    const items = within(screen.getByRole("list", { name: "Roles" })).getAllByRole("listitem");
    expect(items[0].textContent).toContain("Old Role");
    expect(items[1].textContent).toContain("Lead Role");
    expect(items[1].className).toContain("sc-dim");
  });
});

describe("inventory", () => {
  it("renders tiered cards and dims a non-matching project when a skill is picked", async () => {
    mount("inventory");
    await waitFor(() => screen.getByRole("heading", { name: "Inventory" }));
    expect(screen.getByRole("article", { name: "Alpha" }).getAttribute("data-tier")).toBe("gold");
    fireEvent.click(within(screen.getByRole("article", { name: "Alpha" })).getByRole("button", { name: "Py" }));
    expect(screen.getByRole("article", { name: "Beta" }).getAttribute("data-dim")).toBe("true");
    expect(screen.getByRole("article", { name: "Alpha" }).getAttribute("data-dim")).toBeNull();
  });
  it("opens details and filters by type", async () => {
    mount("inventory");
    await waitFor(() => screen.getByRole("heading", { name: "Inventory" }));
    fireEvent.click(screen.getByRole("button", { name: /Details of Alpha/ }));
    expect(screen.getByRole("region", { name: "Alpha details" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Code" }));
    expect(screen.queryByRole("article", { name: "Alpha" })).toBeNull();
  });
});

describe("world", () => {
  it("has one banner per device and a services counter", async () => {
    mount("world");
    await waitFor(() => screen.getByRole("heading", { name: "Settlement" }));
    const banners = within(screen.getByRole("group", { name: "Devices" })).getAllByRole("button");
    expect(banners.map((b) => b.textContent)).toEqual(["Node One", "Pi Two"]);
    expect(screen.getByText("2")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Box/ }));
    expect(screen.getByText("Web")).toBeTruthy();
  });
});

describe("codex", () => {
  it("groups education, expands a row, and shows only existing contact methods", async () => {
    mount("codex");
    await waitFor(() => screen.getByRole("heading", { name: "Codex" }));
    expect(screen.getByRole("region", { name: "Degrees" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /BTech/ }));
    expect(screen.getByText("Studied things")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Email" }).getAttribute("href")).toBe("mailto:a@example.com");
    expect(screen.getByRole("link", { name: "GitHub" })).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Phone" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Evil" })).toBeNull();
    expect(screen.getByText("Bio two")).toBeTruthy();
  });
});
