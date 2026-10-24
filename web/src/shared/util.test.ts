import { describe, expect, it } from "vitest";
import { ago, deviceLinks, fmtBytes, fmtRate, fmtUptime, initials, projectLinks, safeHref } from "./util";

describe("util", () => {
  it("makes initials from names and symbols", () => {
    expect(initials("Proxmox VE")).toBe("PV");
    expect(initials("Grafana")).toBe("GR");
    expect(initials("qBittorrent")).toBe("QB");
    expect(initials("***")).toBe("?");
    expect(initials("")).toBe("?");
  });
  it("only ever renders http(s) links", () => {
    expect(safeHref("https://x.dev/a")).toBe("https://x.dev/a");
    expect(safeHref("http://192.168.10.5:3000/")).toBe("http://192.168.10.5:3000/");
    expect(safeHref("/portfolio")).toBe("/portfolio");
    for (const bad of ["javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,x", "vbscript:x", "file:///etc/passwd", " javascript:alert(1)"]) {
      expect(safeHref(bad), bad).toBeUndefined();
    }
    expect(safeHref(null)).toBeUndefined();
    expect(safeHref("")).toBeUndefined();
  });
  it("formats sizes, rates and uptime", () => {
    expect(fmtBytes(0)).toBe("0 B");
    expect(fmtBytes(1536)).toBe("1.5 KB");
    expect(fmtBytes(5155676160)).toBe("4.8 GB");
    expect(fmtRate(2048)).toBe("2.0 KB/s");
    expect(fmtUptime(74243)).toBe("20h 37m");
    expect(fmtUptime(400000)).toBe("4d 15h");
    expect(fmtUptime(90)).toBe("1m");
  });
  it("describes how long ago", () => {
    const now = Date.parse("2026-09-24T12:00:00Z");
    expect(ago("2026-09-24T11:59:58Z", now)).toBe("just now");
    expect(ago("2026-09-24T11:59:30Z", now)).toBe("30s ago");
    expect(ago("2026-09-24T11:50:00Z", now)).toBe("10 min ago");
    expect(ago("2026-09-24T08:00:00Z", now)).toBe("4 h ago");
    expect(ago(null, now)).toBe("never");
  });
});

import { slugify, SLUG_RE } from "./util";
describe("slugify", () => {
  it("makes what the server accepts from any title", () => {
    for (const t of ["Power System Monitor", "  Réseau  & VPN!! ", "a__b--c", "X".repeat(100), "2024 Report", "---", "ÄÖÜ café"]) {
      const s = slugify(t);
      expect(s === "" || SLUG_RE.test(s), `${t} -> ${s}`).toBe(true);
    }
    expect(slugify("Power System Monitor")).toBe("power-system-monitor");
    expect(slugify("Réseau & VPN")).toBe("reseau-vpn");
    expect(slugify("---")).toBe("");
    expect(slugify("x".repeat(100)).length).toBeLessThanOrEqual(64);
  });
});

describe("portfolio links", () => {
  it("lists a project's live page before its source, and drops what is missing", () => {
    expect(projectLinks({ repo: "https://github.com/x/y", live: "https://demo.example.org/" })).toEqual([
      { label: "Live page", href: "https://demo.example.org/" },
      { label: "Source on GitHub", href: "https://github.com/x/y" },
    ]);
    expect(projectLinks({ repo: "https://github.com/x/y", live: null })).toEqual([{ label: "Source on GitHub", href: "https://github.com/x/y" }]);
    expect(projectLinks({ repo: null, live: null })).toEqual([]);
  });
  it("never turns a non-http(s) project or device link into a link", () => {
    expect(projectLinks({ repo: "javascript:alert(1)", live: "data:text/html,x" })).toEqual([]);
    expect(deviceLinks({ url: "javascript:alert(1)" })).toEqual([]);
    expect(deviceLinks({ url: "ftp://example.org/x" })).toEqual([]);
  });
  it("gives a homelab device one interactive-page link when it has a url", () => {
    expect(deviceLinks({ url: "https://pve.example.org/" })).toEqual([{ label: "Interactive page", href: "https://pve.example.org/" }]);
    expect(deviceLinks({})).toEqual([]);
    expect(deviceLinks({ url: null })).toEqual([]);
  });
});
