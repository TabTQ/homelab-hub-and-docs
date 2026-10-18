import type { Container, RunSpec } from "./types";

export type Tone = "ok" | "warn" | "bad" | "off";

/** Colour of a container's state dot. */
export function tone(c: Pick<Container, "state" | "health">): Tone {
  if (c.health === "unhealthy") return "bad";
  if (c.state === "running") return c.health === "starting" ? "warn" : "ok";
  if (c.state === "restarting" || c.state === "paused" || c.state === "created") return "warn";
  if (c.state === "dead") return "bad";
  return "off";
}

export type StateFilter = "all" | "running" | "stopped" | "unhealthy";

export function matches(c: Container, query: string, filter: StateFilter): boolean {
  if (filter === "running" && c.state !== "running") return false;
  if (filter === "stopped" && c.state === "running") return false;
  if (filter === "unhealthy" && c.health !== "unhealthy" && c.state !== "restarting" && c.state !== "dead") return false;
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [c.name, c.image, c.project ?? "", c.ports].some((s) => s.toLowerCase().includes(q));
}

export interface ProjectGroup { project: string | null; stack: boolean; containers: Container[] }

/** Containers grouped by compose project (alphabetical), standalone containers last. */
export function groupByProject(list: Container[]): ProjectGroup[] {
  const map = new Map<string, ProjectGroup>();
  const loose: Container[] = [];
  for (const c of list) {
    if (!c.project) { loose.push(c); continue; }
    const g = map.get(c.project) ?? { project: c.project, stack: false, containers: [] };
    g.containers.push(c);
    g.stack = g.stack || c.stack != null;
    map.set(c.project, g);
  }
  const groups = [...map.values()].sort((a, b) => a.project!.localeCompare(b.project!));
  if (loose.length) groups.push({ project: null, stack: false, containers: loose });
  return groups;
}

/**
 * "0.0.0.0:8096->8096/tcp, [::]:8096->8096/tcp, 53/udp" -> ["8096→8096/tcp", "53/udp"]. IPv4 and IPv6 bindings of the same
 * mapping are shown once; a non-wildcard host address is kept.
 */
export function ports(text: string): string[] {
  if (!text) return [];
  const out: string[] = [];
  for (const raw of text.split(",")) {
    const p = raw.trim();
    if (!p) continue;
    const m = p.match(/^(?:(\[?[0-9a-fA-F:.]*\]?):)?(\d+(?:-\d+)?)->(\d+(?:-\d+)?\/\w+)$/);
    let label = p;
    if (m) {
      const host = m[1] && !["0.0.0.0", "::", "[::]", ""].includes(m[1]) ? m[1] + ":" : "";
      label = `${host}${m[2]}→${m[3]}`;
    }
    if (!out.includes(label)) out.push(label);
  }
  return out;
}

/** The host port of a published mapping, for an "open" link (only plain http-looking ports are offered). */
export function webPort(text: string): number | null {
  for (const p of ports(text)) {
    const m = p.match(/^(\d+)→\d+\/tcp$/);
    if (m) return Number(m[1]);
  }
  return null;
}

/** "2026-10-02T10:00:00.123456789Z message" -> time and text. */
export function splitLogLine(line: string): { ts: string | null; text: string } {
  const m = line.match(/^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d))\s?(.*)$/);
  return m ? { ts: m[1], text: m[2] } : { ts: null, text: line };
}

/** The time part of a docker timestamp, to the second: "10:00:00". */
export function shortTime(ts: string): string {
  const m = ts.match(/T(\d\d:\d\d:\d\d)/);
  return m ? m[1] : ts;
}

export function pct(n: number | null | undefined): string {
  if (n == null || Number.isNaN(n)) return "–";
  return n >= 10 ? `${Math.round(n)}%` : `${n.toFixed(1)}%`;
}

/** "120.5MiB / 3.704GiB" -> "120.5 MiB" (the used part). */
export function memUsed(usage: string | null | undefined): string {
  if (!usage) return "–";
  const used = usage.split("/")[0].trim();
  return used.replace(/([\d.]+)([A-Za-z]+)/, "$1 $2");
}

/**
 * False when docker reports no memory limit at all ("0B / 0B"): the host kernel has no memory cgroup (common on a
 * Raspberry Pi), so every container's usage reads 0 and is not real data.
 */
export function memAvailable(usage: string | null | undefined): boolean {
  if (!usage || !usage.includes("/")) return true;
  const limit = parseSize(usage.split("/")[1]);
  return limit == null || limit > 0;
}

const UNITS: Record<string, number> = {
  b: 1, kb: 1e3, mb: 1e6, gb: 1e9, tb: 1e12, kib: 1024, mib: 1024 ** 2, gib: 1024 ** 3, tib: 1024 ** 4,
};

/** docker's human sizes ("187MB", "1.2GB", "0B", "3.704GiB") -> bytes; null when unreadable. */
export function parseSize(text: string | null | undefined): number | null {
  const m = (text ?? "").trim().match(/^([\d.]+)\s*([kmgt]?i?b)$/i);
  if (!m) return null;
  const n = Number(m[1]);
  const unit = UNITS[m[2].toLowerCase()];
  return Number.isNaN(n) || unit == null ? null : n * unit;
}

/** Bytes as docker shows them (decimal units): 187000000 -> "187 MB". */
export function formatBytes(n: number): string {
  if (n < 1000) return `${Math.round(n)} B`;
  const units = ["kB", "MB", "GB", "TB"];
  let v = n, i = -1;
  while (v >= 1000 && i < units.length - 1) { v /= 1000; i++; }
  return `${v >= 100 ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
}

/** Text of a compose service equivalent to the run form, for "Save as stack instead". */
export function runToCompose(s: RunSpec): string {
  const service = (s.name.trim() || s.image.split("/").pop()!.split(":")[0].replace(/[^a-zA-Z0-9_.-]/g, "") || "app").toLowerCase();
  const q = (v: string) => (/^[A-Za-z0-9_./:@-]+$/.test(v) && !/^(true|false|yes|no|null|~|\d+(\.\d+)?)$/i.test(v) ? v : JSON.stringify(v));
  const lines = ["services:", `  ${service}:`, `    image: ${q(s.image.trim())}`];
  if (s.name.trim()) lines.push(`    container_name: ${q(s.name.trim())}`);
  lines.push(`    restart: ${s.restart || "unless-stopped"}`);
  const ps = s.ports.map((p) => p.trim()).filter(Boolean);
  if (ps.length) { lines.push("    ports:"); ps.forEach((p) => lines.push(`      - ${JSON.stringify(p)}`)); }
  const vs = s.volumes.filter((v) => v.source.trim() && v.target.trim());
  if (vs.length) { lines.push("    volumes:"); vs.forEach((v) => lines.push(`      - ${JSON.stringify(`${v.source.trim()}:${v.target.trim()}${v.readOnly ? ":ro" : ""}`)}`)); }
  const es = s.env.filter((e) => e.key.trim());
  if (es.length) { lines.push("    environment:"); es.forEach((e) => lines.push(`      ${e.key.trim()}: \${${e.key.trim()}}`)); }
  const cmd = s.command.filter((a) => a !== "");
  if (cmd.length) lines.push(`    command: [${cmd.map((a) => JSON.stringify(a)).join(", ")}]`);
  if (s.network.trim() && !["bridge", "host", "none"].includes(s.network.trim())) {
    lines.push("    networks: [" + q(s.network.trim()) + "]", "networks:", `  ${q(s.network.trim())}:`, "    external: true");
  } else if (s.network.trim() === "host") {
    lines.splice(3, 0, "    network_mode: host");
  }
  return lines.join("\n") + "\n";
}

/** The .env that goes with runToCompose: the values stay out of compose.yaml. */
export function runToEnv(s: RunSpec): string {
  return s.env.filter((e) => e.key.trim()).map((e) => `${e.key.trim()}=${e.value}`).join("\n") + (s.env.some((e) => e.key.trim()) ? "\n" : "");
}

/** Splits a command line typed in one box into arguments: spaces separate, quotes group, no shell expansion. */
export function splitArgs(text: string): string[] {
  const out: string[] = [];
  let cur = "";
  let quote: string | null = null;
  let has = false;
  for (const ch of text) {
    if (quote) {
      if (ch === quote) quote = null; else cur += ch;
    } else if (ch === "'" || ch === '"') {
      quote = ch; has = true;
    } else if (/\s/.test(ch)) {
      if (has || cur) { out.push(cur); cur = ""; has = false; }
    } else {
      cur += ch;
    }
  }
  if (has || cur) out.push(cur);
  return out;
}

export const STACK_RE = /^[a-z0-9][a-z0-9_-]{0,39}$/;

export const NEW_STACK = `services:
  app:
    image: nginx:alpine
    restart: unless-stopped
    ports:
      - "8080:80"
`;
