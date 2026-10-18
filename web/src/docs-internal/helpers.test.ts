import { describe, expect, it } from "vitest";
import { parseRoute } from "./App";
import { joinHost, splitHost } from "./PublishDialog";
import { introToText, textToIntro } from "./WebappPage";

describe("docs-internal helpers", () => {
  it("parses routes", () => {
    expect(parseRoute("#/portfolio")).toEqual({ name: "portfolio" });
    expect(parseRoute("#/p/12")).toEqual({ name: "page", id: 12 });
    expect(parseRoute("#/p/12/edit")).toEqual({ name: "edit", id: 12 });
    expect(parseRoute("#/p/12/history")).toEqual({ name: "history", id: 12 });
    expect(parseRoute("#/p/12/publish")).toEqual({ name: "publish", id: 12 });
    expect(parseRoute("#/trash")).toEqual({ name: "trash" });
    expect(parseRoute("#/p/x")).toEqual({ name: "home" });
    expect(parseRoute("")).toEqual({ name: "home" });
  });

  it("splits and joins hostnames with the configured domains", () => {
    expect(splitHost("tunnel.domain.com", ["domain.com", "docs.example.org"])).toEqual({ sub: "tunnel", domain: "domain.com" });
    expect(splitHost("a.b.docs.example.org", ["example.org", "docs.example.org"])).toEqual({ sub: "a.b", domain: "docs.example.org" });
    expect(splitHost(null, ["domain.com"])).toEqual({ sub: "", domain: "domain.com" });
    expect(splitHost("x.other.net", ["domain.com"])).toEqual({ sub: "x.other.net", domain: "" });
    expect(joinHost("tunnel", "domain.com")).toBe("tunnel.domain.com");
    expect(joinHost("", "domain.com")).toBe("");
    expect(joinHost("page.example.org", "")).toBe("page.example.org");
  });

  it("turns the intro text into paragraphs and back", () => {
    const doc = textToIntro("Click a box.\n\nThen another.");
    expect(doc?.content).toHaveLength(3);
    expect(introToText(doc)).toBe("Click a box.\n\nThen another.");
    expect(textToIntro("  \n ")).toBeNull();
  });
});
