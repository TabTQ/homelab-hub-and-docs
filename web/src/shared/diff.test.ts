import { describe, expect, it } from "vitest";
import { diffLines, hasChanges } from "./diff";

describe("diffLines", () => {
  it("reports identical text as unchanged", () => {
    const d = diffLines("a\nb\nc", "a\nb\nc");
    expect(hasChanges(d)).toBe(false);
    expect(d.map((l) => l.text)).toEqual(["a", "b", "c"]);
  });
  it("marks added and removed lines in place", () => {
    const d = diffLines("a\nb\nc", "a\nx\nb\nc\nd");
    expect(d.filter((l) => l.type === "add").map((l) => l.text)).toEqual(["x", "d"]);
    expect(d.filter((l) => l.type === "del")).toEqual([]);
    const r = diffLines("a\nb\nc", "a\nc");
    expect(r.filter((l) => l.type === "del").map((l) => l.text)).toEqual(["b"]);
  });
  it("shows a changed line as remove then add", () => {
    const d = diffLines("title: A\nx: 1", "title: B\nx: 1");
    expect(d[0]).toEqual({ type: "del", text: "title: A" });
    expect(d[1]).toEqual({ type: "add", text: "title: B" });
    expect(d[2]).toEqual({ type: "same", text: "x: 1" });
  });
  it("rebuilds both sides exactly", () => {
    const a = "1\n2\n3\n4\n5\n6", b = "1\n3\n4\nX\n5\n7\n8";
    const d = diffLines(a, b);
    expect(d.filter((l) => l.type !== "add").map((l) => l.text).join("\n")).toBe(a);
    expect(d.filter((l) => l.type !== "del").map((l) => l.text).join("\n")).toBe(b);
  });
  it("copes with empty and huge inputs", () => {
    expect(hasChanges(diffLines("", "x"))).toBe(true);
    const big = Array.from({ length: 5000 }, (_, i) => "line " + i).join("\n");
    expect(diffLines(big, big + "\nmore").length).toBeGreaterThan(0);
  });
});
