import { describe, expect, it } from "vitest";
import { parse } from "yaml";
import { addAction, addMachine, addService, formatYaml, listActions, listMachines, listGroups, listServices, removeAction, removeService, slugId } from "./yamlEdit";

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
    expect(inserted).toEqual(['      - name: "Z"']);
    expect(after.filter((l) => l !== '      - name: "Z"')).toEqual(before);
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
    expect(r.text).toContain('items:   # none yet\n      - name: "B"');
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


describe("new entries use the standard format", () => {
  it("writes block style with double-quoted strings next to flow neighbours", () => {
    const r = addService(BASE, { group: "Servers", name: "Z", href: "http://z", status: { kind: "tcp", target: "z:1" } });
    expect(r.text).not.toMatch(/\{name: Z/);
    expect(r.text).toContain('      - name: "Z"\n        href: "http://z"\n        status:\n          tcp: "z:1"');
  });
  it("writes actions, buttons and pins as block entries", () => {
    const r = addAction(BASE, { type: "wol", id: "w", label: "W", mac: "AA:BB:CC:DD:EE:FF", placeInGroup: "Servers", pin: true });
    expect(r.text).toContain('  - id: "w"\n    label: "W"\n    type: "wol"\n    mac: "AA:BB:CC:DD:EE:FF"');
    expect(r.text).toContain('      - name: "W"\n        action: "w"');
    expect(r.text).toContain('  - "w"');
    expect(r.text).not.toMatch(/\{id: w/);
  });
  it("writes on: only when a machine is chosen", () => {
    expect(addService(BASE, { group: "Servers", name: "Z", machine: "pve" }).text).toContain('        on: "pve"');
    expect(addService(BASE, { group: "Servers", name: "Z", machine: "" }).text).not.toMatch(/^\s+on:/m);
  });
});

describe("machines", () => {
  it("creates machines: above groups: and keeps the comment on top of groups", () => {
    const t = "version: 1\ntitle: Lab\n\n# the groups\ngroups: []\n";
    const r = addMachine(t, { id: "pve", name: "Proxmox host", addresses: ["192.168.10.102"], power: ["wake"] });
    expect(r.reformatted).toBe(false);
    expect(r.text).toBe('version: 1\ntitle: Lab\n\nmachines:\n  - id: "pve"\n    name: "Proxmox host"\n    addresses:\n      - "192.168.10.102"\n    power:\n      - "wake"\n\n# the groups\ngroups: []\n');
  });
  it("appends to an existing section, with in: and toggle:", () => {
    const t = addMachine("version: 1\ngroups: []\n", { id: "pve", name: "Proxmox" }).text;
    const u = addMachine(t, { id: "apps", name: "Apps", inside: "pve", addresses: ["10.0.0.5", " "], toggle: "t-apps" }).text;
    expect(parse(u).machines).toEqual([{ id: "pve", name: "Proxmox" }, { id: "apps", name: "Apps", in: "pve", addresses: ["10.0.0.5"], toggle: "t-apps" }]);
    expect(listMachines(u)).toEqual([{ id: "pve", name: "Proxmox", parent: null }, { id: "apps", name: "Apps", parent: "pve" }]);
    expect(listMachines("a: [")).toEqual([]);
  });
  it("works when the file has no groups", () => {
    expect(parse(addMachine("version: 1\n", { id: "a", name: "A" }).text).machines[0].id).toBe("a");
  });
});

const MIXED = `# My homelab (invented values)
version: 1
title: Lab   # shown at the top
docs: {url: http://docs.example.test}
machines:
  - {id: pi, name: Raspberry, addresses: [10.0.0.2]}
  - id: pve
    name: Proxmox
    addresses:
    - 10.0.0.3
    power: [wake, off]
  - id: apps
    name: 'Apps'
    in: pve
    toggle: toggle-apps
groups:
  # the servers
  - name: Servers
    items:
      - {name: Grafana, href: "http://10.0.0.2:3000/", status: {tcp: "10.0.0.2:3000"}}   # metrics
      - name: Docs
        href: http://10.0.0.2:3011
        description: A long description that goes past the usual wrapping width of the editor so a folding writer would split it up
        widget: {type: proxmox, url: "https://pve.example.test:8006", token: "\${secret:PVE_TOKEN}"}
      - name: Nested
        items:
          - name: Deep one
          - {name: Deep two, href: 'http://deep.example.test', on: apps}
  - name: Power
    items:
      - {name: Wake box, action: wake}
actions:
  - {id: wake, label: Wake box, type: wol, mac: "AA:BB:CC:DD:EE:FF", status: {icmp: 10.0.0.3}}
  - id: off
    label: Shut down
    type: proxmox-node
    node: pve
    command: shutdown
  - {id: toggle-apps, label: Toggle apps, type: proxmox-guest, node: pve, kind: lxc, vmid: 100}
terminals:
  - {id: pi, label: Pi, host: 10.0.0.2, port: 22, user: admin, auth: key}
dockerHosts: []
pinned: [wake]
empty: {}
flags: {on: true, count: 3, ratio: 1.5, none: null, text: "007", yes: "yes"}
`;

describe("formatYaml", () => {
  const out = formatYaml(MIXED);
  it("keeps the data exactly", () => { expect(parse(out)).toEqual(parse(MIXED)); });
  it("has no flow collections except empty ones", () => {
    expect(out.replace(/"(?:[^"\\\n]|\\.)*"/g, '""').replace(/#.*$/gm, "")).not.toMatch(/[{\[][^}\]\n]/);
    expect(out).toContain("dockerHosts: []");
    expect(out).toContain("empty: {}");
  });
  it("double-quotes strings, keeps numbers and booleans plain, and keeps secret references", () => {
    expect(out).toContain('name: "Raspberry"');
    expect(out).toContain('token: "${secret:PVE_TOKEN}"');
    expect(out).toContain("port: 22");
    expect(out).toContain("count: 3");
    expect(out).toContain("on: true");
    expect(out).toContain('text: "007"');
    expect(out).toContain("none: null");
  });
  it("indents list items under their key and does not fold long lines", () => {
    expect(out).toContain('    addresses:\n      - "10.0.0.3"');
    expect(out).toMatch(/description: "A long description[^\n]+split it up"\n/);
  });
  it("keeps comments", () => {
    for (const c of ["# My homelab (invented values)", "# shown at the top", "# the servers", "# metrics"]) expect(out).toContain(c);
  });
  it("puts one blank line between top-level sections and between top-level groups", () => {
    expect(out).toContain('\n\nmachines:\n');
    expect(out).toContain('\n\ngroups:\n');
    expect(out).toMatch(/\n\n  - name: "Power"/);
    expect(out).not.toMatch(/\n\n\n/);
  });
  it("is stable when formatted twice", () => { expect(formatYaml(out)).toBe(out); });
  it("refuses a file with syntax errors", () => {
    expect(() => formatYaml("version: 1\ngroups: [\n")).toThrow(/syntax/);
    expect(() => formatYaml("- a\n")).toThrow(/mapping/);
  });
  it("is what the writers produce, so a file they edited is already standard", () => {
    const t = addService(addAction(out, { type: "wol", id: "w2", label: "W2", mac: "AA:BB:CC:DD:EE:00" }).text, { group: "Servers", name: "New", machine: "pve" }).text;
    expect(formatYaml(t)).toBe(t);
  });
});
