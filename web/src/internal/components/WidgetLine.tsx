import { fmtBytes, fmtRate, fmtUptime } from "../../shared/util";
import type { WidgetResponse } from "../../shared/types";

/** One short line under a service name, e.g. "CPU 3% · RAM 31% · LXC 3/6". Errors are shown, never hidden. */
export function widgetSummary(type: string, d: Record<string, any>): string {
  switch (type) {
    case "proxmox":
      return [`CPU ${d.cpuPercent}%`, `RAM ${d.memory?.percent}%`, `LXC ${d.lxc?.running}/${d.lxc?.total}`, `VM ${d.vms?.running}/${d.vms?.total}`, d.uptimeSeconds ? `up ${fmtUptime(d.uptimeSeconds)}` : ""].filter(Boolean).join(" · ");
    case "proxmoxbackupserver": {
      const stores = (d.datastores ?? []) as { name: string; percent: number }[];
      return stores.length ? stores.map((s) => `${s.name} ${s.percent}%`).join(" · ") : "no datastores";
    }
    case "filebrowser":
      return `${fmtBytes(d.usedBytes)} of ${fmtBytes(d.totalBytes)} (${d.percent}%)`;
    case "qbittorrent":
      return `↓ ${fmtRate(d.downloadBytesPerSec)} ↑ ${fmtRate(d.uploadBytesPerSec)} · ${d.leech} leech · ${d.seed} seed`;
    default:
      return "";
  }
}

const HINTS: Record<string, string> = {
  secret_missing: "secret missing", unauthorized: "credentials refused", unreachable: "unreachable", timeout: "timed out",
  cert_mismatch: "certificate changed", bad_response: "unexpected answer", not_configured: "needs configuration", request: "cannot load",
};

export function WidgetLine({ w }: { w: WidgetResponse | null }) {
  if (!w) return null;
  if (w.state === "error") return <span className="wline err" title={w.error?.message}>{HINTS[w.error?.code ?? ""] ?? "error"}</span>;
  const text = w.data ? widgetSummary(w.type, w.data) : "";
  if (w.state === "stale") return <span className="wline stale" title={`${text} · ${w.error?.message ?? "stale"}`}>{text} · stale</span>;
  return <span className="wline" title={text}>{text}</span>;
}
