// Form-assisted edits of dashboard.yaml. The text is the source of truth: these functions take text and return text.
//
// New entries are INSERTED AS TEXT at the right place, so every other byte of the file (comments, spacing, quoting, flow
// style) stays exactly as the user wrote it. Only when a layout is not understood do we fall back to re-serialising the
// whole document, and then `reformatted` is true so the UI can say so. The server validates whatever is finally saved.
//
// One standard format everywhere (see formatYaml): block style, two-space indent, double-quoted strings. Every new entry is
// written in it, whatever style its neighbours use.
import { Document, isCollection, isMap, isScalar, isSeq, parseDocument, visit, YAMLMap, YAMLSeq, type Node } from "yaml";

export interface NewService {
  group: string; name: string; href?: string; icon?: string; description?: string;
  status?: { kind: "tcp" | "http" | "icmp"; target: string };
  /** machine id for `on:`; left out when the service is placed automatically by address */
  machine?: string;
}

export interface NewMachine {
  id: string; name: string;
  /** id of a top-level machine this one lives in */
  inside?: string;
  addresses?: string[];
  power?: string[];
  toggle?: string;
}

export interface NewAction {
  type: "wol" | "proxmox-node" | "proxmox-guest"; id: string; label: string;
  mac?: string; node?: string; command?: "shutdown" | "reboot"; kind?: "lxc" | "qemu"; vmid?: number;
  icmp?: string;
  /** also put a button for this action into this group (created when missing) */
  placeInGroup?: string;
  pin?: boolean;
}

export interface Edited { text: string; reformatted: boolean }

class Unsupported extends Error {}

function parse(text: string): Document.Parsed {
  const doc = parseDocument(text);
  if (doc.errors.length > 0) throw new Error("The file has YAML syntax errors. Fix them first, then use the form.");
  if (!isMap(doc.contents)) throw new Error("The top level of the file must be a mapping.");
  return doc;
}

const nl = (text: string) => (text.includes("\r\n") ? "\r\n" : "\n");
const lineStart = (text: string, pos: number) => text.lastIndexOf("\n", pos - 1) + 1;
/** Position of the end of the line's content: before "\n", and before a "\r" that precedes it (CRLF files). */
const lineEnd = (text: string, pos: number) => {
  const i = text.indexOf("\n", pos);
  const end = i < 0 ? text.length : i;
  return end > 0 && text[end - 1] === "\r" && i >= 0 ? end - 1 : end;
};

/** Makes every string a double-quoted scalar and every non-empty collection block style. Keys stay plain. */
function standardise(doc: Document): void {
  visit(doc, {
    Collection(_k, node) { if (node.items.length > 0) node.flow = false; node.spaceBefore = false; },
    Pair(_k, pair) { if (isScalar(pair.key)) { pair.key.spaceBefore = false; if (typeof pair.key.value === "string") pair.key.type = "PLAIN"; } },
    Scalar(key, node) {
      if (key === "key") return;
      node.spaceBefore = false;
      if (typeof node.value === "string") node.type = "QUOTE_DOUBLE";
    },
  });
}

const STD = { lineWidth: 0, indent: 2, indentSeq: true } as const;

/** YAML for one new list entry (no leading dash) in the standard format. */
function render(value: unknown): string {
  const doc = new Document(value);
  standardise(doc);
  return doc.toString(STD).replace(/\s+$/, "");
}

function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a === "number" && typeof b === "number") return Number.isNaN(a) && Number.isNaN(b);
  if (Array.isArray(a) || Array.isArray(b)) return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => deepEqual(x, b[i]));
  if (a && b && typeof a === "object" && typeof b === "object") {
    const ka = Object.keys(a), kb = Object.keys(b);
    return ka.length === kb.length && ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && deepEqual((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]));
  }
  return false;
}

/**
 * The standard format: block style only (an empty list or map stays [] or {}), two-space indent with list items under their
 * key, every string double-quoted, keys plain, comments kept, one blank line between top-level sections and between
 * top-level groups, no line folding. The result must parse to exactly the same data; if it does not, or the file has syntax
 * errors, this throws and the caller keeps its text.
 */
export function formatYaml(text: string): string {
  const doc = parseDocument(text);
  if (doc.errors.length > 0) throw new Error("The file has YAML syntax errors, so it cannot be formatted. Fix them first.");
  if (!isMap(doc.contents)) throw new Error("The top level of the file must be a mapping.");
  const before = doc.toJS();
  standardise(doc);
  const root = doc.contents as YAMLMap;
  root.items.forEach((pair, i) => { if (i > 0 && isScalar(pair.key)) pair.key.spaceBefore = true; });
  const groups = root.get("groups", true);
  if (isSeq(groups)) groups.items.forEach((g, i) => { if (i > 0 && isCollection(g)) g.spaceBefore = true; });
  const out = doc.toString(STD);
  const again = parseDocument(out);
  if (again.errors.length > 0 || !deepEqual(before, again.toJS())) throw new Error("Formatting would change the data, so nothing was changed. Edit the file by hand instead.");
  return out;
}

function child(map: YAMLMap, key: string): Node | null {
  const v = map.get(key, true);
  return (v as Node | undefined) ?? null;
}

/**
 * Appends `entry` to the list found at `path` (creating the key when it is missing or empty).
 * `path` is the list of keys/indexes from the document root to the sequence.
 */
function appendToSeq(text: string, path: (string | number)[], entry: unknown, beforeKey?: string): string {
  const doc = parse(text);
  const eol = nl(text);
  const parent = path.length === 1 ? (doc.contents as YAMLMap) : (doc.getIn(path.slice(0, -1), true) as YAMLMap | undefined);
  if (!parent || !isMap(parent)) throw new Unsupported("parent is not a mapping");
  const key = String(path[path.length - 1]);
  const node = child(parent, key);
  const body = render(entry).split("\n");

  // key missing: append a new block at the end of the parent map (only supported at the top level)
  if (node == null || (isScalar(node) && node.value === null)) {
    if (path.length !== 1) throw new Unsupported("nested missing key");
    if (isScalar(node)) {
      // `actions:` with no value: put the list on the following lines
      const end = node.range ? lineEnd(text, node.range[0]) : text.length;
      const keyLineStart = lineStart(text, node.range![0]);
      const indent = " ".repeat(text.slice(keyLineStart).search(/\S/));
      const lines = body.map((l, i) => `${indent}  ${i === 0 ? "- " : "  "}${l}`);
      return text.slice(0, end) + eol + lines.join(eol) + text.slice(end);
    }
    const lines = body.map((l, i) => `  ${i === 0 ? "- " : "  "}${l}`);
    const block = `${key}:` + eol + lines.join(eol) + eol;
    const before = beforeKey ? parent.items.find((p) => isScalar(p.key) && p.key.value === beforeKey) : undefined;
    if (before && isScalar(before.key) && before.key.range) {
      // put the new section above `beforeKey`, and above the comment lines that sit right on top of it
      let at = lineStart(text, before.key.range[0]);
      while (at > 0) {
        const prev = lineStart(text, at - 1);
        if (!text.slice(prev, at).trimStart().startsWith("#")) break;
        at = prev;
      }
      return text.slice(0, at) + block + eol + text.slice(at);
    }
    const sep = text.endsWith("\n") ? "" : eol;
    return text + sep + (text.trim() ? eol : "") + block;
  }

  if (!isSeq(node)) throw new Unsupported("not a list");
  if (!node.range) throw new Unsupported("no source range");

  // empty list written as `[]`: turn it into a block list under the key
  if (node.flow && node.items.length === 0) {
    const keyLine = lineStart(text, node.range[0]);
    const keyIndent = text.slice(keyLine).search(/\S/);
    if (text.slice(keyLine, node.range[0]).includes("-")) throw new Unsupported("list nested inside a list item on one line");
    const before = text.slice(0, node.range[0]).replace(/[ \t]+$/, "");
    const after = text.slice(node.range[1]);
    const dashIndent = " ".repeat(keyIndent + 2);
    const lines = body.map((l, i) => `${dashIndent}${i === 0 ? "- " : "  "}${l}`);
    const restOfLine = lineEnd(after, 0);
    return before + after.slice(0, restOfLine) + eol + lines.join(eol) + after.slice(restOfLine);
  }
  if (node.flow) throw new Unsupported("flow list with items");
  if (node.items.length === 0) throw new Unsupported("empty block list");

  // block list: insert after the last existing entry, using the same indentation as the entries before it
  const first = node.items[0] as Node;
  const last = node.items[node.items.length - 1] as Node;
  if (!first.range || !last.range) throw new Unsupported("no item range");
  const ls = lineStart(text, first.range[0]);
  const prefix = text.slice(ls, first.range[0]);
  const dash = prefix.lastIndexOf("-");
  if (dash < 0 || prefix.slice(0, dash).trim() !== "") throw new Unsupported("unusual list layout");
  const indent = prefix.slice(0, dash);
  const at = lineEnd(text, Math.max(last.range[1] - 1, last.range[0]));
  const lines = body.map((l, i) => `${indent}${i === 0 ? "- " : "  "}${l}`);
  return text.slice(0, at) + eol + lines.join(eol) + text.slice(at);
}

function findGroupIndex(doc: Document.Parsed, name: string): number {
  const groups = child(doc.contents as YAMLMap, "groups");
  if (!isSeq(groups)) return -1;
  return groups.items.findIndex((g) => isMap(g) && g.get("name") === name);
}

function withFallback(text: string, edit: (t: string) => string, reserialise: (doc: Document.Parsed) => void): Edited {
  try {
    return { text: edit(text), reformatted: false };
  } catch (e) {
    if (!(e instanceof Unsupported)) throw e;
    const doc = parse(text);
    reserialise(doc);
    return { text: formatYaml(doc.toString(STD)), reformatted: true };
  }
}

export function listGroups(text: string): string[] {
  try {
    const doc = parse(text);
    const groups = child(doc.contents as YAMLMap, "groups");
    if (!isSeq(groups)) return [];
    return groups.items.flatMap((g) => (isMap(g) && typeof g.get("name") === "string" ? [g.get("name") as string] : []));
  } catch {
    return [];
  }
}

export function addService(text: string, svc: NewService): Edited {
  const item: Record<string, unknown> = { name: svc.name.trim() };
  if (svc.href?.trim()) item.href = svc.href.trim();
  if (svc.icon?.trim()) item.icon = svc.icon.trim();
  if (svc.description?.trim()) item.description = svc.description.trim();
  if (svc.machine?.trim()) item.on = svc.machine.trim();
  const status = svc.status?.target.trim() ? { [svc.status.kind]: svc.status.target.trim() } : null;
  const group = svc.group.trim();

  const edit = (t: string): string => {
    const doc = parse(t);
    const gi = findGroupIndex(doc, group);
    if (gi < 0) {
      // a new group, appended to the groups list
      const g = { name: group, items: [{ ...item, ...(status ? { status } : {}) }] };
      return appendToSeq(t, ["groups"], g);
    }
    return appendToSeq(t, ["groups", gi, "items"], status ? { ...item, status } : item);
  };
  return withFallback(text, edit, (doc) => {
    const root = doc.contents as YAMLMap;
    let groups = child(root, "groups") as YAMLSeq | null;
    if (!isSeq(groups)) { groups = doc.createNode([]) as YAMLSeq; root.set("groups", groups); }
    groups.flow = false;
    let g = groups.items.find((x) => isMap(x) && x.get("name") === group) as YAMLMap | undefined;
    if (!g) { g = doc.createNode({ name: group, items: [] }) as YAMLMap; groups.add(g); }
    let items = child(g, "items") as YAMLSeq | null;
    if (!isSeq(items)) { items = doc.createNode([]) as YAMLSeq; g.set("items", items); }
    items.flow = false;
    const n = doc.createNode(item) as YAMLMap;
    if (status) n.set("status", doc.createNode(status));
    items.add(n);
  });
}

export function addAction(text: string, a: NewAction): Edited {
  const obj: Record<string, unknown> = { id: a.id.trim(), label: a.label.trim(), type: a.type };
  if (a.type === "wol") obj.mac = a.mac?.trim();
  if (a.type === "proxmox-node") { obj.node = a.node?.trim(); obj.command = a.command; }
  if (a.type === "proxmox-guest") { obj.node = a.node?.trim(); obj.kind = a.kind; obj.vmid = a.vmid; }
  if (a.icmp?.trim()) obj.status = { icmp: a.icmp.trim() };
  const button = { name: a.label.trim(), action: a.id.trim() };

  const edit = (t: string): string => {
    let out = appendToSeq(t, ["actions"], obj);
    if (a.placeInGroup?.trim()) {
      const doc = parse(out);
      const gi = findGroupIndex(doc, a.placeInGroup.trim());
      out = gi < 0
        ? appendToSeq(out, ["groups"], { name: a.placeInGroup.trim(), items: [button] })
        : appendToSeq(out, ["groups", gi, "items"], button);
    }
    if (a.pin) out = appendToSeq(out, ["pinned"], a.id.trim());
    return out;
  };
  return withFallback(text, edit, (doc) => {
    const root = doc.contents as YAMLMap;
    const ensure = (key: string): YAMLSeq => {
      let s = child(root, key) as YAMLSeq | null;
      if (!isSeq(s)) { s = doc.createNode([]) as YAMLSeq; root.set(key, s); }
      s.flow = false;
      return s;
    };
    const n = doc.createNode(obj) as YAMLMap;
    ensure("actions").add(n);
    if (a.placeInGroup?.trim()) {
      const name = a.placeInGroup.trim();
      const groups = ensure("groups");
      let g = groups.items.find((x) => isMap(x) && x.get("name") === name) as YAMLMap | undefined;
      if (!g) { g = doc.createNode({ name, items: [] }) as YAMLMap; groups.add(g); }
      let items = child(g, "items") as YAMLSeq | null;
      if (!isSeq(items)) { items = doc.createNode([]) as YAMLSeq; g.set("items", items); }
      items.flow = false;
      const b = doc.createNode(button) as YAMLMap;
      items.add(b);
    }
    if (a.pin) ensure("pinned").add(doc.createNode(a.id.trim()));
  });
}

export interface MachineRef { id: string; name: string; parent: string | null }

/** The machines in file order. A broken file gives an empty list. */
export function listMachines(text: string): MachineRef[] {
  try {
    const doc = parse(text);
    const machines = child(doc.contents as YAMLMap, "machines");
    if (!isSeq(machines)) return [];
    return machines.items.flatMap((m) => {
      if (!isMap(m) || typeof m.get("id") !== "string") return [];
      const inside = m.get("in");
      return [{ id: m.get("id") as string, name: asString(m.get("name")), parent: typeof inside === "string" && inside ? inside : null }];
    });
  } catch {
    return [];
  }
}

/** Adds a machine to `machines:`. The section is created above `groups:` when the file has none. */
export function addMachine(text: string, m: NewMachine): Edited {
  const obj: Record<string, unknown> = { id: m.id.trim(), name: m.name.trim() };
  if (m.inside?.trim()) obj.in = m.inside.trim();
  const addresses = (m.addresses ?? []).map((x) => x.trim()).filter(Boolean);
  if (addresses.length) obj.addresses = addresses;
  if (m.power?.length) obj.power = m.power;
  if (m.toggle?.trim()) obj.toggle = m.toggle.trim();
  const edit = (t: string): string => appendToSeq(t, ["machines"], obj, "groups");
  return withFallback(text, edit, (doc) => {
    const root = doc.contents as YAMLMap;
    let s = child(root, "machines") as YAMLSeq | null;
    if (!isSeq(s)) { s = doc.createNode([]) as YAMLSeq; root.set("machines", s); }
    s.flow = false;
    s.add(doc.createNode(obj));
  });
}

export function slugId(label: string): string {
  const s = label.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60);
  return s || "action";
}


// ------------------------------------------------------------------------------------------------ removing entries

export interface ServiceRef { path: (string | number)[]; label: string; name: string; isButton: boolean }
export interface ActionRef { id: string; label: string; type: string; buttons: number; pinned: boolean }
export interface Removed extends Edited { removedButtons: number; removedPin: boolean }

const asString = (v: unknown): string => (typeof v === "string" ? v : v == null ? "" : String(v));

/** Every clickable entry in the groups (nested groups included), with the path that removeEntry needs. */
export function listServices(text: string): ServiceRef[] {
  try {
    const doc = parse(text);
    const out: ServiceRef[] = [];
    const walk = (seq: unknown, path: (string | number)[], trail: string[]) => {
      if (!isSeq(seq)) return;
      seq.items.forEach((it, i) => {
        if (!isMap(it)) return;
        const name = asString(it.get("name"));
        const inner = child(it, "items");
        if (isSeq(inner)) walk(inner, [...path, i, "items"], [...trail, name]);
        else out.push({ path: [...path, i], name, label: [...trail, name || "(unnamed)"].join(" › "), isButton: it.has("action") });
      });
    };
    const groups = child(doc.contents as YAMLMap, "groups");
    if (isSeq(groups)) groups.items.forEach((g, gi) => {
      if (!isMap(g)) return;
      walk(child(g, "items"), ["groups", gi, "items"], [asString(g.get("name"))]);
    });
    return out;
  } catch {
    return [];
  }
}

/** Paths of the dashboard buttons that trigger action `id`. */
function buttonPaths(doc: Document.Parsed, id: string): (string | number)[][] {
  const found: (string | number)[][] = [];
  const walk = (seq: unknown, path: (string | number)[]) => {
    if (!isSeq(seq)) return;
    seq.items.forEach((it, i) => {
      if (!isMap(it)) return;
      const inner = child(it, "items");
      if (isSeq(inner)) walk(inner, [...path, i, "items"]);
      else if (it.get("action") === id) found.push([...path, i]);
    });
  };
  const groups = child(doc.contents as YAMLMap, "groups");
  if (isSeq(groups)) groups.items.forEach((g, gi) => { if (isMap(g)) walk(child(g, "items"), ["groups", gi, "items"]); });
  return found;
}

function pinnedIndex(doc: Document.Parsed, id: string): number {
  const pinned = child(doc.contents as YAMLMap, "pinned");
  return isSeq(pinned) ? pinned.items.findIndex((x) => isScalar(x) && x.value === id) : -1;
}

export function listActions(text: string): ActionRef[] {
  try {
    const doc = parse(text);
    const actions = child(doc.contents as YAMLMap, "actions");
    if (!isSeq(actions)) return [];
    return actions.items.flatMap((a) => {
      if (!isMap(a)) return [];
      const id = asString(a.get("id"));
      return [{ id, label: asString(a.get("label")), type: asString(a.get("type")), buttons: buttonPaths(doc, id).length, pinned: pinnedIndex(doc, id) >= 0 }];
    });
  } catch {
    return [];
  }
}

/**
 * Removes the list entry at `path` (the last element is its index). Its own lines are cut out of the text and nothing else
 * changes. Layouts that cannot be cut safely (flow lists, the last remaining entry of a list) fall back to re-serialising the
 * document, and `reformatted` says so.
 */
function removeEntry(text: string, path: (string | number)[]): Edited {
  const index = path[path.length - 1];
  const seqPath = path.slice(0, -1);
  if (typeof index !== "number") throw new Error("not a list entry");
  const textLevel = (t: string): string => {
    const doc = parse(t);
    const seq = doc.getIn(seqPath, true);
    if (!isSeq(seq) || seq.items.length < 2) throw new Unsupported("cannot cut this list safely");
    const entry = seq.items[index] as Node | undefined;
    if (!entry?.range) throw new Unsupported("no range");
    if (seq.flow) {
      // [a, b, c]: take the entry and one neighbouring comma. The others keep their exact text.
      const next = seq.items[index + 1] as Node | undefined;
      const prev = seq.items[index - 1] as Node | undefined;
      if (next?.range) return t.slice(0, entry.range[0]) + t.slice(next.range[0]);
      if (prev?.range) return t.slice(0, prev.range[1]) + t.slice(entry.range[1]);
      throw new Unsupported("no neighbour");
    }
    const ls = lineStart(t, entry.range[0]);
    const prefix = t.slice(ls, entry.range[0]);
    if (!/^\s*-\s+$/.test(prefix)) throw new Unsupported("unusual entry layout");
    const end = lineEnd(t, Math.max(entry.range[1] - 1, entry.range[0]));
    if (t.slice(end).replace(/\r?\n/, "").length === 0 && end === t.length) {
      // last line of a file without a final newline: take the newline before it
      const from = ls > 0 ? ls - (t[ls - 2] === "\r" ? 2 : 1) : ls;
      return t.slice(0, from) + t.slice(end);
    }
    const after = t.indexOf("\n", end);
    return t.slice(0, ls) + (after < 0 ? "" : t.slice(after + 1));
  };
  try {
    return { text: textLevel(text), reformatted: false };
  } catch (e) {
    if (!(e instanceof Unsupported)) throw e;
    const doc = parse(text);
    doc.deleteIn(path);
    const seq = doc.getIn(seqPath, true);
    if (isSeq(seq) && seq.items.length === 0) {
      // never leave `key:` with no value behind: an emptied list stays a list
      if (seqPath.length === 1 && (seqPath[0] === "pinned" || seqPath[0] === "actions")) doc.deleteIn(seqPath);
      else { const empty = doc.createNode([]) as YAMLSeq; empty.flow = true; doc.setIn(seqPath, empty); }
    }
    return { text: formatYaml(doc.toString(STD)), reformatted: true };
  }
}

export function removeService(text: string, path: (string | number)[]): Edited {
  return removeEntry(text, path);
}

/** Removes an action together with every dashboard button and pin that points at it (they would be dangling references). */
export function removeAction(text: string, id: string): Removed {
  let out = text;
  let reformatted = false;
  let removedButtons = 0;
  let removedPin = false;
  for (;;) {
    const paths = buttonPaths(parse(out), id);
    if (paths.length === 0) break;
    const r = removeEntry(out, paths[paths.length - 1]);
    out = r.text; reformatted ||= r.reformatted; removedButtons++;
  }
  const pi = pinnedIndex(parse(out), id);
  if (pi >= 0) { const r = removeEntry(out, ["pinned", pi]); out = r.text; reformatted ||= r.reformatted; removedPin = true; }
  const actions = child(parse(out).contents as YAMLMap, "actions");
  const ai = isSeq(actions) ? actions.items.findIndex((a) => isMap(a) && a.get("id") === id) : -1;
  if (ai < 0) throw new Error(`There is no action with the id "${id}".`);
  const r = removeEntry(out, ["actions", ai]);
  return { text: r.text, reformatted: reformatted || r.reformatted, removedButtons, removedPin };
}
