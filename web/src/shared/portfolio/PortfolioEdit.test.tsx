import { act, fireEvent, render, renderHook, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "../components/Toast";
import type { Portfolio, ResourceSchema } from "../types";
import { PortfolioContext, usePortfolio, type PortfolioContextValue } from "./context";
import { PortfolioShell } from "./PortfolioShell";

const dev = { dbId: 7, id: "proxmox", key: "proxmox", cat: "proxmox", name: "Node One", ip: null, summary: "sum", url: null, hw: [{ component: "CPU", spec: "4 cores" }], skills: [], t: "node",
  containers: [{ dbId: 11, id: "lxc-100", name: "Box", ip: null, purpose: "Web", res: "2C", status: "Active", skills: [], t: "box" }],
  services: [{ dbId: 12, id: "s1", name: "Svc", ct: null, port: "80", fn: "Serve", skills: [], t: "svc" }],
  network: [{ dbId: 13, id: "n1", type: "vlan", name: "Lan", spec: { subnet: "10.0.0.0/24" }, skills: [], t: "lan" }] };
const data = {
  profile: { name: "Ada Test", title: "Engineer", tagline: "Pipelines", location: "Here", status: "remote", bio: ["Bio"], email: "a@example.com", socials: {}, stats: [{ value: "4+", label: "Years", sub: "" }], skills: [], t: "" },
  experience: [{ id: "e1", dbId: 5, role: "Consultant", company: "Acme", client: null, duration: "2024 - Present", location: null, summary: "s", highlights: ["h"], skills: ["Py"], tags: [], t: "" }],
  projects: [{ id: "p1", dbId: 9, kind: "flagship", title: "Alpha", description: "d", repo: null, live: null, type: "Data", skills: ["Py"], tags: [], t: "" }],
  education: [{ id: "d1", dbId: 3, type: "Degree", title: "BTech", inst: "Uni", date: "2020", desc: null, url: null, skills: [], t: "" }],
  homelab: [dev],
  skills: [{ id: 4, name: "Langs", icon: "", skills: [{ id: 21, l: "Py", n: 3 }] }],
} as unknown as Portfolio;

vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} unobserve() {} });
window.scrollTo = () => {};
const make = () => ({ onEdit: vi.fn(), onAdd: vi.fn() });
const mount = (edit?: ReturnType<typeof make>) => render(<ToastProvider><PortfolioShell data={data} edit={edit} /></ToastProvider>);
const tab = (name: string) => fireEvent.click(screen.getByRole("link", { name }));
const editButtons = () => screen.queryAllByRole("button", { name: /^(Edit|Add) / }).filter((b) => b.classList.contains("ed-btn") || b.classList.contains("ed-add"));
beforeEach(() => { location.hash = ""; });
afterEach(() => { location.hash = ""; });

describe("edit hooks in the context", () => {
  it("exposes edit to the screens", () => {
    const edit = make();
    const wrap = ({ children }: { children: React.ReactNode }) => <PortfolioContext.Provider value={{ data, edit } as unknown as PortfolioContextValue}>{children}</PortfolioContext.Provider>;
    const { result } = renderHook(() => usePortfolio(), { wrapper: wrap });
    expect(result.current.edit).toBe(edit);
  });
});

describe("public shell", () => {
  it("shows no edit buttons on any tab", () => {
    mount();
    for (const t of ["Character", "Skills", "Quests", "Inventory", "World", "Codex"]) {
      act(() => { location.hash = `#/${t.toLowerCase()}`; window.dispatchEvent(new HashChangeEvent("hashchange")); });
      expect(editButtons(), t).toHaveLength(0);
    }
  });
});

describe("edit mode", () => {
  it("shows the character edit buttons and calls onEdit with the old page's resource and id", () => {
    const edit = make();
    mount(edit);
    expect(location.hash).toBe("");
    fireEvent.click(screen.getByRole("button", { name: "Edit profile" }));
    expect(edit.onEdit).toHaveBeenCalledWith("profile", 1);
    fireEvent.click(screen.getByRole("button", { name: "Edit stat: Years" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("profile", 1);
    expect(screen.getByText(/The first four projects in order/)).toBeTruthy();
  });
  it("switches tabs without touching the URL hash", () => {
    mount(make());
    tab("Skills");
    expect(location.hash).toBe("");
    expect(screen.getByRole("link", { name: "Skills" }).getAttribute("aria-current")).toBe("page");
  });
  it("skills: opens in list view; edits a skill and a group; adds a skill", () => {
    const edit = make();
    mount(edit);
    tab("Skills");
    fireEvent.click(screen.getByRole("button", { name: "Edit skill: Py" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("skills", 21);
    fireEvent.click(screen.getByRole("button", { name: "Edit group: Langs" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("skill-groups", 4);
    fireEvent.click(screen.getByRole("button", { name: "Add skill to Langs" }));
    expect(edit.onAdd).toHaveBeenLastCalledWith("skills", { api: "groupId", id: 4 });
  });
  it("quests: edits and adds a role", () => {
    const edit = make();
    mount(edit);
    tab("Quests");
    fireEvent.click(screen.getByRole("button", { name: "Edit role: Consultant" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("experiences", 5);
    fireEvent.click(screen.getByRole("button", { name: "Add role" }));
    expect(edit.onAdd).toHaveBeenLastCalledWith("experiences", undefined);
  });
  it("inventory: edits a project and adds one", () => {
    const edit = make();
    mount(edit);
    tab("Inventory");
    fireEvent.click(screen.getByRole("button", { name: "Edit project: Alpha" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("projects", 9);
    fireEvent.click(screen.getByRole("button", { name: "Add project" }));
    expect(edit.onAdd).toHaveBeenLastCalledWith("projects", undefined);
  });
  it("world: edits a device, container, service and network entry; adds a container", () => {
    const edit = make();
    mount(edit);
    tab("World");
    fireEvent.click(screen.getByRole("button", { name: "Edit device: Node One" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("homelab-devices", 7);
    fireEvent.click(screen.getByRole("button", { name: "Edit container: Box" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("homelab-containers", 11);
    fireEvent.click(screen.getByRole("button", { name: "Edit service: Svc" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("homelab-services", 12);
    fireEvent.click(screen.getByRole("button", { name: "Edit network entry: Lan" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("homelab-network", 13);
    fireEvent.click(screen.getByRole("button", { name: "Add container" }));
    expect(edit.onAdd).toHaveBeenLastCalledWith("homelab-containers", { api: "deviceId", id: 7 });
  });
  it("codex: edits an education item, adds one and edits the bio", () => {
    const edit = make();
    mount(edit);
    tab("Codex");
    fireEvent.click(screen.getByRole("button", { name: "Edit education: BTech" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("education", 3);
    fireEvent.click(screen.getByRole("button", { name: "Add education item" }));
    expect(edit.onAdd).toHaveBeenLastCalledWith("education", undefined);
    fireEvent.click(screen.getByRole("button", { name: "Edit bio" }));
    expect(edit.onEdit).toHaveBeenLastCalledWith("profile", 1);
  });
});

describe("PortfolioAdmin", () => {
  it("renders the tabbed shell and opens the drawer for a chosen resource", async () => {
    const schema: ResourceSchema[] = [{ path: "profile", title: "Profile", singleton: true, parent: null, skills: false, tags: false, fields: [{ api: "name", label: "Name", type: "text", required: true, max: 100, allowed: [], help: null }] }];
    vi.resetModules();
    vi.doMock("../api", () => ({
      ApiError: class extends Error {},
      api: vi.fn(async (path: string) => (path === "/api/portfolio" ? data : path.endsWith("/schema") ? schema : { upToDate: true, lastPublished: null, lastError: null })),
      apiWithHeaders: vi.fn(async () => ({ data: { id: 1, version: 1, name: "Ada Test" }, headers: new Headers() })),
    }));
    const { PortfolioAdmin } = await import("./PortfolioAdmin");
    render(<ToastProvider><PortfolioAdmin header={<div>Docs header</div>} onUnauthorized={() => {}} extraPalette={[]} /></ToastProvider>);
    await waitFor(() => screen.getByRole("button", { name: "Edit profile" }));
    expect(screen.getByText("Docs header")).toBeTruthy();
    expect(screen.getByRole("note", { name: "How editing works" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Edit profile" }));
    await waitFor(() => screen.getByRole("dialog", { name: /profile/i }));
    vi.doUnmock("../api");
  });
});
