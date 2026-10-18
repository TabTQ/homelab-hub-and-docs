import { render, screen } from "@testing-library/react";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { DocRenderer, headings, type RenderContext } from "./DocRenderer";
import { acceptHeight, frameSrc } from "./frame";
import { safeHref } from "./safe";
import type { PMNode } from "./types";

const SHA = "a".repeat(64);
const ctx: RenderContext = {
  assetUrl: (s) => `/a/${s}`,
  dark: false,
  pageLink: (a) => ({ href: a.href ?? null }),
  embed: (a, key) => <div key={key} data-testid="embed">{a.bundle}</div>,
};
const doc = (...content: PMNode[]): PMNode => ({ type: "doc", content });
const text = (t: string, marks?: PMNode["marks"]): PMNode => ({ type: "text", text: t, marks });

describe("DocRenderer", () => {
  it("renders every node type", () => {
    const { container } = render(<DocRenderer ctx={ctx} doc={doc(
      { type: "heading", attrs: { level: 2 }, content: [text("Routing")] },
      { type: "paragraph", content: [text("b", [{ type: "bold" }]), text("i", [{ type: "italic" }]), text("u", [{ type: "underline" }]),
        text("s", [{ type: "strike" }]), text("c", [{ type: "code" }]), { type: "hardBreak" }, text("link", [{ type: "link", attrs: { href: "https://example.org" } }])] },
      { type: "bulletList", content: [{ type: "listItem", content: [{ type: "paragraph", content: [text("one")] }] }] },
      { type: "orderedList", attrs: { start: 3 }, content: [{ type: "listItem", content: [{ type: "paragraph" }] }] },
      { type: "taskList", content: [{ type: "taskItem", attrs: { checked: true }, content: [{ type: "paragraph", content: [text("done")] }] }] },
      { type: "blockquote", content: [{ type: "paragraph", content: [text("quoted")] }] },
      { type: "horizontalRule" },
      { type: "codeBlock", attrs: { language: "yaml" }, content: [text("a: 1")] },
      { type: "table", content: [{ type: "tableRow", content: [{ type: "tableHeader", attrs: { colspan: 2 }, content: [{ type: "paragraph", content: [text("H")] }] }] }] },
      { type: "image", attrs: { sha: SHA, alt: "net", caption: "Network", width: 50 } },
      { type: "callout", attrs: { type: "warning" }, content: [{ type: "paragraph", content: [text("careful")] }] },
      { type: "details", attrs: { open: true }, content: [{ type: "detailsSummary", content: [text("More")] }, { type: "detailsContent", content: [{ type: "paragraph", content: [text("hidden")] }] }] },
      { type: "attachment", attrs: { sha: SHA, name: "guide.pdf", size: 2048 } },
      { type: "mermaid", attrs: { source: "flowchart TD", lightSvg: SHA, darkSvg: "b".repeat(64) } },
      { type: "paragraph", content: [{ type: "pageLink", attrs: { label: "Public one", href: "https://flow.example.test/" } }, { type: "pageLink", attrs: { label: "Internal one" } }] },
      { type: "webappEmbed", attrs: { bundle: "c".repeat(64), height: 300 } },
    )} />);
    expect(container.querySelector("h2#routing")?.textContent).toBe("Routing");
    expect(container.querySelector("strong")?.textContent).toBe("b");
    expect(container.querySelector("a[href='https://example.org']")?.getAttribute("rel")).toContain("noopener");
    expect(container.querySelector("ol")?.getAttribute("start")).toBe("3");
    expect(container.querySelector(".dx-tasks input")).toHaveProperty("checked", true);
    expect(container.querySelector("th")?.getAttribute("colspan")).toBe("2");
    expect(container.querySelector("figure img")?.getAttribute("src")).toBe(`/a/${SHA}`);
    expect(screen.getByText("Network").tagName).toBe("FIGCAPTION");
    expect(container.querySelector(".dx-callout.warning")).not.toBeNull();
    expect(container.querySelector("details")?.hasAttribute("open")).toBe(true);
    expect(container.querySelector("a.dx-attach")?.getAttribute("download")).toBe("guide.pdf");
    expect(container.querySelector(".dx-mermaid img")?.getAttribute("src")).toBe(`/a/${SHA}`);
    expect(screen.getByText("Public one").tagName).toBe("A");
    expect(screen.getByText("Internal one").tagName).toBe("SPAN");
    expect(screen.getByTestId("embed").textContent).toBe("c".repeat(64));
    expect(screen.getByText("a: 1").closest(".dx-code")).not.toBeNull();
  });

  it("shows the dark diagram in dark mode", () => {
    const { container } = render(<DocRenderer ctx={{ ...ctx, dark: true }} doc={doc({ type: "mermaid", attrs: { source: "x", lightSvg: SHA, darkSvg: "b".repeat(64) } })} />);
    expect(container.querySelector("img")?.getAttribute("src")).toBe(`/a/${"b".repeat(64)}`);
  });

  it("drops unknown nodes and unsafe links", () => {
    const { container } = render(<DocRenderer ctx={ctx} doc={doc(
      { type: "html", attrs: { html: "<script>alert(1)</script>" } } as PMNode,
      { type: "paragraph", content: [text("x", [{ type: "link", attrs: { href: "javascript:alert(1)" } }]), text("y", [{ type: "link", attrs: { href: "data:text/html,1" } }])] },
      { type: "paragraph", content: [{ type: "pageLink", attrs: { label: "evil", href: "javascript:alert(1)" } }] },
    )} />);
    expect(container.innerHTML).not.toContain("script");
    expect(container.querySelectorAll("a")).toHaveLength(0);
    expect(container.textContent).toContain("xy");
    expect(render(<DocRenderer ctx={ctx} doc={{ type: "paragraph" }} />).container.innerHTML).toBe("");
  });

  it("lists headings with the same ids", () => {
    expect(headings(doc({ type: "heading", attrs: { level: 2 }, content: [text("Setup")] }, { type: "heading", attrs: { level: 3 }, content: [text("Setup")] })))
      .toEqual([{ id: "setup", text: "Setup", level: 2 }, { id: "setup-2", text: "Setup", level: 3 }]);
  });
});

describe("links and frames", () => {
  it("allows only absolute http, https and mailto links", () => {
    expect(safeHref("https://x.org")).toBe("https://x.org");
    expect(safeHref("mailto:me@x.org")).toBe("mailto:me@x.org");
    for (const bad of ["javascript:alert(1)", " JavaScript:x", "/relative", "data:text/html,x", "java\nscript:x", 42]) expect(safeHref(bad)).toBeNull();
  });

  it("accepts height reports only from its own frame, clamped", () => {
    const win = {};
    expect(acceptHeight({ source: win, data: { type: "hub:height", px: 812.4 } }, win)).toBe(812);
    expect(acceptHeight({ source: win, data: { type: "hub:height", px: 99999 } }, win)).toBe(4000);
    expect(acceptHeight({ source: win, data: { type: "hub:height", px: 3 } }, win)).toBe(150);
    expect(acceptHeight({ source: {}, data: { type: "hub:height", px: 500 } }, win)).toBeNull();
    expect(acceptHeight({ source: win, data: { type: "other", px: 500 } }, win)).toBeNull();
    expect(acceptHeight({ source: win, data: { type: "hub:height", px: "500" } }, win)).toBeNull();
    expect(acceptHeight({ source: win, data: { type: "hub:height", px: NaN } }, win)).toBeNull();
    expect(acceptHeight({ source: null, data: { type: "hub:height", px: 500 } }, null)).toBeNull();
  });

  it("builds the frame address", () => {
    expect(frameSrc("/app/abc", "dark", true)).toBe("/app/abc/index.html?theme=dark");
    expect(frameSrc("/app/abc/", "light", false)).toBe("/app/abc/index.html");
  });

  it("never renders HTML from data in the Docs code", () => {
    const walk = (d: string): string[] => readdirSync(d).flatMap((f) => { const p = join(d, f); return statSync(p).isDirectory() ? walk(p) : [p]; });
    const files = ["src/shared/docs", "src/docs-public", "src/docs-internal"].flatMap(walk).filter((f) => /\.tsx?$/.test(f) && !f.endsWith(".test.tsx"));
    for (const f of files) expect(readFileSync(f, "utf8"), f).not.toMatch(/dangerouslySetInnerHTML|innerHTML\s*=/);
  });
});
