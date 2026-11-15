import { useMemo, useState } from "react";
import { api } from "../../shared/api";
import type { ActionView, DashboardView, EntryView, GroupView, ItemView, LightState, MachineView, StatusEntry } from "../../shared/types";
import { ago, fmtRate, fmtUptime, safeHref } from "../../shared/util";
import { Icon } from "../components/Icon";
import { WidgetLine } from "../components/WidgetLine";
import { useRunAction } from "../components/ActionRunner";
import { usePolling, useWidget } from "../hooks";
import { useState as useS } from "react";
import type { WidgetResponse } from "../../shared/types";

const dotClass = (s: LightState | undefined) => "dot " + (s ?? "unknown");
const gemClass = (s: LightState | undefined) => "dot " + (s ?? "unknown");

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
    <section className="panel hp grp" aria-label={g.name}>
      <header><h3>{g.name}</h3>{c.total > 0 && <span className="count">{c.up}/{c.total} up</span>}</header>
      {body}
    </section>
  );
}


// ---- by machine ----
const flatItems = (entries: EntryView[]): ItemView[] => entries.flatMap((e) => (e.kind === "group" ? flatItems(e.entries) : [e]));
const hit = (i: ItemView, filter: string) => !filter || `${i.name} ${i.href ?? ""} ${i.description ?? ""}`.toLowerCase().includes(filter);
const STATE_TEXT: Record<LightState, string> = { up: "Online", down: "Offline", degraded: "Partly up", unknown: "No checks" };

/** One state for a set of status ids: all up is up, all down is down, a mix is degraded, nothing known is unknown. */
function combine(ids: (string | null | undefined)[], statuses: Map<string, StatusEntry>): LightState {
  const s = ids.flatMap((id) => { const v = id ? statuses.get(id)?.state : undefined; return v ? [v] : []; });
  if (s.length === 0) return "unknown";
  if (s.every((x) => x === "up")) return "up";
  return s.every((x) => x === "down") ? "down" : "degraded";
}

/** Boxes and panels show their own power state when an action has a check, else what their services say. */
function machineState(m: MachineView, svc: ItemView[], actions: Map<string, ActionView>, statuses: Map<string, StatusEntry>): LightState {
  const own = combine([...m.power, ...(m.toggle ? [m.toggle] : [])].map((id) => actions.get(id)?.statusId), statuses);
  return own !== "unknown" ? own : combine(svc.map((i) => i.statusId), statuses);
}

function PowerButton({ a, statuses, toggle }: { a: ActionView; statuses: Map<string, StatusEntry>; toggle?: boolean }) {
  const run = useRunAction();
  const st = a.statusId ? statuses.get(a.statusId)?.state : undefined;
  const label = toggle && st === "up" ? "Stop" : toggle && st === "down" ? "Start" : a.label;
  const danger = a.type === "proxmox-node" && (a.command === "shutdown" || a.command === "reboot");
  return <button className={"btn" + (toggle ? " sm" : "") + (danger ? " danger" : "")} data-action={a.id} title={a.label} onClick={() => run(a.id)}>{!toggle && a.statusId && <span className={dotClass(st)} />}{label}</button>;
}

function ItemList({ items, actions, statuses }: { items: ItemView[]; actions: Map<string, ActionView>; statuses: Map<string, StatusEntry> }) {
  return <ul className="items">{items.map((i) => <Tile key={i.id} item={i} actions={actions} statuses={statuses} />)}</ul>;
}

function Machines({ machines, groups, actions, statuses, filter }: { machines: MachineView[]; groups: GroupView[]; actions: Map<string, ActionView>; statuses: Map<string, StatusEntry>; filter: string }) {
  const all = flatItems(groups.flatMap((g) => g.entries)).filter((i) => hit(i, filter));
  const on = (id: string | null) => all.filter((i) => !i.action && (i.machine ?? null) === id);
  const kids = (id: string) => machines.filter((m) => m.parent === id);
  const tops = machines.filter((m) => m.parent === null);
  const known = new Set(machines.map((m) => m.id));
  const unplaced = all.filter((i) => !i.action && !(i.machine && known.has(i.machine)));
  const loose = all.filter((i) => i.action && !(i.machine && known.has(i.machine)));
  const act = (id: string) => actions.get(id);
  const up = (items: ItemView[]) => items.filter((i) => i.statusId && statuses.get(i.statusId)?.state === "up").length;
  const checked = (items: ItemView[]) => items.filter((i) => i.statusId).length;

  const box = (m: MachineView) => {
    const svc = on(m.id);
    if (filter && svc.length === 0) return null;
    const tg = m.toggle ? act(m.toggle) : undefined;
    const st = machineState(m, svc, actions, statuses);
    const tst = tg?.statusId ? statuses.get(tg.statusId)?.state : undefined;
    const dim = tst === "down" && !svc.some((i) => i.statusId && statuses.get(i.statusId)?.state === "up");
    return (
      <div className={"mp-box" + (dim ? " off" : "")} key={m.id}>
        <div className="mp-boxh"><h4 title={m.name}><span className={dotClass(st)} /><span className="sr">{STATE_TEXT[st]}</span><span className="nm">{m.name}</span></h4>{tg && <PowerButton a={tg} statuses={statuses} toggle />}</div>
        {svc.length > 0 ? <ItemList items={svc} actions={actions} statuses={statuses} /> : <span className="state">no services listed</span>}
      </div>
    );
  };

  const panel = (m: MachineView, wide: boolean) => {
    const svc = on(m.id), cs = kids(m.id).map((c) => ({ c, b: box(c) })).filter((x) => x.b);
    if (filter && svc.length === 0 && cs.length === 0) return null;
    const st = machineState(m, svc, actions, statuses);
    const pw = m.power.flatMap((id) => { const a = act(id); return a ? [a] : []; });
    const all2 = [...svc, ...cs.flatMap(({ c }) => on(c.id))];
    return (
      <section className={"panel hp mp" + (wide ? " wide" : "")} key={m.id} aria-label={m.name}>
        <header><h3 title={m.name}>{m.name}</h3><span className={"state " + (st === "degraded" ? "warn" : st)}><span className={dotClass(st)} /> {STATE_TEXT[st]}{checked(all2) > 0 ? ` · ${up(all2)}/${checked(all2)} up` : ""}</span></header>
        {pw.length > 0 && <div className="mp-power"><span className="eyebrow">Power</span>{pw.map((a) => <PowerButton key={a.id} a={a} statuses={statuses} />)}</div>}
        {svc.length > 0 && <ItemList items={svc} actions={actions} statuses={statuses} />}
        {wide && cs.length > 0 && <><div className="rule"><i />Containers · {cs.length}<i /></div><div className="mp-boxes">{cs.map((x) => x.b)}</div></>}
        {!wide && svc.length === 0 && <span className="state">no services listed</span>}
      </section>
    );
  };

  const wide = tops.filter((m) => kids(m.id).length > 0).map((m) => panel(m, true)).filter(Boolean);
  const side = tops.filter((m) => kids(m.id).length === 0).map((m) => panel(m, false)).filter(Boolean);
  return (
    <div className="mview">
      {(wide.length > 0 || side.length > 0) && <div className={"mv" + (wide.length === 0 ? " solo" : "") + (side.length === 0 ? " nside" : "")}>
        {side.length > 0 && <div className="mv-side">{side}</div>}
        {wide.length > 0 && <div className="mv-main">{wide}</div>}
      </div>}
      {(unplaced.length > 0 || loose.length > 0) && (
        <div className="mv-rest">
          {unplaced.length > 0 && <section className="panel hp mp" aria-label="Unplaced"><header><h3>Unplaced</h3><span className="count">{unplaced.length}</span></header>
            <p className="mp-hint">Add <span className="mono">on:</span> to a service, or an address to a machine, to place it.</p><ItemList items={unplaced} actions={actions} statuses={statuses} /></section>}
          {loose.length > 0 && <section className="panel hp mp" aria-label="Other actions"><header><h3>Other actions</h3><span className="count">{loose.length}</span></header><ItemList items={loose} actions={actions} statuses={statuses} /></section>}
        </div>
      )}
    </div>
  );
}

const VIEW_KEY = "hub-dash-view";
function readView(): "machine" | "group" { try { return localStorage.getItem(VIEW_KEY) === "group" ? "group" : "machine"; } catch { return "machine"; } }

/** Ten-segment bar. Warn from 75 percent, bad from 90 percent. */
function Seg({ pct }: { pct: number }) {
  const n = Math.round(Math.max(0, Math.min(100, pct)) / 10);
  const tone = pct >= 90 ? "bad" : pct >= 75 ? "warn" : "accent";
  return <div className={"seg " + tone} aria-hidden="true">{Array.from({ length: 10 }, (_, i) => <i key={i} className={i < n ? "f" : ""} />)}</div>;
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
        <div className="panel hp w" key={c.k}>
          <div className="eyebrow k">{c.k}</div>
          <div className="v"><span className="num">{c.v}</span><small>{c.small}</small></div>
          {c.pct != null && <Seg pct={c.pct} />}
        </div>
      ))}
    </section>
  );
}

/** The portfolio is edited and served from the Docs LXC now. These are plain links: the hub never calls the Docs host. */
function PortfolioCard({ docsUrl, portfolioUrl }: { docsUrl: string | null; portfolioUrl: string | null }) {
  const editUrl = docsUrl ? `${docsUrl.replace(/\/+$/, "")}/#/portfolio` : null;
  return (
    <section className="panel hp portfolio-card" aria-label="Portfolio">
      <div>
        <h3>Portfolio</h3>
        <p>{editUrl ? "Edited in Docs and served from the Docs LXC." : <>Moved to Docs. Add <span className="mono">docs: {"{url: …}"}</span> in Config to get the link here.</>}</p>
      </div>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {editUrl && <a className="btn pri" href={editUrl}>Edit in Docs</a>}
        {portfolioUrl && <a className="btn" href={portfolioUrl} target="_blank" rel="noopener noreferrer">Open public site</a>}
      </div>
    </section>
  );
}

export function Dashboard({ view, statuses, live, refresh, gotoConfig }: {
  view: DashboardView; statuses: Map<string, StatusEntry>; live: boolean; refresh: () => void;
  gotoConfig: () => void;
}) {
  const run = useRunAction();
  const [filter, setFilter] = useState("");
  const [pick, setPick] = useState<"machine" | "group">(readView);
  const machines = view.machines ?? [];
  const mode = machines.length > 0 ? pick : "group";
  const choose = (v: "machine" | "group") => { setPick(v); try { localStorage.setItem(VIEW_KEY, v); } catch { /* private mode */ } };
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
      <div className="screen-title">
        <div>
          <div className="eyebrow">{totals.total > 0 ? `${new Date().toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })} · ${totals.up} of ${totals.total} monitored services up` : "No status checks configured yet"}</div>
          <h1 className="display">Settlement</h1>
        </div>
        {totals.total > 0 && <div className="power" aria-hidden="true"><div className="eyebrow">Services up</div><div className="num">{totals.up}</div></div>}
      </div>
      <p className="sub">
        {" "}<span title={live ? "Updates arrive live" : "Live updates are reconnecting"}>{live ? "live" : "reconnecting…"}</span>
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
            return <button key={id} className="btn" onClick={() => run(id)}>{a.statusId && <span className={gemClass(st)} />}{a.label}</button>;
          })}
        </div>
      )}
      <PortfolioCard docsUrl={view.docsUrl ?? null} portfolioUrl={view.portfolioUrl ?? null} />
      <div className="dash-bar">
        <form className="search-row" onSubmit={webSearch} role="search">
          <input type="search" value={filter} onChange={(e) => setFilter(e.target.value.toLowerCase())} placeholder={searchOpts ? "Filter services, or press Enter to search the web" : "Filter services"} aria-label="Filter services" />
        </form>
        {machines.length > 0 && (
          <div className="viewsw" role="group" aria-label="Dashboard view">
            <button type="button" aria-pressed={mode === "machine"} onClick={() => choose("machine")}>By machine</button>
            <button type="button" aria-pressed={mode === "group"} onClick={() => choose("group")}>By group</button>
          </div>
        )}
      </div>
      {mode === "machine"
        ? <Machines machines={machines} groups={view.groups} actions={actions} statuses={statuses} filter={filter.trim()} />
        : <div className="groups">{view.groups.map((g) => <Group key={g.id} g={g} actions={actions} statuses={statuses} filter={filter.trim()} />)}</div>}
      {view.groups.length === 0 && <div className="none">No groups yet. Add some in Config.</div>}
    </>
  );
}
