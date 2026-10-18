import { lazy, Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiError } from "../../shared/api";
import { usePolling } from "../hooks";
import { useDockerAction } from "../docker/ActionDialog";
import { CleanupDialog } from "../docker/CleanupDialog";
import { groupByProject, matches, memAvailable, memUsed, NEW_STACK, pct, ports, tone, webPort, type StateFilter } from "../docker/format";
import { LogsDrawer } from "../docker/LogsDrawer";
import { RunForm } from "../docker/RunForm";
import { StackDelete } from "../docker/StackDelete";
import type { AuditRow, Container, DockerConfig, DockerHost, HostResult, Job, StackRow, Stats } from "../docker/types";

// The stack editor carries CodeMirror; it is only fetched when a stack is opened.
const StackEditor = lazy(() => import("../docker/StackEditor"));

type Tab = "containers" | "stacks" | "activity";
type Editing = { host: DockerHost; name: string; compose: string; env: string; isNew: boolean };

const DOCKER = "/api/admin/docker";
const hostPath = (h: DockerHost) => `hosts/${encodeURIComponent(h.id)}`;

/** Where a published port can be opened from this browser: the Pi itself is "127.0.0.1" in the config. */
function linkHost(h: DockerHost): string {
  return ["127.0.0.1", "localhost", "::1"].includes(h.host) ? location.hostname : h.host;
}

function loadSaved<T extends string>(key: string, fallback: T): T {
  try { return (localStorage.getItem(key) as T) || fallback; } catch { return fallback; }
}
function save(key: string, v: string) {
  try { localStorage.setItem(key, v); } catch { /* private mode */ }
}

export default function DockerPage({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [config, setConfig] = useState<DockerConfig | null>(null);
  const [configError, setConfigError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string>(() => loadSaved("hub.docker.host", "all"));
  const [tab, setTab] = useState<Tab>(() => loadSaved<Tab>("hub.docker.tab", "containers"));
  const [containers, setContainers] = useState<Record<string, HostResult<Container[]>>>({});
  const [stacks, setStacks] = useState<Record<string, HostResult<StackRow[]>>>({});
  const [stats, setStats] = useState<Record<string, Record<string, Stats>>>({});
  const [live, setLive] = useState(false);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<StateFilter>("all");
  const [logs, setLogs] = useState<{ host: DockerHost; c: Container } | null>(null);
  const [runOn, setRunOn] = useState<DockerHost | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [cleaning, setCleaning] = useState<DockerHost | null>(null);

  useEffect(() => {
    api<DockerConfig>(`${DOCKER}/config`).then(setConfig).catch((e: ApiError) => { if (e.status === 401) onUnauthorized(); setConfigError(e.message); });
  }, [onUnauthorized]);
  useEffect(() => save("hub.docker.host", selected), [selected]);
  useEffect(() => save("hub.docker.tab", tab), [tab]);

  const hosts = config?.hosts ?? [];
  const shown = useMemo(() => (selected === "all" ? hosts : hosts.filter((h) => h.id === selected)), [hosts, selected]);
  useEffect(() => { if (config && selected !== "all" && !hosts.some((h) => h.id === selected)) setSelected("all"); }, [config, hosts, selected]);

  const loadContainers = useCallback(() => {
    for (const h of hosts) {
      api<{ containers: Container[] }>(`${DOCKER}/${hostPath(h)}/containers`)
        .then((r) => setContainers((m) => ({ ...m, [h.id]: { status: "ok", data: r.containers } })))
        .catch((e: ApiError) => { if (e.status === 401) onUnauthorized(); setContainers((m) => ({ ...m, [h.id]: { status: "error", message: e.message, code: e.body?.error } })); });
    }
  }, [hosts, onUnauthorized]);

  const loadStacks = useCallback(() => {
    for (const h of shown) {
      api<{ stacks: StackRow[] }>(`${DOCKER}/${hostPath(h)}/stacks`)
        .then((r) => setStacks((m) => ({ ...m, [h.id]: { status: "ok", data: r.stacks } })))
        .catch((e: ApiError) => setStacks((m) => ({ ...m, [h.id]: { status: "error", message: e.message } })));
    }
  }, [shown]);

  // All hosts are listed (for the counts on the host chips); only while this page is open and the tab visible.
  usePolling(() => { if (config) loadContainers(); }, 15_000, [config, loadContainers]);
  usePolling(() => { if (config && tab === "stacks") loadStacks(); }, 30_000, [config, tab, loadStacks]);
  usePolling(() => {
    if (!live || !config) return;
    for (const h of shown) {
      api<{ stats: Stats[] }>(`${DOCKER}/${hostPath(h)}/stats`).then((r) => {
        const byId: Record<string, Stats> = {};
        r.stats.forEach((s) => { byId[s.id] = s; byId[s.name] = s; });
        setStats((m) => ({ ...m, [h.id]: byId }));
      }).catch(() => {});
    }
  }, 10_000, [live, config, shown]);

  const refresh = useCallback(() => { loadContainers(); if (tab === "stacks") loadStacks(); }, [loadContainers, loadStacks, tab]);
  const action = useDockerAction(refresh, onUnauthorized);

  const openStack = (host: DockerHost, name: string) => setEditing({ host, name, compose: "", env: "", isNew: false });
  /** Stop, down, delete, deploy or pull a whole stack, from whichever place offers it (Stacks tab, group head, editor). */
  const stackAction = (h: DockerHost, name: string, op: string, volumes?: boolean) =>
    action.start({ path: `${hostPath(h)}/stacks/${encodeURIComponent(name)}/${op}/prepare${volumes ? "?volumes=true" : ""}` });

  if (configError) return <div className="banner bad" role="alert">{configError}</div>;
  if (!config) return <p className="sub">Loading…</p>;
  if (!config.enabled) return <div className="banner warn">The Docker page is turned off on this hub (HUB_DOCKER_ENABLED).</div>;
  if (hosts.length === 0) return <div className="banner warn">No Docker hosts are declared yet. Add a <span className="mono">dockerHosts:</span> entry in the Config page (see README, Docker setup).</div>;

  return (
    <div className="dk">
      <div className="dk-head">
        <div>
          <div className="eyebrow">Containers on {hosts.length} host{hosts.length === 1 ? "" : "s"}</div>
          <h1>Docker</h1>
        </div>
        <span className="grow" />
        <div className="seg" role="tablist" aria-label="View">
          {(["containers", "stacks", "activity"] as Tab[]).map((t) => (
            <button key={t} role="tab" aria-selected={tab === t} aria-pressed={tab === t} onClick={() => setTab(t)}>{t[0].toUpperCase() + t.slice(1)}</button>
          ))}
        </div>
      </div>

      <div className="dk-hosts" role="group" aria-label="Hosts">
        <button className="dk-host" aria-pressed={selected === "all"} onClick={() => setSelected("all")}>All hosts</button>
        {hosts.map((h) => {
          const r = containers[h.id];
          const running = r?.status === "ok" ? r.data.filter((c) => c.state === "running").length : 0;
          const bad = r?.status === "ok" ? r.data.filter((c) => tone(c) === "bad").length : 0;
          return (
            <button key={h.id} className="dk-host" aria-pressed={selected === h.id} onClick={() => setSelected(h.id)} title={`${h.user}@${h.host}`}>
              <span className={"dk-dot " + (r?.status === "error" ? "bad" : r?.status === "ok" ? (bad ? "warn" : "ok") : "off")} aria-hidden="true" />
              {h.label}
              <span className="dk-count">{r?.status === "ok" ? `${running}/${r.data.length}` : r?.status === "error" ? "error" : "…"}</span>
            </button>
          );
        })}
      </div>

      {tab === "containers" && (
        <>
          <div className="dk-toolbar">
            <input className="dk-search" type="search" placeholder="Filter by name, image, project or port" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Filter containers" />
            <div className="seg" role="group" aria-label="State">
              {(["all", "running", "stopped", "unhealthy"] as StateFilter[]).map((f) => <button key={f} aria-pressed={filter === f} onClick={() => setFilter(f)}>{f}</button>)}
            </div>
            <label className="dk-check"><input type="checkbox" checked={live} onChange={(e) => setLive(e.target.checked)} /> Live CPU / memory</label>
            <button className="btn sm" onClick={refresh}>Refresh</button>
          </div>
          {shown.map((h) => (
            <HostSection key={h.id} host={h} result={containers[h.id]} stats={live ? stats[h.id] : undefined} query={query} filter={filter}
              onLogs={(c) => setLogs({ host: h, c })} onRun={() => setRunOn(h)} onNewStack={() => setEditing({ host: h, name: "", compose: NEW_STACK, env: "", isNew: true })}
              onAction={(c, op) => action.start({ path: `${hostPath(h)}/containers/${c.id}/${op}/prepare` })}
              onOpenStack={(name) => openStack(h, name)} onStackAction={(name, op) => stackAction(h, name, op)}
              onCleanup={() => setCleaning(h)} />
          ))}
        </>
      )}

      {tab === "stacks" && shown.map((h) => (
        <StacksSection key={h.id} host={h} result={stacks[h.id]} onEdit={(n) => openStack(h, n)}
          onNew={() => setEditing({ host: h, name: "", compose: NEW_STACK, env: "", isNew: true })}
          onAction={(name, op, volumes) => stackAction(h, name, op, volumes)}
          onShowContainers={(name) => { setSelected(h.id); setQuery(name); setFilter("all"); setTab("containers"); }} />
      ))}

      {tab === "activity" && <Activity hosts={hosts} />}

      {logs && <LogsDrawer host={logs.host} container={logs.c} onClose={() => setLogs(null)} onUnauthorized={onUnauthorized} />}
      {runOn && <RunForm host={runOn} onClose={() => setRunOn(null)}
        onRun={(spec) => { const h = runOn; setRunOn(null); action.start({ path: `${hostPath(h)}/run/prepare`, body: spec }); }}
        onSaveAsStack={(name, compose, env) => { const h = runOn; setRunOn(null); setEditing({ host: h, name, compose, env, isNew: true }); }} />}
      {editing && (
        <Suspense fallback={null}>
          <StackEditor host={editing.host} initial={editing} onClose={() => setEditing(null)} onUnauthorized={onUnauthorized}
            onStackAction={(op, volumes) => { const h = editing.host, name = editing.name; setEditing(null); stackAction(h, name, op, volumes); }}
            onSaved={(name, deployNow) => {
              const h = editing.host;
              setEditing(null);
              refresh();
              if (tab === "stacks") loadStacks();
              if (deployNow) action.start({ path: `${hostPath(h)}/stacks/${encodeURIComponent(name)}/deploy/prepare` });
            }} />
        </Suspense>
      )}
      {cleaning && <CleanupDialog host={cleaning} onClose={() => setCleaning(null)} onUnauthorized={onUnauthorized}
        onRemove={(body) => { const h = cleaning; setCleaning(null); action.start({ path: `${hostPath(h)}/cleanup/prepare`, body }); }} />}
      {action.dialog}
    </div>
  );
}

function HostSection({ host, result, stats, query, filter, onLogs, onRun, onNewStack, onAction, onOpenStack, onStackAction, onCleanup }: {
  host: DockerHost; result: HostResult<Container[]> | undefined; stats: Record<string, Stats> | undefined; query: string; filter: StateFilter;
  onLogs: (c: Container) => void; onRun: () => void; onNewStack: () => void; onAction: (c: Container, op: string) => void; onOpenStack: (name: string) => void;
  onStackAction: (name: string, op: string) => void; onCleanup: () => void;
}) {
  const list = result?.status === "ok" ? result.data.filter((c) => matches(c, query, filter)) : [];
  const groups = groupByProject(list);
  return (
    <section className="dk-section" aria-label={host.label}>
      <header className="dk-section-head">
        <h2>{host.label}</h2>
        <span className="mono dk-muted">{host.user}@{host.host}</span>
        <span className="grow" />
        <button className="btn sm" onClick={onNewStack}>+ New stack</button>
        <button className="btn sm" onClick={onRun}>+ Run container</button>
        <button className="btn sm" onClick={onCleanup} title="Remove images and networks nothing uses">Clean up…</button>
      </header>
      {!result || result.status === "loading" ? <p className="dk-muted">Loading…</p>
        : result.status === "error" ? <div className="banner bad" role="alert"><b>{host.label}:</b> {result.message}</div>
        : result.data.length === 0 ? <p className="dk-muted">No containers on this host yet.</p>
        : list.length === 0 ? <p className="dk-muted">Nothing matches the filter.</p>
        : groups.map((g) => (
          <div key={g.project ?? "-"} className="dk-group">
            <div className="dk-group-head">
              <span className="eyebrow">{g.project ?? "Standalone"}</span>
              {g.stack && <>
                <button className="btn sm" onClick={() => onOpenStack(g.project!)}>Edit stack</button>
                {g.containers.some((c) => c.state === "running") && <button className="btn sm" onClick={() => onStackAction(g.project!, "stop")}>Stop stack</button>}
                <button className="btn sm" onClick={() => onStackAction(g.project!, "down")} title="Stop and remove the stack's containers; its files stay">Down</button>
              </>}
              {g.project && !g.stack && <span className="chip tag" title="Started outside the hub's stacks folder">external compose</span>}
              {stats && <><div className="dk-stats-head" aria-hidden="true"><span>CPU</span><span>Mem</span></div><span className="dk-stats-pad" /></>}
            </div>
            <ul className="dk-list">
              {g.containers.map((c) => <Row key={c.id} host={host} c={c} stats={stats?.[c.id.slice(0, 12)] ?? stats?.[c.id] ?? stats?.[c.name]} showStats={!!stats}
                onLogs={() => onLogs(c)} onAction={(op) => onAction(c, op)} />)}
            </ul>
          </div>
        ))}
    </section>
  );
}

function Row({ host, c, stats, showStats, onLogs, onAction }: {
  host: DockerHost; c: Container; stats: Stats | undefined; showStats: boolean; onLogs: () => void; onAction: (op: string) => void;
}) {
  const running = c.state === "running" || c.state === "restarting";
  const web = webPort(c.ports);
  return (
    <li className={"dk-row-c" + (running ? "" : " dk-stopped")}>
      <span className={"dk-dot " + tone(c)} title={c.health ? `${c.state}, ${c.health}` : c.state} aria-label={c.health ? `${c.state}, ${c.health}` : c.state} />
      <div className="dk-main">
        <div className="dk-name">
          <span className="mono">{c.name}</span>
          {c.protected && <span className="chip tag" title={c.protectedReason ?? ""}>🔒 protected</span>}
          {c.health === "unhealthy" && <span className="chip tag dk-bad">unhealthy</span>}
        </div>
        <div className="dk-meta">
          <span className="mono" title={c.image}>{c.image}</span>
          <span>{c.status}</span>
          {ports(c.ports).map((p) => <span key={p} className="chip tag">{p}</span>)}
          {web && running && <a href={`http://${linkHost(host)}:${web}`} target="_blank" rel="noopener noreferrer">open ↗</a>}
        </div>
      </div>
      {showStats && (
        <div className="dk-stats" aria-label="CPU and memory">
          <span className="dk-cpu" title="CPU">{running ? pct(stats?.cpuPercent) : "–"}</span>
          {running && !memAvailable(stats?.memUsage)
            ? <span className="dk-mem dk-na" title="Memory n/a: this host's kernel has no memory cgroup, so Docker reports 0 for every container (enable it with cgroup_enable=memory in /boot/firmware/cmdline.txt on a Raspberry Pi).">n/a</span>
            : <span className="dk-mem" title={stats?.memUsage ?? ""}>{running ? memUsed(stats?.memUsage) : "–"}</span>}
        </div>
      )}
      <div className="dk-actions">
        <button className="btn sm" onClick={onLogs}>Logs</button>
        {running
          ? <>
            <button className="btn sm" disabled={c.protected} onClick={() => onAction("restart")} title={c.protectedReason ?? "Restart"}>Restart</button>
            <button className="btn sm" disabled={c.protected} onClick={() => onAction("stop")} title={c.protectedReason ?? "Stop"}>Stop</button>
          </>
          : <button className="btn sm" onClick={() => onAction("start")}>Start</button>}
        {c.canUpdate && <button className="btn sm" onClick={() => onAction("update")} title="Pull the newest image and recreate">Update</button>}
        {c.canRemove && <button className="btn sm" onClick={() => onAction("remove")}>Remove</button>}
      </div>
    </li>
  );
}

function StacksSection({ host, result, onEdit, onNew, onAction, onShowContainers }: {
  host: DockerHost; result: HostResult<StackRow[]> | undefined; onEdit: (name: string) => void; onNew: () => void;
  onAction: (name: string, op: string, volumes?: boolean) => void; onShowContainers: (name: string) => void;
}) {
  return (
    <section className="dk-section" aria-label={`Stacks on ${host.label}`}>
      <header className="dk-section-head">
        <h2>{host.label}</h2>
        <span className="mono dk-muted">~{host.user}/stacks</span>
        <span className="grow" />
        <button className="btn sm pri" onClick={onNew}>+ New stack</button>
      </header>
      {!result || result.status === "loading" ? <p className="dk-muted">Loading…</p>
        : result.status === "error" ? <div className="banner bad" role="alert"><b>{host.label}:</b> {result.message}</div>
        : result.data.length === 0 ? <p className="dk-muted">No stacks yet. A stack is a compose.yaml (and .env) the hub keeps on the host, with history and one-click deploy.</p>
        : (
          <ul className="dk-list">
            {result.data.map((s) => (
              <li key={s.name} className="dk-row-c">
                <span className={"dk-dot " + (s.containers === 0 ? "off" : s.running === s.containers ? "ok" : s.running === 0 ? "off" : "warn")} aria-hidden="true" />
                <div className="dk-main">
                  <div className="dk-name"><span className="mono">{s.name}</span>{!s.managed && <span className="chip tag" title={s.workdir ?? ""}>external</span>}</div>
                  <div className="dk-meta"><span>{s.containers === 0 ? "not deployed" : `${s.running} of ${s.containers} running`}</span>{!s.managed && s.workdir && <span className="mono">{s.workdir}</span>}</div>
                </div>
                <div className="dk-actions">
                  {s.containers > 0 && <button className="btn sm" onClick={() => onShowContainers(s.name)}>Containers</button>}
                  {s.managed && <>
                    <button className="btn sm" onClick={() => onEdit(s.name)}>Edit</button>
                    <button className="btn sm" onClick={() => onAction(s.name, "deploy")}>Deploy</button>
                    <button className="btn sm" onClick={() => onAction(s.name, "pull")} title="Pull newer images and redeploy">Pull</button>
                    {s.running > 0 && <button className="btn sm" onClick={() => onAction(s.name, "stop")}>Stop</button>}
                    {s.containers > 0 && <button className="btn sm" onClick={() => onAction(s.name, "down")}>Down</button>}
                    <StackDelete onDelete={(v) => onAction(s.name, "delete", v)} />
                  </>}
                </div>
              </li>
            ))}
          </ul>
        )}
    </section>
  );
}

function Activity({ hosts }: { hosts: DockerHost[] }) {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [audit, setAudit] = useState<AuditRow[]>([]);
  const [open, setOpen] = useState<Job | null>(null);
  usePolling(() => {
    api<Job[]>(`${DOCKER}/jobs`).then(setJobs).catch(() => {});
    api<AuditRow[]>(`${DOCKER}/audit?limit=50`).then(setAudit).catch(() => {});
  }, 10_000);
  const label = (id: string) => hosts.find((h) => h.id === id)?.label ?? id;
  return (
    <>
      <section className="dk-section">
        <header className="dk-section-head"><h2>Recent jobs</h2><span className="dk-muted">kept in memory until the hub restarts</span></header>
        {jobs.length === 0 ? <p className="dk-muted">No jobs since the hub started.</p> : (
          <ul className="dk-list">
            {jobs.map((j) => (
              <li key={j.id} className="dk-row-c">
                <span className={"dk-dot " + (j.state === "ok" ? "ok" : j.state === "failed" ? "bad" : "warn")} aria-label={j.state} />
                <div className="dk-main"><div className="dk-name">{j.title}</div><div className="dk-meta"><span>{label(j.hostId)}</span><span className="mono">{j.started.replace("T", " ").slice(0, 19)}</span><span>{j.message}</span></div></div>
                <div className="dk-actions"><button className="btn sm" onClick={() => api<Job>(`${DOCKER}/jobs/${j.id}`).then(setOpen).catch(() => {})}>Output</button></div>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section className="dk-section">
        <header className="dk-section-head"><h2>Audit log</h2><span className="dk-muted">last 50 changes</span></header>
        {audit.length === 0 ? <p className="dk-muted">Nothing recorded yet.</p> : (
          <div className="dk-table-wrap">
            <table className="dk-table">
              <thead><tr><th>Time (UTC)</th><th>Host</th><th>Target</th><th>Action</th><th>Result</th><th>Detail</th></tr></thead>
              <tbody>{audit.map((a) => (
                <tr key={a.id}><td className="mono">{a.ts.replace("T", " ").replace("Z", "")}</td><td>{label(a.hostId)}</td><td className="mono">{a.target}</td><td>{a.action}</td>
                  <td className={a.result === "ok" ? "result ok" : a.result === "failed" || a.result === "denied" ? "result bad" : ""}>{a.result}</td><td>{a.detail}</td></tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </section>
      {open && (
        <div className="overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) setOpen(null); }}>
          <div className="dialog" style={{ width: "min(900px, 100%)" }} role="dialog" aria-modal="true" aria-label={open.title}>
            <div className="modal"><h2>{open.title}</h2><p className={"result " + (open.state === "ok" ? "ok" : "bad")}>{open.message}</p>
              <pre className="dk-out">{open.output}</pre><div className="mrow"><button className="btn pri" onClick={() => setOpen(null)}>Close</button></div></div>
          </div>
        </div>
      )}
    </>
  );
}
