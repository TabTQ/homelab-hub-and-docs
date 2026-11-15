// Pure helpers for the GitHub Pages parts of the Docs editor: folder names, the dialog's rules and the status wording.
import type { PageState } from "./treeModel";

export const RESERVED_SLUGS = ["viewer", "portfolio", "assets", "a", "app", "api", "static", "index"];
const SLUG_RE = /^[a-z0-9][a-z0-9-]{0,62}$/;

export interface GithubConfig { enabled: boolean; siteUrl: string | null; dryRun: boolean }
export type GithubSyncState = "off" | "idle" | "pending" | "building" | "live" | "error";
export interface GithubStatus {
  enabled: boolean; dryRun: boolean; state: GithubSyncState; message: string | null; lastSyncedAt: string | null; siteUrl: string | null; files: number | null;
}

export type Tone = "up" | "warn" | "down" | "unknown";

/** A folder name from a page title: lower case, letters and digits, single dashes, at most 63 characters. */
export function slugFromTitle(title: string): string {
  const s = title.normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase()
    .replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 63).replace(/-+$/, "");
  if (!s) return "";
  return RESERVED_SLUGS.includes(s) ? `${s}-page`.slice(0, 63) : s;
}

/** Null when the folder name is fine, otherwise a short reason. */
export function slugProblem(slug: string): string | null {
  if (!slug) return "Enter a folder name.";
  if (RESERVED_SLUGS.includes(slug)) return `“${slug}” is reserved. Pick another name.`;
  if (!SLUG_RE.test(slug)) return "Use lower-case letters, digits and dashes. Start with a letter or digit. At most 63 characters.";
  return null;
}

/** Makes what the owner types safe to show: lower case, spaces become dashes. */
export function cleanSlugInput(v: string): string { return v.toLowerCase().replace(/\s+/g, "-"); }

export function githubGate(config: { github?: GithubConfig | null } | null | undefined): boolean { return !!config?.github?.enabled; }

export function githubPageUrl(siteUrl: string | null | undefined, slug: string): string {
  const base = siteUrl ? (siteUrl.endsWith("/") ? siteUrl : siteUrl + "/") : "";
  return `${base}${slug}/`;
}

export interface DialogInput {
  live: boolean;            // the page has a published version now
  githubAvailable: boolean; // config.github.enabled
  localOn: boolean; host: string; hostOk: boolean | null;       // hostOk: null = not checked
  githubOn: boolean; slug: string; slugOk: boolean | null;
  problems: number; busy: boolean;
  currentHost: string | null; currentSlug: string | null;       // what the page has now
}
export interface DialogRules {
  canPublish: boolean; canSave: boolean; saveUnpublishes: boolean; showUnpublish: boolean;
  unpublishLabel: string; takesLocalOffline: boolean; takesGithubOffline: boolean;
}

/** The dialog's enable and disable rules in one place, so they can be tested. */
export function dialogRules(i: DialogInput): DialogRules {
  const localOk = i.localOn && !!i.host && i.hostOk !== false;
  const ghOk = i.githubAvailable && i.githubOn && slugProblem(i.slug) === null && i.slugOk !== false;
  const anyOn = (i.localOn || (i.githubAvailable && i.githubOn));
  // Every switch that is on must be valid, and at least one must be on.
  const valid = anyOn && (!i.localOn || localOk) && (!(i.githubAvailable && i.githubOn) || ghOk);
  const bothLive = !!i.currentHost && !!i.currentSlug && i.live && i.githubAvailable;
  return {
    canPublish: valid && i.problems === 0 && !i.busy,
    canSave: !i.busy && (i.live ? (!anyOn || valid) : true),
    saveUnpublishes: i.live && !anyOn,
    showUnpublish: i.live,
    unpublishLabel: bothLive ? "Unpublish everywhere" : "Unpublish",
    takesLocalOffline: i.live && !!i.currentHost && (!i.localOn || i.host !== i.currentHost),
    takesGithubOffline: i.live && i.githubAvailable && !!i.currentSlug && (!i.githubOn || i.slug !== i.currentSlug),
  };
}

/** Where the page is shown after the dialog, as the body of POST /publish and PUT /targets. The slug key is left out when GitHub is off. */
export function targetsBody(i: { localOn: boolean; host: string; githubAvailable: boolean; githubOn: boolean; slug: string; version: number }) {
  const body: { publicHost: string | null; githubSlug?: string | null; version: number } = { publicHost: i.localOn && i.host ? i.host : null, version: i.version };
  if (i.githubAvailable) body.githubSlug = i.githubOn && i.slug ? i.slug : null;
  return body;
}

export function badgesFor(item: { state: PageState; publicHost: string | null; githubSlug?: string | null }): { local: boolean; github: boolean } {
  const live = item.state !== "internal";
  return { local: live && !!item.publicHost, github: live && !!item.githubSlug };
}

export interface StatusText { title: string; detail: string; tone: Tone }

/** Words for the top bar and tooltips. Returns null when GitHub is off, so nothing is drawn. */
export function statusText(s: GithubStatus | null, fmtWhen: (iso: string | null) => string = (x) => x ?? ""): StatusText | null {
  if (!s || !s.enabled || s.state === "off") return null;
  const dry = s.dryRun ? " (dry run)" : "";
  switch (s.state) {
    case "live": {
      const parts = [s.lastSyncedAt ? `Last sent ${fmtWhen(s.lastSyncedAt)}` : "", s.files != null ? `${s.files} files` : ""].filter(Boolean);
      return { title: `GitHub Pages: live${dry}`, detail: parts.join(" · "), tone: "up" };
    }
    case "building": return { title: `GitHub Pages: building${dry}`, detail: s.message || "Sent. GitHub is updating the site, usually under a minute.", tone: "warn" };
    case "pending": return { title: `GitHub Pages: updating${dry}`, detail: s.message || "Changes are queued and go out in a moment.", tone: "warn" };
    case "error": return { title: "GitHub Pages: not updated", detail: s.message || "The last update failed. The local pages are not affected.", tone: "down" };
    default: return { title: `GitHub Pages: ready${dry}`, detail: s.message || "Nothing waiting to be sent.", tone: "unknown" };
  }
}

export const isBusyState = (state: GithubSyncState | undefined) => state === "pending" || state === "building";

/** The toast sentence about GitHub after a publish. Empty when GitHub was not part of it. */
export function githubToast(r: { githubUrl?: string | null; github?: { state: string; message?: string | null } | null } | null | undefined): string {
  const g = r?.github;
  if (!g) return "";
  if (g.state === "error") return `GitHub Pages was not updated: ${g.message || "unknown error"} Use Retry in the top bar.`;
  if (g.state === "pending" || g.state === "building") return `GitHub Pages is updating, about a minute${r?.githubUrl ? `: ${r.githubUrl}` : "."}`;
  if (g.state === "off") return "";
  return `GitHub Pages: ${r?.githubUrl ?? "live"}`;
}

export interface PortfolioGithub {
  enabled: boolean; deployed: boolean; deployedAt: string | null; behind: boolean; url: string | null; sync: { state: string; message: string | null } | null;
}

/** The portfolio panel's line. Four states as in the mockup: not deployed, up to date, behind, failed. */
export function portfolioText(p: PortfolioGithub, fmtWhen: (iso: string | null) => string = (x) => x ?? ""): StatusText {
  const sync = p.sync?.state;
  if (sync === "error") return { title: "GitHub Pages: deploy failed", detail: p.sync?.message || "GitHub could not be reached. Nothing changed there.", tone: "down" };
  if (sync === "pending" || sync === "building") return { title: "GitHub Pages: updating", detail: p.sync?.message || "Sent. GitHub is updating the site, usually under a minute.", tone: "warn" };
  if (!p.deployed) return { title: "GitHub Pages: not deployed", detail: p.url ?? "", tone: "unknown" };
  const when = p.deployedAt ? ` (${fmtWhen(p.deployedAt)})` : "";
  if (p.behind) return { title: "GitHub Pages: behind", detail: `The portfolio was edited after the last deploy${when}. The local site already shows the edits.`, tone: "warn" };
  return { title: "GitHub Pages: up to date", detail: `Deployed ${fmtWhen(p.deployedAt)}`, tone: "up" };
}
