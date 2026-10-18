// Types and small helpers for docs-internal's API (docs plan 5.3).
import { api } from "../shared/api";
import type { PMNode, WebappSettings } from "../shared/docs/types";
import type { PageState, TreeItem } from "./treeModel";

export const A = "/api/admin/docs";

export interface BundleView { seq: number; sha256: string; size: number; createdAt: string; draft: boolean; live: boolean; files: { path: string; size: number }[] }

export interface PageDetail {
  id: number; parentId: number | null; kind: "rich" | "webapp"; title: string; body: PMNode | WebappSettings;
  publicHost: string | null; publishedVersion: number | null; publishedAt: string | null; updatedAt: string; createdAt: string;
  version: number; state: PageState; latestVersion: number; breadcrumbs: { id: number; title: string }[]; childCount: number;
  draftBundle?: BundleView; publishedBundle?: BundleView;
}

export interface DocsConfig { hubUrl: string; domains: string[]; maxAssetMb: number; maxBundleMb: number; cdns: Record<string, string> }

export interface VersionItem { version: number; kind: string; title: string; bundleSeq: number | null; note: string | null; createdAt: string; live: boolean }

export interface PublishCheck {
  draftVersion: number; publishedVersion: number | null; publishedAt: string | null; publicHost: string | null; state: PageState;
  domains: string[];
  links: { pageId: number; title: string | null; public: boolean; href: string | null; missing: boolean }[];
  embeds: { pageId: number; title: string | null; bundleSeq: number | null; size: number | null; source: "published" | "draft" | "missing" }[];
  files: { sha256: string; mime: string; size: number; name: string }[];
  fileBytes: number; bundle: BundleView | null; problems: string[]; liveBody: PMNode | WebappSettings | null; liveTitle: string | null;
}

export const loadTree = () => api<TreeItem[]>(`${A}/tree`);
export const loadPage = (id: number) => api<PageDetail>(`${A}/pages/${id}`);
export const assetUrl = (sha: string) => `${A}/assets/${sha}`;

export function when(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const today = new Date();
  const time = d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  if (d.toDateString() === today.toDateString()) return `today ${time}`;
  return `${d.toLocaleDateString(undefined, { day: "numeric", month: "short", year: d.getFullYear() === today.getFullYear() ? undefined : "numeric" })} ${time}`;
}

export function ago(iso: string | null | undefined): string {
  if (!iso) return "";
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return when(iso);
}

export const go = (hash: string) => { location.hash = hash; };
