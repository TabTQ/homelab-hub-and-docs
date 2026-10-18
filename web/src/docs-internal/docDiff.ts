// Page history diff (mockups tab 5): block by block, then word by word inside a changed block. Reuses the line diff from
// the config editor: each block becomes one "line" (its JSON), each word one "line" inside a block.
import { diffLines } from "../shared/diff";
import { textOf } from "../shared/docs/safe";
import type { PMNode } from "../shared/docs/types";

export type WordPart = { type: "same" | "add" | "del"; text: string };
export type BlockChange =
  | { type: "same"; node: PMNode }
  | { type: "add"; node: PMNode }
  | { type: "del"; node: PMNode }
  | { type: "change"; before: PMNode; after: PMNode; words: WordPart[] };

const blocks = (d: PMNode | null | undefined): PMNode[] => (d && Array.isArray(d.content) ? d.content : []);

export function wordDiff(a: string, b: string): WordPart[] {
  const split = (s: string) => s.split(/(\s+)/).filter((x) => x.length > 0);
  return diffLines(split(a).join("\n"), split(b).join("\n")).filter((p) => p.text !== "" || p.type !== "same");
}

export function blockDiff(before: PMNode | null | undefined, after: PMNode | null | undefined): BlockChange[] {
  const a = blocks(before), b = blocks(after);
  const sa = a.map((n) => JSON.stringify(n)), sb = b.map((n) => JSON.stringify(n));
  const lines = diffLines(sa.join("\n"), sb.join("\n"));
  const out: BlockChange[] = [];
  let i = 0, j = 0;
  // diffLines on an empty side yields one empty line; skip those.
  for (const l of lines) {
    if (l.type === "same") { if (i < a.length) out.push({ type: "same", node: a[i] }); i++; j++; }
    else if (l.type === "del") { if (i < a.length && l.text) out.push({ type: "del", node: a[i] }); i++; }
    else { if (j < b.length && l.text) out.push({ type: "add", node: b[j] }); j++; }
  }
  // Inside a run of removed and added blocks, a removed block and an added block of the same type (in order) are one
  // edited block; the rest stay removed or added.
  const merged: BlockChange[] = [];
  for (let k = 0; k < out.length;) {
    if (out[k].type === "same") { merged.push(out[k++]); continue; }
    const dels: PMNode[] = [], adds: PMNode[] = [];
    while (k < out.length && out[k].type !== "same") {
      const c = out[k++];
      if (c.type === "del") dels.push(c.node); else if (c.type === "add") adds.push(c.node);
    }
    const used = new Set<number>();
    for (const d of dels) {
      const m = adds.findIndex((x, idx) => !used.has(idx) && x.type === d.type);
      if (m >= 0) {
        used.add(m);
        merged.push({ type: "change", before: d, after: adds[m], words: wordDiff(textOf(d), textOf(adds[m])) });
      } else merged.push({ type: "del", node: d });
    }
    adds.forEach((x, idx) => { if (!used.has(idx)) merged.push({ type: "add", node: x }); });
  }
  return merged;
}

const NAMES: Record<string, string> = {
  heading: "section", paragraph: "paragraph", table: "table", image: "image", codeBlock: "code block", callout: "callout",
  mermaid: "diagram", bulletList: "list", orderedList: "list", taskList: "checklist", details: "collapsible", attachment: "file",
  webappEmbed: "embedded app", blockquote: "quote", horizontalRule: "divider",
};

/** "1 section added, 1 table edited, 1 image replaced" */
export function summarize(changes: BlockChange[]): string {
  const counts = new Map<string, number>();
  const bump = (k: string) => counts.set(k, (counts.get(k) ?? 0) + 1);
  for (const c of changes) {
    if (c.type === "add") bump(`${NAMES[c.node.type] ?? "block"} added`);
    if (c.type === "del") bump(`${NAMES[c.node.type] ?? "block"} removed`);
    if (c.type === "change") bump(`${NAMES[c.after.type] ?? "block"} ${["image", "attachment"].includes(c.after.type) ? "replaced" : "edited"}`);
  }
  return [...counts].map(([k, n]) => `${n} ${k}`).join(", ") || "no changes";
}

export function changeCount(changes: BlockChange[]): number {
  return changes.filter((c) => c.type !== "same").length;
}
