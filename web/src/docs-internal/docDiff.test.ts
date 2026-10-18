import { describe, expect, it } from "vitest";
import { blockDiff, changeCount, summarize, wordDiff } from "./docDiff";
import type { PMNode } from "../shared/docs/types";

const p = (text: string): PMNode => ({ type: "paragraph", content: [{ type: "text", text }] });
const h = (text: string): PMNode => ({ type: "heading", attrs: { level: 2 }, content: [{ type: "text", text }] });
const doc = (...content: PMNode[]): PMNode => ({ type: "doc", content });

describe("doc diff", () => {
  it("finds added, removed and edited blocks", () => {
    const before = doc(h("Routing"), p("Every page has a path under /docs."), p("Gone"));
    const after = doc(h("Routing"), p("Every page has its own hostname."), h("Troubleshooting"));
    const d = blockDiff(before, after);
    expect(d.map((c) => c.type)).toEqual(["same", "change", "del", "add"]);
    const edit = d[1];
    if (edit.type !== "change") throw new Error("expected a change");
    expect(edit.words.filter((w) => w.type === "del").map((w) => w.text).join("")).toContain("path");
    expect(edit.words.filter((w) => w.type === "add").map((w) => w.text).join("")).toContain("hostname");
    expect(changeCount(d)).toBe(3);
  });

  it("handles empty sides and summarizes", () => {
    expect(blockDiff(null, doc(h("New"))).map((c) => c.type)).toEqual(["add"]);
    expect(blockDiff(doc(p("x")), doc()).map((c) => c.type)).toEqual(["del"]);
    expect(summarize(blockDiff(doc(p("a")), doc(p("a"), h("Troubleshooting"))))).toBe("1 section added");
    expect(summarize(blockDiff(doc(p("a")), doc(p("a"))))).toBe("no changes");
  });

  it("diffs words", () => {
    const w = wordDiff("a b c", "a x c");
    expect(w.filter((x) => x.type !== "same").map((x) => `${x.type}:${x.text}`)).toEqual(["del:b", "add:x"]);
  });
});
