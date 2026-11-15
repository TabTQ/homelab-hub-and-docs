import { describe, expect, it } from "vitest";
import { badgesFor, cleanSlugInput, dialogRules, githubToast, portfolioText, slugFromTitle, slugProblem, statusText, targetsBody, type DialogInput, type GithubStatus } from "./githubUi";

const base: DialogInput = {
  live: false, githubAvailable: true, localOn: true, host: "brain.example.com", hostOk: true, githubOn: true, slug: "brain", slugOk: true,
  problems: 0, busy: false, currentHost: null, currentSlug: null,
};

describe("slug from title", () => {
  it("makes a folder name", () => {
    expect(slugFromTitle("Obsidian web brain")).toBe("obsidian-web-brain");
    expect(slugFromTitle("  Café: Naïve  design!! ")).toBe("cafe-naive-design");
    expect(slugFromTitle("---")).toBe("");
    expect(slugFromTitle("x".repeat(100))).toHaveLength(63);
  });
  it("avoids reserved names", () => {
    expect(slugFromTitle("Portfolio")).toBe("portfolio-page");
    expect(slugProblem("api")).toMatch(/reserved/);
  });
  it("validates", () => {
    expect(slugProblem("network-map")).toBeNull();
    expect(slugProblem("")).not.toBeNull();
    expect(slugProblem("-x")).not.toBeNull();
    expect(slugProblem("Upper")).not.toBeNull();
    expect(cleanSlugInput("My Page")).toBe("my-page");
  });
});

describe("dialog rules", () => {
  it("publishes when every switch that is on is valid", () => {
    expect(dialogRules(base).canPublish).toBe(true);
    expect(dialogRules({ ...base, hostOk: false }).canPublish).toBe(false);
    expect(dialogRules({ ...base, slugOk: false }).canPublish).toBe(false);
    expect(dialogRules({ ...base, slug: "api" }).canPublish).toBe(false);
    expect(dialogRules({ ...base, problems: 1 }).canPublish).toBe(false);
    expect(dialogRules({ ...base, busy: true }).canPublish).toBe(false);
  });
  it("allows one place alone and never none", () => {
    expect(dialogRules({ ...base, githubOn: false }).canPublish).toBe(true);
    expect(dialogRules({ ...base, localOn: false, host: "" }).canPublish).toBe(true);
    expect(dialogRules({ ...base, localOn: false, githubOn: false }).canPublish).toBe(false);
    expect(dialogRules({ ...base, localOn: false, host: "", githubOn: false, githubAvailable: false }).canPublish).toBe(false);
  });
  it("ignores a GitHub switch when GitHub is off in the config", () => {
    expect(dialogRules({ ...base, githubAvailable: false, githubOn: true, slug: "!!" }).canPublish).toBe(true);
  });
  it("labels unpublish and says when save unpublishes", () => {
    const live = { ...base, live: true, currentHost: "brain.example.com", currentSlug: "brain" };
    expect(dialogRules(live).unpublishLabel).toBe("Unpublish everywhere");
    expect(dialogRules({ ...live, currentSlug: null }).unpublishLabel).toBe("Unpublish");
    expect(dialogRules({ ...base }).showUnpublish).toBe(false);
    const off = dialogRules({ ...live, localOn: false, githubOn: false });
    expect(off.saveUnpublishes).toBe(true);
    expect(off.canSave).toBe(true);
    expect(off.takesLocalOffline && off.takesGithubOffline).toBe(true);
    expect(dialogRules(live).takesLocalOffline).toBe(false);
    expect(dialogRules({ ...live, host: "other.example.com" }).takesLocalOffline).toBe(true);
  });
});

describe("request body", () => {
  it("leaves the slug key out when GitHub is off", () => {
    expect(targetsBody({ localOn: true, host: "a.example.com", githubAvailable: false, githubOn: true, slug: "a", version: 3 }))
      .toEqual({ publicHost: "a.example.com", version: 3 });
  });
  it("sends null for a switch that is off", () => {
    expect(targetsBody({ localOn: false, host: "a.example.com", githubAvailable: true, githubOn: true, slug: "a", version: 3 }))
      .toEqual({ publicHost: null, githubSlug: "a", version: 3 });
    expect(targetsBody({ localOn: true, host: "a.example.com", githubAvailable: true, githubOn: false, slug: "a", version: 3 }).githubSlug).toBeNull();
  });
});

describe("badges and status text", () => {
  it("shows badges only for live pages", () => {
    expect(badgesFor({ state: "public", publicHost: "a.example.com", githubSlug: "a" })).toEqual({ local: true, github: true });
    expect(badgesFor({ state: "public-changed", publicHost: null, githubSlug: "a" })).toEqual({ local: false, github: true });
    expect(badgesFor({ state: "internal", publicHost: "a.example.com", githubSlug: "a" })).toEqual({ local: false, github: false });
    expect(badgesFor({ state: "public", publicHost: "a.example.com" })).toEqual({ local: true, github: false });
  });
  const s = (state: GithubStatus["state"], more: Partial<GithubStatus> = {}): GithubStatus =>
    ({ enabled: true, dryRun: false, state, message: null, lastSyncedAt: null, siteUrl: null, files: null, ...more });
  it("maps each state", () => {
    expect(statusText(null)).toBeNull();
    expect(statusText(s("off"))).toBeNull();
    expect(statusText({ ...s("live"), enabled: false })).toBeNull();
    expect(statusText(s("live", { files: 14, lastSyncedAt: "x" }), () => "today")).toMatchObject({ title: "GitHub Pages: live", tone: "up", detail: "Last sent today · 14 files" });
    expect(statusText(s("building"))?.tone).toBe("warn");
    expect(statusText(s("pending"))?.title).toBe("GitHub Pages: updating");
    expect(statusText(s("error", { message: "401" }))).toMatchObject({ tone: "down", detail: "401" });
    expect(statusText(s("idle"))?.tone).toBe("unknown");
    expect(statusText(s("live", { dryRun: true }))?.title).toContain("dry run");
  });
  it("words the publish toast", () => {
    expect(githubToast({ github: { state: "pending" } })).toMatch(/about a minute/);
    expect(githubToast({ github: { state: "error", message: "bad token" } })).toMatch(/bad token/);
    expect(githubToast({})).toBe("");
    expect(githubToast({ github: { state: "off" } })).toBe("");
  });
  it("words the portfolio panel", () => {
    const p = { enabled: true, deployed: false, deployedAt: null, behind: false, url: "https://example.com/p/", sync: { state: "idle", message: null } };
    expect(portfolioText(p).title).toBe("GitHub Pages: not deployed");
    expect(portfolioText({ ...p, deployed: true, deployedAt: "t" }, () => "now")).toMatchObject({ title: "GitHub Pages: up to date", tone: "up" });
    expect(portfolioText({ ...p, deployed: true, behind: true }).tone).toBe("warn");
    expect(portfolioText({ ...p, sync: { state: "error", message: "m" } })).toMatchObject({ title: "GitHub Pages: deploy failed", tone: "down" });
  });
});
