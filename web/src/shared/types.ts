// Shapes of the JSON the backend serves. Kept in one place so a backend change shows up as a type error.

export interface Stat { value: string; label: string; sub: string }
export interface Profile {
  name: string; title: string; tagline?: string; location?: string; status?: string; brand?: string; summary?: string;
  email?: string; phone?: string; bio: string[]; socials: Record<string, string>; stats: Stat[]; skills: string[]; t: string;
}
export interface Experience {
  id: string; dbId: number; role: string; company: string; client: string | null; duration: string; location: string | null;
  summary: string; highlights: string[]; skills: string[]; tags: string[]; t: string;
}
export interface Metric { label: string; before?: string; after?: string; emphasis?: string }
export interface Deliverable { key: string; title: string; tag: string; body: string }
export interface Project {
  id: string; dbId: number; kind: "flagship" | "small"; title: string; description: string; repo: string | null; live: string | null;
  type: string | null; skills: string[]; tags: string[]; t: string;
  case?: { client: string | null; role: string | null; duration: string | null; context: string | null; metrics: Metric[]; platform: string[] };
  deliverables?: Deliverable[];
}
export interface Education { id: string; dbId: number; type: string; title: string; inst: string; date: string; desc: string | null; url: string | null; skills: string[]; t: string }
export interface HwRow { component: string; spec: string }
export interface Container { dbId: number; id: string; name: string; ip: string | null; purpose: string | null; res: string | null; status: string; skills: string[]; t: string }
export interface Service { dbId: number; id: string; name: string; ct: string | null; port: string | null; fn: string | null; skills: string[]; t: string }
export interface NetEntity { dbId: number; id: string; type: string; name: string; spec: Record<string, unknown>; skills: string[]; t: string }
export interface Device {
  dbId: number; id: string; key: string; cat: string; name: string; ip: string | null; summary: string | null; url?: string | null; hw: HwRow[]; skills: string[]; t: string;
  containers: Container[]; services: Service[]; network: NetEntity[];
}
export interface SkillRef { id: number; l: string; n: number }
export interface SkillGroup { id: number; name: string; icon: string; skills: SkillRef[] }
export interface Portfolio {
  profile: Profile; experience: Experience[]; projects: Project[]; education: Education[]; homelab: Device[]; skills: SkillGroup[];
}
export interface SnapshotEnvelope { schemaVersion: number; generatedAt: string; data: Portfolio }

// ---- dashboard ----
export interface ItemView { kind: "item"; id: string; name: string; href: string | null; icon: string | null; description: string | null; action: string | null; widget: string | null; statusId: string | null }
export interface GroupView { kind: "group"; id: string; name: string; entries: EntryView[] }
export type EntryView = ItemView | GroupView;
export interface ActionView { id: string; label: string; confirm: string; statusId: string | null; type: "wol" | "proxmox-node" | "proxmox-guest"; mac?: string; node?: string; command?: string; kind?: string; vmid?: number; stopMode?: string }
export interface DashboardView {
  title: string; theme: string; widgets: { type: string; options: Record<string, string> }[]; pinned: string[]; groups: GroupView[]; actions: ActionView[];
  config: { health: string; errors: number; warnings: number; missingSecrets: string[]; loadedAt: string };
  /** Optional pages the server has switched on (older servers omit it). */
  features?: { terminal: boolean; docker?: boolean };
  /** dashboard.yaml docs.url: the Docs app on its own LXC (a plain link). */
  docsUrl?: string | null;
  portfolioUrl?: string | null;
}
export type LightState = "up" | "down" | "degraded" | "unknown";
export interface StatusEntry { id: string; state: LightState; latencyMs: number | null; detail: string | null; checkedAt: string | null; since: string | null }
export interface WidgetResponse { state: "ok" | "stale" | "error"; type: string; data: Record<string, any> | null; fetchedAt?: string; error?: { code: string; message: string } }

export interface Prepared { token: string; expiresAt: string; actionId: string; title: string; kind: string; confirm: "click" | "type-name" | "none"; word: string | null; impact: string[]; desired: string | null }
export interface Executed { ok: boolean; message: string; taskId: string | null }
export interface TaskStatus { id: string; label: string; state: "RUNNING" | "OK" | "FAILED" | "TIMEOUT" | "UNKNOWN"; detail: string | null }

export interface ConfigIssue { level: "ERROR" | "WARNING"; path: string; line: number; message: string }
export interface ConfigState { text: string; sha256: string; health: string; issues: ConfigIssue[]; loadedAt: string }
export interface ConfigVersion { id: number; ts: string; sha256: string; source: string; note: string | null; sizeBytes: number }

export interface FieldSchema { api: string; label: string; type: string; required: boolean; max: number; allowed: string[]; help: string | null }
export interface ResourceSchema { path: string; title: string; singleton: boolean; parent: { api: string; path: string } | null; skills: boolean; tags: boolean; fields: FieldSchema[] }
export type Row = Record<string, any> & { id: number; version: number };
