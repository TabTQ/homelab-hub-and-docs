import { describe, expect, it } from "vitest";
import { filterSlash, SLASH_ITEMS } from "./slash";

describe("slash menu", () => {
  it("shows everything for an empty query", () => {
    expect(filterSlash("")).toHaveLength(SLASH_ITEMS.length);
  });
  it("matches labels, hints and keywords", () => {
    expect(filterSlash("tab").map((i) => i.id)).toEqual(["table"]);
    expect(filterSlash("flowchart").map((i) => i.id)).toEqual(["mermaid"]);
    expect(filterSlash("warning").map((i) => i.id)).toEqual(["callout"]);
    expect(filterSlash("zzz")).toEqual([]);
  });
  it("puts label prefix matches first", () => {
    expect(filterSlash("c")[0].label.toLowerCase().startsWith("c")).toBe(true);
    expect(filterSlash("list").map((i) => i.id)).toEqual(expect.arrayContaining(["bullet", "ordered", "task"]));
  });
});
