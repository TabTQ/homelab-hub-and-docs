import { describe, expect, it } from "vitest";
import { assetHref, bundleBase, staticDataFile } from "./static";

function doc(content: string | null) {
  document.head.querySelectorAll('meta[name="static-data"]').forEach((m) => m.remove());
  if (content !== null) {
    const m = document.createElement("meta");
    m.setAttribute("name", "static-data");
    m.setAttribute("content", content);
    document.head.append(m);
  }
  return document;
}

describe("staticDataFile", () => {
  it("is null without the tag, so the app uses the API", () => expect(staticDataFile(doc(null))).toBeNull());
  it("returns the relative file name", () => {
    expect(staticDataFile(doc("page.json"))).toBe("page.json");
    expect(staticDataFile(doc("data/portfolio.json"))).toBe("data/portfolio.json");
  });
  it.each(["/page.json", "https://example.com/p.json", "../page.json", "a/../../p.json", "", "//example.com/p.json", "a\\b.json"])(
    "rejects %s", (v) => expect(() => staticDataFile(doc(v))).toThrow());
});

describe("assetHref", () => {
  const assets = { abc: { file: "a/abc.png" }, plain: {} };
  it("uses the listed file in static mode", () => expect(assetHref("abc", assets, true)).toBe("a/abc.png"));
  it("uses /a/<sha> in normal mode", () => expect(assetHref("abc", assets, false)).toBe("/a/abc"));
  it("falls back to /a/<sha> when no file is listed", () => expect(assetHref("plain", assets, true)).toBe("/a/plain"));
});

describe("bundleBase", () => {
  it("is relative in static mode", () => expect(bundleBase("x1", true)).toBe("app/x1/"));
  it("is absolute otherwise", () => expect(bundleBase("x1", false)).toBe("/app/x1/"));
});
