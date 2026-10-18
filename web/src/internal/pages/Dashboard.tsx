import { useMemo, useState } from "react";
import { api } from "../../shared/api";
import type { ActionView, DashboardView, EntryView, GroupView, ItemView, LightState, StatusEntry } from "../../shared/types";
import { ago, fmtRate, fmtUptime, safeHref } from "../../shared/util";
import { Icon } from "../components/Icon";
import { WidgetLine } from "../components/WidgetLine";
import { useRunAction } from "../components/ActionRunner";
import { usePolling, useWidget } from "../hooks";
import { useState as useS } from "react";
import type { WidgetResponse } from "../../shared/types";

const dotClass = (s: LightState | undefined) => "dot " + (s ?? "unknown");

function countItems(entries: EntryView[], statuses: Map<string, StatusEntry>): { up: number; total: number } {
  let up = 0, total = 0;
  for (const e of entries) {
    if (e.kind === "group") { const c = countItems(e.entries, statuses); up += c.up; total += c.total; }
    else if (e.statusId) { total++; if (statuses.get(e.statusId)?.state === "up") up++; }
  }
  return { up, total };
}

function hostOf(href: string | null): string {
  if (!href) return "";
  try { const u = new URL(href, window.location.origin); return u.host; } catch { return ""; }
}

function Tile({ item, actions, statuses }: { item: ItemView; actions: Map<string, ActionView>; statuses: Map<string, StatusEntry> }) {
  const run = useRunAction();
  const w = useWidget(item.widget ? `/api/widgets/item/${encodeURIComponent(item.id)}` : null);
  const st = item.statusId ? statuses.get(item.statusId) : undefined;
  const action = item.action ? actions.get(item.action) : undefined;
  const lightState: LightState | undefined = item.statusId ? (st?.state ?? "unknown") : undefined;
  const title = st ? `${st.state}${st.detail ? " · " + st.detail : ""}${st.latencyMs != null ? ` · ${st.latencyMs} ms` : ""} · checked ${ago(st.checkedAt)}` : undefined;
  const inner = (
    <>
      <Icon icon={item.icon} name={item.name} />
      <span className="txt">
        <span className="nm" title={item.name}>{item.name}</span>
        <span className="hs">{action ? actionHint(action) : hostOf(item.href) || item.description || ""}</span>
        <WidgetLine w={w} />
      </span>
      {lightState && <><span className={dotClass(lightState)} title={title} /><span className="sr">{lightState}</span></>}
    </>
  );
  const off = (lightState === "down" ? " off" : "") + (item.widget ? " hasw" : "");
  if (item.action) {
    return <li className={"item" + off}><button className="tile" data-action={item.action} onClick={() => run(item.action!)}>{inner}</button></li>;
  }
  const href = safeHref(item.href);
  return <li className={"item" + off}>{href ? <a href={href} target="_blank" rel="noopener noreferrer" title={item.description ?? undefined}>{inner}</a> : <span className="tile">{inner}</span>}</li>;
}

function actionHint(a: ActionView): string {
  if (a.type === "wol") return `WOL ${a.mac}`;
  if (a.type === "proxmox-node") return `${a.command} · node ${a.node}`;
  return `${a.kind} ${a.vmid}`;
}

function Group({ g, actions, statuses, filter, nested }: { g: GroupView; actions: Map<string, ActionView>; statuses: Map<string, StatusEntry>; filter: string; nested?: boolean }) {
  const items = g.entries.filter((e): e is ItemView => e.kind === "item").filter((i) => !filter || `${i.name} ${i.href ?? ""} ${i.description ?? ""}`.toLowerCase().includes(filter));
  const subs = g.entries.filter((e): e is GroupView => e.kind === "group");
  const c = countItems(g.entries, statuses);
  const visibleSubs = subs.filter((s) => !filter || JSON.stringify(s).toLowerCase().includes(filter));
  if (filter && items.length === 0 && visibleSubs.length === 0) return null;
  const body = (
    <ul className="items">
      {items.map((i) => <Tile key={i.id} item={i} actions={actions} statuses={statuses} />)}
      {visibleSubs.map((s) => <li className="sub-grp" key={s.id}><span className="eyebrow">{s.name}</span><Group g={s} actions={actions} statuses={statuses} filter={filter} nested /></li>)}
    </ul>
  );
  if (nested) return body;
  return (
    <section className="grp" aria-label={g.name}>
      <header><h2 className="tape">{g.name}</h2>{c.total > 0 && <span className="count">{c.up}/{c.total} up</span>}</header>
      {body}
    </section>
  );
}

function Resources({ options }: { options: Record<string, string> }) {
  const [w, setW] = useS<WidgetResponse | null>(null);
  usePolling(() => { api<WidgetResponse>("/api/widgets/info/resources").then(setW).catch(() => setW({ state: "error", type: "resources", data: null, error: { code: "request", message: "cannot load" } })); }, 15_000);
  const d = w?.data ?? {};
  const cells: { k: string; v: string; small?: string; pct?: number }[] = [];
  if (d.cpu) cells.push({ k: "CPU", v: `${d.cpu.percent}`, small: "%", pct: d.cpu.percent });
  if (d.memory) cells.push({ k: "Memory", v: `${d.memory.percent}`, small: "%", pct: d.memory.percent });
  if (d.disk) cells.push({ k: `Disk ${d.disk.path}`, v: `${d.disk.percent}`, small: "%", pct: d.disk.percent });
  if (d.cputemp) cells.push({ k: "CPU temp", v: `${d.cputemp.value}`, small: "°" + d.cputemp.unit });
  if (d.uptime) cells.push({ k: "Uptime", v: fmtUptime(d.uptime.seconds) });
  if (d.network) cells.push({ k: "Network", v: `↓ ${fmtRate(d.network.rxBytesPerSec)}`, small: `↑ ${fmtRate(d.network.txBytesPerSec)}` });
  void options;
  if (w?.state === "error") return <div className="banner warn" role="status">Host metrics are unavailable ({w.error?.message}).</div>;
  return (
    <section className="widgets" aria-label="Host resources">
      {cells.map((c) => (
        <div className="w" key={c.k}>
          <div className="k">{c.k}</div>
          <div className="v">{c.v}<small>{c.small}</small></div>
          {c.pct != null && <div className="bar"><i style={{ width: `${Math.min(100, c.pct)}%` }} /></div>}
        </div>
      ))}
    </section>
  );
}

/** The portfolio is edited and served from the Docs LXC now. These are plain links: the hub never calls the Docs host. */
function PortfolioCard({ docsUrl, portfolioUrl }: { docsUrl: string | null; portfolioUrl: string | null }) {
  const editUrl = docsUrl ? `${docsUrl.replace(/\/+$/, "")}/#/portfolio` : null;
  return (
    <div className="portfolio-card">
      <div>
        <h2>Portfolio</h2>
        <p>{editUrl ? "Edited in Docs and served from the Docs LXC." : <>Moved to Docs. Add <span className="mono">docs: {"{url: …}"}</span> in Config to get the link here.</>}</p>
      </div>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {editUrl && <a className="btn solid" href={editUrl}>Edit in Docs</a>}
        {portfolioUrl && <a className="btn" href={portfolioUrl} target="_blank" rel="noopener noreferrer">Open public site</a>}
      </div>
    </div>
  );
}

export function Dashboard({ view, statuses, live, refresh, gotoConfig }: {
  view: DashboardView; statuses: Map<string, StatusEntry>; live: boolean; refresh: () => void;
  gotoConfig: () => void;
}) {
  const run = useRunAction();
  const [filter, setFilter] = useState("");
  const actions = useMemo(() => new Map(view.actions.map((a) => [a.id, a])), [view.actions]);
  const totals = countItems(view.groups.flatMap((g) => g.entries), statuses);
  const searchOpts = view.widgets.find((w) => w.type === "search")?.options;
  const resources = view.widgets.find((w) => w.type === "resources");
  const c = view.config;

  function webSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!searchOpts || !filter.trim()) return;
    const base = searchOpts.provider === "duckduckgo" ? "https://duckduckgo.com/?q=" : searchOpts.provider === "bing" ? "https://www.bing.com/search?q=" : "https://www.google.com/search?q=";
    window.open(base + encodeURIComponent(filter.trim()), searchOpts.target === "_self" ? "_self" : "_blank", "noopener,noreferrer");
  }

  return (
    <>
      <h1>{view.title}</h1>
      <p className="sub">
        {totals.total > 0 ? `${totals.up} of ${totals.total} monitored services up` : "No status checks configured yet"}
        {" · "}<span title={live ? "Updates arrive live" : "Live updates are reconnecting"}>{live ? "live" : "reconnecting…"}</span>
        {" · "}<button className="linkbtn" onClick={refresh}>Recheck now</button>
      </p>
      {c.health !== "ok" && (
        <div className="banner bad" role="alert">
          <b>dashboard.yaml has {c.errors} error{c.errors === 1 ? "" : "s"}.</b>{" "}
          {c.health === "using_default" ? "Showing the starter dashboard." : "Still showing the last working version."}{" "}
          <button className="linkbtn" onClick={gotoConfig}>Fix it in Config</button>
        </div>
      )}
      {c.missingSecrets.length > 0 && (
        <div className="banner warn" role="status">Widgets need {c.missingSecrets.length === 1 ? "a secret" : "secrets"}: <span className="mono">{c.missingSecrets.join(", ")}</span>. Add {c.missingSecrets.length === 1 ? "it" : "them"} to secrets.env.</div>
      )}
      {resources && <Resources options={resources.options} />}
      {view.pinned.length > 0 && (
        <div className="pinned"><span className="eyebrow">Quick actions</span>
          {view.pinned.map((id) => {
            const a = actions.get(id);
            if (!a) return null;
            const st = a.statusId ? statuses.get(a.statusId)?.state : undefined;
            return <button key={id} className="chip-act" onClick={() => run(id)}>{a.statusId && <span className={dotClass(st)} />}{a.label}</button>;
          })}
        </div>
      )}
      <PortfolioCard docsUrl={view.docsUrl ?? null} portfolioUrl={view.portfolioUrl ?? null} />
      <form className="search-row" onSubmit={webSearch} role="search">
        <input type="search" value={filter} onChange={(e) => setFilter(e.target.value.toLowerCase())} placeholder={searchOpts ? "Filter services, or press Enter to search the web" : "Filter services"} aria-label="Filter services" />
      </form>
      <div className="groups">
        {view.groups.map((g) => <Group key={g.id} g={g} actions={actions} statuses={statuses} filter={filter.trim()} />)}
      </div>
      {view.groups.length === 0 && <div className="none">No groups yet. Add some in Config.</div>}
    </>
  );
}
