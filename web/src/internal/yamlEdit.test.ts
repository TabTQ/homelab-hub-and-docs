import { describe, expect, it } from "vitest";
import { parse } from "yaml";
import { addAction, addService, listActions, listGroups, listServices, removeAction, removeService, slugId } from "./yamlEdit";

const BASE = `# My homelab
version: 1
title: Lab   # shown at the top

groups:
  # the servers
  - name: Servers
    items:
      - {name: Grafana, href: "http://192.168.10.101:3000/"}   # metrics
      - name: Nested
        items: []
actions: []
pinned: []
`;

describe("addService", () => {
  it("keeps every comment and adds the item to the right group", () => {
    const out = addService(BASE, { group: "Servers", name: "Portainer", href: "http://192.168.10.101:9000/", status: { kind: "tcp", target: "192.168.10.101:9000" } }).text;
    expect(out).toContain("# My homelab");
    expect(out).toContain("# shown at the top");
    expect(out).toContain("# the servers");
    expect(out).toContain("# metrics");
    const doc = parse(out);
    const items = doc.groups[0].items;
    expect(items.map((i: any) => i.name)).toEqual(["Grafana", "Nested", "Portainer"]);
    expect(items[2].status).toEqual({ tcp: "192.168.10.101:9000" });
  });
  it("creates a new group when the name is new", () => {
    const doc = parse(addService(BASE, { group: "Media", name: "Jellyfin", href: "http://x:8096" }).text);
    expect(doc.groups.map((g: any) => g.name)).toEqual(["Servers", "Media"]);
    expect(doc.groups[1].items[0].name).toBe("Jellyfin");
  });
  it("quotes names YAML would misread", () => {
    for (const name of ["No", "on", "007", "a: b", "# not a comment", "- dash", "true", "null"]) {
      const doc = parse(addService(BASE, { group: "Servers", name }).text);
      const added = doc.groups[0].items.at(-1);
      expect(added.name, name).toBe(name);
    }
  });
  it("leaves out empty optional fields", () => {
    const doc = parse(addService(BASE, { group: "Servers", name: "Bare", href: "  ", icon: "", description: " " }).text);
    expect(doc.groups[0].items.at(-1)).toEqual({ name: "Bare" });
  });
  it("refuses a file that does not parse or is not a mapping", () => {
    expect(() => addService("version: 1\ngroups: [\n", { group: "A", name: "B" })).toThrow(/syntax/);
    expect(() => addService("- a\n- b\n", { group: "A", name: "B" })).toThrow(/mapping/);
  });
  it("works when groups is missing or empty", () => {
    expect(parse(addService("version: 1\n", { group: "A", name: "B" }).text).groups[0].items[0].name).toBe("B");
    expect(parse(addService("version: 1\ngroups: []\n", { group: "A", name: "B" }).text).groups[0].name).toBe("A");
  });
  it("changes NOTHING but the inserted lines: every other byte of the file is identical", () => {
    const r = addService(BASE, { group: "Servers", name: "Z" });
    expect(r.reformatted).toBe(false);
    const before = BASE.split("\n"), after = r.text.split("\n");
    expect(after.length).toBe(before.length + 1);
    const inserted = after.filter((l) => !before.includes(l) || l.includes("Z"));
    expect(inserted).toEqual(["      - {name: Z}"]);
    expect(after.filter((l) => l !== "      - {name: Z}")).toEqual(before);
  });
  it("keeps a trailing comment on the last item and inserts after it", () => {
    const r = addService(BASE, { group: "Servers", name: "Z", href: "http://z" });
    expect(r.text).toContain('      - {name: Grafana, href: "http://192.168.10.101:3000/"}   # metrics');
    expect(parse(r.text).groups[0].items.at(-1)).toEqual({ name: "Z", href: "http://z" });
  });
  it("adds into a nested empty list written as []", () => {
    const flat = "version: 1\ngroups:\n  - name: A\n    items: []   # none yet\n";
    const r = addService(flat, { group: "A", name: "B" });
    expect(r.reformatted).toBe(false);
    expect(r.text).toContain("items:   # none yet\n      - {name: B}");
    expect(parse(r.text).groups[0].items).toEqual([{ name: "B" }]);
  });
  it("preserves Windows line endings in what it inserts", () => {
    const crlf = BASE.replace(/\n/g, "\r\n");
    const r = addService(crlf, { group: "Servers", name: "Z" });
    expect(r.text.replace(/\r\n/g, "").includes("\n")).toBe(false);
    expect(parse(r.text).groups[0].items.at(-1).name).toBe("Z");
  });
  it("falls back to re-serialising for layouts it cannot patch, and says so", () => {
    const odd = "version: 1\ngroups:\n  - name: A\n    items: [{name: X}, {name: Y}]\n";
    const r = addService(odd, { group: "A", name: "B" });
    expect(r.reformatted).toBe(true);
    expect(parse(r.text).groups[0].items.map((i: any) => i.name)).toEqual(["X", "Y", "B"]);
  });
});

describe("addAction", () => {
  it("adds a wake action with a status check and pins it", () => {
    const out = addAction(BASE, { type: "wol", id: "wake-pve", label: "Wake Proxmox", mac: "AA:BB:CC:DD:EE:01", icmp: "192.168.10.102", pin: true }).text;
    const doc = parse(out);
    expect(doc.actions).toEqual([{ id: "wake-pve", label: "Wake Proxmox", type: "wol", mac: "AA:BB:CC:DD:EE:01", status: { icmp: "192.168.10.102" } }]);
    expect(doc.pinned).toEqual(["wake-pve"]);
    expect(out).toContain("# My homelab");
  });
  it("adds node and guest actions and a button in a group", () => {
    let t = addAction(BASE, { type: "proxmox-node", id: "pve-off", label: "Shutdown", node: "pve", command: "shutdown" }).text;
    t = addAction(t, { type: "proxmox-guest", id: "apps", label: "Apps LXC", node: "pve", kind: "lxc", vmid: 100, placeInGroup: "Power" }).text;
    const doc = parse(t);
    expect(doc.actions.map((a: any) => a.id)).toEqual(["pve-off", "apps"]);
    expect(doc.actions[1].vmid).toBe(100);
    expect(doc.groups.at(-1)).toEqual({ name: "Power", items: [{ name: "Apps LXC", action: "apps" }] });
  });
  it("appends to an existing actions list without touching earlier entries", () => {
    const t1 = addAction(BASE, { type: "wol", id: "a", label: "A", mac: "AA:BB:CC:DD:EE:FF" }).text;
    const t2 = addAction(t1, { type: "wol", id: "b", label: "B", mac: "AA:BB:CC:DD:EE:00" }).text;
    expect(parse(t2).actions.map((a: any) => a.id)).toEqual(["a", "b"]);
  });
});

describe("helpers", () => {
  it("lists top-level group names", () => {
    expect(listGroups(BASE)).toEqual(["Servers"]);
    expect(listGroups("garbage: [")).toEqual([]);
  });
  it("makes safe action ids", () => {
    expect(slugId("Wake Proxmox!")).toBe("wake-proxmox");
    expect(slugId("  --  ")).toBe("action");
    expect(slugId("A".repeat(100)).length).toBe(60);
  });

  it("only touches the lines it adds when adding an action, a button and a pin together", () => {
    const r = addAction(BASE, { type: "wol", id: "wake", label: "Wake", mac: "AA:BB:CC:DD:EE:FF", placeInGroup: "Servers", pin: true });
    expect(r.reformatted).toBe(false);
    for (const line of BASE.split("\n").filter((l) => l && l !== "actions: []" && l !== "pinned: []")) expect(r.text).toContain(line);
    const doc = parse(r.text);
    expect(doc.actions[0].id).toBe("wake");
    expect(doc.pinned).toEqual(["wake"]);
    expect(doc.groups[0].items.at(-1)).toEqual({ name: "Wake", action: "wake" });
    expect(r.text).toContain("# My homelab");
  });
  it("creates the actions key when the file has none", () => {
    const r = addAction("version: 1\ngroups: []\n", { type: "wol", id: "w", label: "W", mac: "AA:BB:CC:DD:EE:FF" });
    expect(r.reformatted).toBe(false);
    expect(parse(r.text).actions[0].id).toBe("w");
    const empty = addAction("version: 1\ngroups: []\nactions:\n", { type: "wol", id: "w", label: "W", mac: "AA:BB:CC:DD:EE:FF" });
    expect(parse(empty.text).actions[0].id).toBe("w");
  });
});

const FULL = `# My homelab
version: 1
title: Lab

groups:
  # the servers
  - name: Servers
    items:
      - {name: Grafana, href: "http://a/"}   # metrics
      - name: Portainer
        href: "http://b/"
        status: {tcp: "b:9000"}
      - name: Nested
        items:
          - name: Deep one
          - name: Deep two
  - name: Power
    items:
      - {name: Wake box, action: wake-box}
      - {name: Stop apps, action: stop-apps}
actions:
  - {id: wake-box, label: Wake box, type: wol, mac: "AA:BB:CC:DD:EE:FF"}
  - {id: stop-apps, label: Stop apps, type: proxmox-guest, node: pve, kind: lxc, vmid: 100}
pinned:
  - wake-box
  - stop-apps
`;


describe("listServices / listActions", () => {
  it("lists leaves at any depth with a readable label, and marks buttons", () => {
    const s = listServices(FULL);
    expect(s.map((x) => x.label)).toEqual(["Servers › Grafana", "Servers › Portainer", "Servers › Nested › Deep one", "Servers › Nested › Deep two", "Power › Wake box", "Power › Stop apps"]);
    expect(s.filter((x) => x.isButton).length).toBe(2);
  });
  it("lists actions with how many places use them", () => {
    expect(listActions(FULL)).toEqual([
      { id: "wake-box", label: "Wake box", type: "wol", buttons: 1, pinned: true },
      { id: "stop-apps", label: "Stop apps", type: "proxmox-guest", buttons: 1, pinned: true },
    ]);
  });
  it("returns nothing for a broken file instead of throwing", () => {
    expect(listServices("groups: [")).toEqual([]);
    expect(listActions("groups: [")).toEqual([]);
  });
});

describe("removeService", () => {
  it("cuts exactly the item's own lines and keeps everything else byte for byte", () => {
    const p = listServices(FULL).find((x) => x.name === "Portainer")!.path;
    const r = removeService(FULL, p);
    expect(r.reformatted).toBe(false);
    expect(r.text).toBe(FULL.replace('      - name: Portainer\n        href: "http://b/"\n        status: {tcp: "b:9000"}\n', ""));
    expect(r.text).toContain("# metrics");
    expect(r.text).toContain("# the servers");
  });
  it("removes a one-line flow item and a nested item", () => {
    const g = removeService(FULL, listServices(FULL).find((x) => x.name === "Grafana")!.path);
    expect(g.text).toBe(FULL.replace('      - {name: Grafana, href: "http://a/"}   # metrics\n', ""));
    const d = parse(removeService(FULL, listServices(FULL).find((x) => x.name === "Deep one")!.path).text);
    expect(d.groups[0].items[2].items.map((i: any) => i.name)).toEqual(["Deep two"]);
  });
  it("removes the last item of a list without leaving an empty key, and says it re-formatted", () => {
    const one = "version: 1\ngroups:\n  - name: A\n    items:\n      - name: Only\n";
    const r = removeService(one, ["groups", 0, "items", 0]);
    expect(r.reformatted).toBe(true);
    expect(parse(r.text).groups[0].items).toEqual([]);
  });
  it("handles a file with no final newline and CRLF line endings", () => {
    const noNl = "version: 1\ngroups:\n  - name: A\n    items:\n      - name: One\n      - name: Two";
    const a = removeService(noNl, ["groups", 0, "items", 1]);
    expect(a.text).toBe("version: 1\ngroups:\n  - name: A\n    items:\n      - name: One");
    const crlf = FULL.replace(/\n/g, "\r\n");
    const b = removeService(crlf, listServices(crlf).find((x) => x.name === "Portainer")!.path);
    expect(b.reformatted).toBe(false);
    expect(b.text).toBe(crlf.replace('      - name: Portainer\r\n        href: "http://b/"\r\n        status: {tcp: "b:9000"}\r\n', ""));
  });
  it("cuts one entry out of a flow list without touching the others", () => {
    const flow = "version: 1\ngroups:\n  - name: A\n    items: [{name: One}, {name: Two},   {name: Three}]   # keep\n";
    expect(removeService(flow, ["groups", 0, "items", 0])).toEqual({ text: "version: 1\ngroups:\n  - name: A\n    items: [{name: Two},   {name: Three}]   # keep\n", reformatted: false });
    expect(removeService(flow, ["groups", 0, "items", 1]).text).toBe("version: 1\ngroups:\n  - name: A\n    items: [{name: One}, {name: Three}]   # keep\n");
    expect(removeService(flow, ["groups", 0, "items", 2]).text).toBe("version: 1\ngroups:\n  - name: A\n    items: [{name: One}, {name: Two}]   # keep\n");
  });
  it("re-formats only when the last entry of a list goes", () => {
    const one = "version: 1\ngroups:\n  - name: A\n    items: [{name: Only}]\n";
    const r = removeService(one, ["groups", 0, "items", 0]);
    expect(r.reformatted).toBe(true);
    expect(parse(r.text).groups[0].items).toEqual([]);
  });
});

describe("removeAction", () => {
  it("removes the action, its buttons and its pin, and nothing else", () => {
    const r = removeAction(FULL, "wake-box");
    expect(r.removedButtons).toBe(1);
    expect(r.removedPin).toBe(true);
    expect(r.reformatted).toBe(false);
    const doc = parse(r.text);
    expect(doc.actions.map((a: any) => a.id)).toEqual(["stop-apps"]);
    expect(doc.pinned).toEqual(["stop-apps"]);
    expect(doc.groups[1].items).toEqual([{ name: "Stop apps", action: "stop-apps" }]);
    expect(r.text).toContain("# metrics");
    expect(r.text.split("\n").length).toBe(FULL.split("\n").length - 3);
  });
  it("handles an action nobody references, and the last action / pin", () => {
    const lone = "version: 1\nactions:\n  - {id: a, label: A, type: wol, mac: 'AA:BB:CC:DD:EE:FF'}\npinned:\n  - a\ngroups: []\n";
    const r = removeAction(lone, "a");
    const doc = parse(r.text);
    expect(doc.actions ?? []).toEqual([]);
    expect(doc.pinned ?? []).toEqual([]);
    const bare = "version: 1\nactions:\n  - {id: a, label: A, type: wol, mac: 'AA:BB:CC:DD:EE:FF'}\n  - {id: b, label: B, type: wol, mac: 'AA:BB:CC:DD:EE:FF'}\n";
    expect(removeAction(bare, "b")).toMatchObject({ removedButtons: 0, removedPin: false, reformatted: false });
  });
  it("refuses an unknown id", () => {
    expect(() => removeAction(FULL, "nope")).toThrow(/no action/);
  });
});
