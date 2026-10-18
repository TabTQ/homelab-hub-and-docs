import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { deviceKids, deviceMatches, isActive, matches, sectionCounts, textOk, homelabCount, toggle, tokens, type FilterState } from "../filter";
import { safeHref } from "../util";
import type { Device, Portfolio } from "../types";
import { Palette, usePaletteHotkey, type PaletteItem } from "./Palette";
import { SkillPanel, type Visibility } from "./SkillPanel";
import { useToast } from "./Toast";

export interface PortfolioEditHooks {
  onEdit: (resource: string, id?: number) => void;
  onAdd: (resource: string, parent?: { api: string; id: number }) => void;
}

interface Props {
  data: Portfolio; hooks?: PortfolioEditHooks; header: ReactNode; ribbon?: ReactNode;
  /** extra palette entries (the internal app adds services and actions) */
  extraPalette?: PaletteItem[]; paletteRef?: { open: () => void };
}

const SECTIONS: [string, string][] = [["about", "About"], ["experience", "Experience"], ["projects", "Projects"], ["education", "Education"], ["homelab", "Homelab"], ["contact", "Contact"]];

function store(key: string, value?: string): string | null {
  try {
    if (value !== undefined) { localStorage.setItem(key, value); return value; }
    return localStorage.getItem(key);
  } catch { return null; }
}

export function PortfolioPage({ data, hooks, header, ribbon, extraPalette = [] }: Props) {
  const toast = useToast();
  const [filter, setFilter] = useState<FilterState>({ picked: new Set(), mode: "all", q: "" });
  const [vis, setVis] = useState<Visibility>("hide");
  const [collapsed, setCollapsed] = useState(() => store("hub-panel") === "1");
  const [drawer, setDrawer] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [openKeys, setOpenKeys] = useState<Set<string>>(new Set());
  const [openGroups, setOpenGroups] = useState<Set<string>>(new Set());
  const [here, setHere] = useState("about");
  const subnav = useRef<HTMLElement>(null);
  const editable = !!hooks;

  const active = isActive(filter);
  const counts = useMemo(() => sectionCounts(filter, data), [filter, data]);

  const setCollapsedPersist = (c: boolean) => { setCollapsed(c); store("hub-panel", c ? "1" : "0"); };
  const togglePalette = useCallback(() => setPaletteOpen((o) => !o), []);
  usePaletteHotkey(togglePalette);

  useEffect(() => {
    const measure = () => {
      const h = subnav.current?.offsetHeight ?? 44;
      document.documentElement.style.setProperty("--stick", `${h + 10}px`);
    };
    measure();
    const ro = new ResizeObserver(measure);
    if (subnav.current) ro.observe(subnav.current);
    const spy = () => {
      const y = window.scrollY + (subnav.current?.offsetHeight ?? 44) + 50;
      let cur = "about";
      for (const [id] of SECTIONS) { const el = document.getElementById(id); if (el && el.offsetTop <= y) cur = id; }
      setHere(cur);
    };
    window.addEventListener("scroll", spy, { passive: true });
    spy();
    return () => { ro.disconnect(); window.removeEventListener("scroll", spy); };
  }, []);

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "Escape") setDrawer(false); };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);

  const pickSkill = (s: string) => setFilter((f) => ({ ...f, picked: toggle(f.picked, s) }));
  const clear = () => setFilter((f) => ({ ...f, picked: new Set(), q: "" }));
  const shown = <T extends { skills: string[]; t: string }>(e: T): boolean => (vis === "hide" ? matches(filter, e) : true);
  const dim = <T extends { skills: string[]; t: string }>(e: T): string => (vis === "dim" && !matches(filter, e) ? " dim" : "");
  const isOpen = (k: string, force = false) => force || openKeys.has(k);
  const onToggle = (k: string) => (e: React.SyntheticEvent<HTMLDetailsElement>) => {
    const open = (e.target as HTMLDetailsElement).open;
    setOpenKeys((s) => { const n = new Set(s); if (open) n.add(k); else n.delete(k); return n; });
  };

  const chip = (l: string) => <button key={l} className={"chip" + (filter.picked.has(l) ? " on" : "")} title={`Filter by ${l}`} onClick={() => pickSkill(l)}>{l}</button>;
  const chips = (e: { skills: string[]; tags?: string[] }) =>
    (e.skills.length > 0 || (e.tags?.length ?? 0) > 0) && (
      <div className="chips">
        {e.skills.map(chip)}
        {e.tags?.map((t) => <span key={t} className="chip tag" title="Free-text tag, not linked to a skill">{t}</span>)}
      </div>
    );
  const editBtn = (label: string, resource: string, id: number) => editable && (
    <button className="btn sm edit" aria-label={`Edit ${label}`} onClick={() => hooks!.onEdit(resource, id)}>Edit</button>
  );

  function section(id: string, title: string, count: string, body: ReactNode, opts: { empty?: boolean; add?: string } = {}) {
    if (opts.empty) {
      return (
        <section className="sec empty" id={id} key={id}>
          <div className="sec-h"><h2 className="tape">{title}</h2><span className="rule" /><span className="ct">no matches</span></div>
        </section>
      );
    }
    return (
      <section className="sec" id={id} key={id}>
        <div className="sec-h">
          <h2 className="tape">{title}</h2><span className="rule" /><span className="ct">{count}</span>
          {editable && opts.add && <button className="btn sm" onClick={() => hooks!.onAdd(opts.add!)}>Add</button>}
        </div>
        {body}
      </section>
    );
  }

  // ---- sections ----
  const p = data.profile;
  const aboutMatch = !active || matches(filter, p);
  const about = !aboutMatch && vis === "hide" ? section("about", "About", "", null, { empty: true }) : (
    <section className="sec" id="about" key="about">
      <div className="sec-h"><h2 className="tape">About</h2><span className="rule" />{editable && <button className="btn sm" onClick={() => hooks!.onEdit("profile", 1)}>Edit profile</button>}</div>
      <div className={"about" + (!aboutMatch ? " dim" : "")}>
        <div>
          <h1>{p.name}</h1>
          <div className="lede"><b>{p.title}</b>{p.tagline ? <> · {p.tagline}</> : null}</div>
          <div className="meta">{p.location && <span>{p.location}</span>}{p.status && <span>{p.status}</span>}{p.brand && <span>{p.brand}</span>}</div>
          {p.bio.map((b, i) => <p className="lede" key={i}>{b}</p>)}
        </div>
        <div className="stats">
          {p.stats.map((s, i) => <div className="stat" key={i}><div className="v">{s.value}</div><div className="l">{s.label}</div><div className="s">{s.sub}</div></div>)}
        </div>
      </div>
    </section>
  );

  const exps = data.experience.filter(shown);
  const experience = exps.length === 0 && active
    ? section("experience", "Experience", "", null, { empty: true })
    : section("experience", "Experience", `${data.experience.filter((e) => matches(filter, e)).length} of ${data.experience.length}`,
      <div className="cols">
        {exps.map((e) => {
          const hit = tokens(filter.q).length > 0 && e.highlights.some((h) => textOk(filter, h.toLowerCase()));
          return (
            <article key={e.id} className={"card" + dim(e)} id={e.id}>
              {editBtn(e.role, "experiences", e.dbId)}
              <h3>{e.role}</h3>
              <div className="role-meta"><span>{e.company}</span><span className="when">{e.duration}</span>{e.location && <span>{e.location}</span>}</div>
              {e.client && <div className="role-meta" style={{ marginTop: -6 }}><span>Client: {e.client}</span></div>}
              <p style={{ margin: 0 }}>{e.summary}</p>
              <details open={isOpen("h:" + e.id, hit)} onToggle={onToggle("h:" + e.id)}>
                <summary>{e.highlights.length} highlights</summary>
                <ul className="hl">{e.highlights.map((h, i) => <li key={i} className={tokens(filter.q).length && textOk(filter, h.toLowerCase()) ? "hit" : ""}>{h}</li>)}</ul>
              </details>
              {chips(e)}
            </article>
          );
        })}
      </div>, { add: "experiences" });

  const projs = data.projects.filter(shown);
  const flagship = projs.filter((x) => x.kind === "flagship"), small = projs.filter((x) => x.kind !== "flagship");
  const projectsBody = (
    <>
      {flagship.map((x) => {
        const dl = (x.deliverables ?? []).filter((d) => !tokens(filter.q).length || textOk(filter, `${d.title} ${d.tag} ${d.body}`.toLowerCase()));
        const c = x.case;
        return (
          <article key={x.id} className={"card" + dim(x)} id={x.id}>
            {editBtn(x.title, "projects", x.dbId)}
            {c && <div className="eyebrow">Flagship · {c.duration} · {c.client}</div>}
            <h3 style={{ marginTop: 2 }}>{x.title}</h3>
            <p style={{ margin: "8px 0 0" }}>{x.description}</p>
            {c && (
              <details open={isOpen("c:" + x.id)} onToggle={onToggle("c:" + x.id)}>
                <summary>Context and results</summary>
                <p>{c.context}</p>
                <div className="tablewrap"><table><thead><tr><th>Metric</th><th>Before</th><th>After</th></tr></thead>
                  <tbody>{c.metrics.map((m, i) => <tr key={i}><td>{m.label}</td><td>{m.before}</td><td className={"after" + (m.emphasis === "accent" ? " accent" : "")}>{m.after}</td></tr>)}</tbody></table></div>
              </details>
            )}
            {x.deliverables && x.deliverables.length > 0 && (
              <details open={isOpen("d:" + x.id, tokens(filter.q).length > 0 && dl.length > 0)} onToggle={onToggle("d:" + x.id)}>
                <summary>{dl.length} deliverable{dl.length === 1 ? "" : "s"}{dl.length < x.deliverables.length ? ` of ${x.deliverables.length}` : ""}</summary>
                <ul className="dl">{dl.map((d) => <li key={d.key}><div className="t">{d.title} <span className="chip tag">{d.tag}</span></div><p>{d.body}</p></li>)}</ul>
              </details>
            )}
            {chips(x)}
          </article>
        );
      })}
      {small.length > 0 && (
        <>
          <div className="sub-h">Other projects</div>
          <div className="grid3">
            {small.map((x) => (
              <article key={x.id} className={"card" + dim(x)} id={x.id}>
                {editBtn(x.title, "projects", x.dbId)}
                {x.type && <div className="eyebrow">{x.type}</div>}
                <h3 style={{ marginTop: 2, fontSize: "1rem" }}>{x.title}</h3>
                <p style={{ margin: "6px 0 0", fontSize: 14 }}>{x.description}</p>
                {chips(x)}
                {safeHref(x.repo) && <p style={{ margin: "10px 0 0" }}><a href={safeHref(x.repo)} className="mono" style={{ fontSize: 12.5 }} target="_blank" rel="noopener noreferrer">Source on GitHub ↗</a></p>}
              </article>
            ))}
          </div>
        </>
      )}
    </>
  );
  const projects = projs.length === 0 && active ? section("projects", "Projects", "", null, { empty: true })
    : section("projects", "Projects", `${data.projects.filter((e) => matches(filter, e)).length} of ${data.projects.length}`, projectsBody, { add: "projects" });

  const edus = data.education.filter(shown);
  const education = edus.length === 0 && active
    ? section("education", "Education", "", null, { empty: true })
    : section("education", "Education", `${data.education.filter((e) => matches(filter, e)).length} of ${data.education.length}`,
      <div className="edu">
        {["Degree", "Publication", "Certification"].map((t) => {
          const r = edus.filter((e) => e.type === t);
          return r.length === 0 ? null : (
            <div key={t}><div className="sub-h" style={{ marginTop: 0 }}>{t}s</div>
              <ul>{r.map((e) => (
                <li key={e.id} className={dim(e).trim()}>
                  <div className="t">{e.title}</div><div className="m">{e.inst} · {e.date}</div>{e.desc && <div className="m">{e.desc}</div>}
                  {editable && <button className="btn sm" style={{ marginTop: 4 }} onClick={() => hooks!.onEdit("education", e.dbId)}>Edit</button>}
                </li>))}</ul></div>
          );
        })}
      </div>, { add: "education" });

  const devs = data.homelab.filter((d) => (vis === "hide" ? deviceMatches(filter, d) : true));
  const hl = homelabCount(filter, data.homelab);
  const renderDevice = (d: Device) => {
    const k = deviceKids(filter, d), hide = vis === "hide";
    const cts = hide ? k.containers : d.containers, svs = hide ? k.services : d.services, nets = hide ? k.network : d.network;
    const net = (t: string) => nets.filter((n) => n.type === t);
    const dimD = !deviceMatches(filter, d) && vis === "dim" ? " dim" : "";
    return (
      <article key={d.id} className={"dev card" + dimD} id={d.id}>
        {editBtn(d.name, "homelab-devices", d.dbId)}
        <header><h3>{d.name}</h3>{d.ip && <span className="mono" style={{ color: "var(--muted)", fontSize: 13 }}>{d.ip}</span>}</header>
        <p style={{ margin: 0 }}>{d.summary}</p>
        {d.hw.length > 0 && <dl className="hw">{d.hw.map((h, i) => <div key={i}><dt>{h.component}</dt><dd>{h.spec}</dd></div>)}</dl>}
        {chips(d)}
        {cts.length > 0 && (<><div className="sub-h">Containers</div>
          <div className="grid3">{cts.map((c) => (
            <div key={c.id} className={"card" + dim(c)} style={{ padding: "12px 14px" }}>
              <div style={{ display: "flex", justifyContent: "space-between", gap: 8 }}><b>{c.name}</b><span className={"state " + (c.status === "Active" ? "up" : "warn")}>{c.status}</span></div>
              <div className="kv"><span>ID</span><span className="mono">{c.id.replace("lxc-", "")}</span></div>
              {c.purpose && <div className="kv"><span>Purpose</span><span>{c.purpose}</span></div>}
              {c.res && <div className="kv"><span>Resources</span><span className="mono">{c.res}</span></div>}
              {chips(c)}
              {editable && <button className="btn sm" style={{ marginTop: 6 }} onClick={() => hooks!.onEdit("homelab-containers", c.dbId)}>Edit</button>}
            </div>))}</div></>)}
        {svs.length > 0 && (<><div className="sub-h">Services</div>
          <div className="grid3">{svs.map((s) => (
            <div key={s.id} className={"card" + dim(s)} style={{ padding: "12px 14px" }}>
              <b>{s.name}</b>
              {s.fn && <div className="kv"><span>Function</span><span style={{ textAlign: "right" }}>{s.fn}</span></div>}
              {s.ct && <div className="kv"><span>Container</span><span className="mono">{s.ct}</span></div>}
              {s.port && <div className="kv"><span>Port</span><span className="mono">{s.port}</span></div>}
              {chips(s)}
              {editable && <button className="btn sm" style={{ marginTop: 6 }} onClick={() => hooks!.onEdit("homelab-services", s.dbId)}>Edit</button>}
            </div>))}</div></>)}
        {net("isp").length > 0 && (<><div className="sub-h">Uplinks</div><div className="grid2">{net("isp").map((n) => <div key={n.id} className="card" style={{ padding: "12px 14px" }}><b>{n.name}</b><div style={{ color: "var(--muted)", fontSize: 13.5 }}>{String(n.spec.spec ?? "")}</div></div>)}</div></>)}
        {net("vlan").length > 0 && (<><div className="sub-h">VLANs</div><div className="grid2">{net("vlan").map((n) => <div key={n.id} className="card" style={{ padding: "12px 14px" }}><b>{n.name}</b><div className="kv"><span>Subnet</span><span className="mono">{String(n.spec.subnet ?? "")}</span></div><div className="kv"><span>Role</span><span style={{ textAlign: "right" }}>{String(n.spec.role ?? "")}</span></div></div>)}</div></>)}
        {net("switch").length > 0 && (<><div className="sub-h">Switches</div><div className="grid3">{net("switch").map((n) => <div key={n.id} className="card" style={{ padding: "12px 14px" }}><b>{n.name}</b><div className="kv"><span>Type</span><span>{String(n.spec.type ?? "")}</span></div><div className="kv"><span>Role</span><span style={{ textAlign: "right" }}>{String(n.spec.role ?? "")}</span></div></div>)}</div></>)}
      </article>
    );
  };
  const homelab = devs.length === 0 && active ? section("homelab", "Homelab", "", null, { empty: true })
    : section("homelab", "Homelab", `${hl.hit} of ${hl.total}`, devs.map(renderDevice), { add: "homelab-devices" });

  const s = p.socials;
  const contactMatch = !active || matches(filter, p);
  const contact = !contactMatch && vis === "hide" ? section("contact", "Contact", "", null, { empty: true }) : section("contact", "Contact", "",
    <div className={"contact" + (!contactMatch ? " dim" : "")}>
      {p.email && <div><div className="eyebrow">Email</div><div className="v">{p.email}</div></div>}
      {p.phone && <div><div className="eyebrow">Phone</div><div className="v">{p.phone}</div></div>}
      {safeHref(s.github) && <div><div className="eyebrow">GitHub</div><a className="v" href={safeHref(s.github)} target="_blank" rel="noopener noreferrer">{s.githubLabel || s.github}</a></div>}
      {safeHref(s.linkedin) && <div><div className="eyebrow">LinkedIn</div><a className="v" href={safeHref(s.linkedin)} target="_blank" rel="noopener noreferrer">{s.linkedinLabel || s.linkedin}</a></div>}
    </div>);

  const cnt: Record<string, number> = counts;
  const none = Object.entries(counts).filter(([, n]) => n === 0).map(([k]) => k);

  // ---- palette ----
  const jump = (id: string) => () => { clear(); setTimeout(() => document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" }), 30); };
  const paletteItems: PaletteItem[] = useMemo(() => [
    ...SECTIONS.map(([id, t]) => ({ group: "Sections", title: t, hint: "Jump to", run: jump(id) })),
    ...data.experience.map((e) => ({ group: "Experience", title: e.role, hint: e.company, run: jump(e.id) })),
    ...data.projects.map((x) => ({ group: "Projects", title: x.title, hint: x.kind, run: jump(x.id) })),
    ...data.homelab.map((d) => ({ group: "Homelab", title: d.name, hint: d.ip ?? "", run: jump(d.id) })),
    ...data.skills.flatMap((g) => g.skills.map((sk) => ({ group: "Skills", title: sk.l, hint: sk.n ? `${sk.n} linked` : "no linked content", run: () => { pickSkill(sk.l); setCollapsedPersist(false); } }))),
    ...extraPalette,
    // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [data, extraPalette]);

  return (
    <>
      {header}
      <nav className="subnav" ref={subnav} aria-label="Sections">
        <div className="wrap">
          {SECTIONS.map(([id, label]) => (
            <a key={id} href={`#${id}`} className={here === id ? "here" : ""} onClick={(e) => { e.preventDefault(); document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" }); }}>
              {label}{active && cnt[id] != null && <span className="n">{cnt[id]}</span>}
            </a>
          ))}
        </div>
      </nav>
      <div className="wrap">
        <div className={"page" + (collapsed ? " collapsed" : "")}>
          <main id="content">
            {ribbon}
            {active && (
              <div className="banner" role="status">
                <div className="row">
                  <b>Showing what matches</b>
                  {[...filter.picked].map(chip)}
                  {tokens(filter.q).length > 0 && <span className="chip tag">text: {filter.q}</span>}
                  <span>· {Object.entries(counts).filter(([, n]) => n > 0).map(([k, n]) => `${k} ${n}`).join(" · ") || "nothing"}</span>
                  <button className="linkbtn" onClick={clear}>Clear all</button>
                </div>
                {none.length > 0 && <div style={{ color: "var(--muted)", marginTop: 4 }}>No matches in: {none.join(", ")}{vis === "hide" ? " (collapsed)" : ""}</div>}
              </div>
            )}
            {about}{experience}{projects}{education}{homelab}{contact}
          </main>
          <SkillPanel data={data} filter={filter} vis={vis} openGroups={openGroups} drawerOpen={drawer} collapsed={collapsed}
            onToggleSkill={pickSkill} onMode={(mode) => setFilter((f) => ({ ...f, mode }))} onVis={setVis} onQuery={(q) => setFilter((f) => ({ ...f, q }))}
            onGroupToggle={(name, open) => setOpenGroups((s) => { const n = new Set(s); if (open) n.add(name); else n.delete(name); return n; })}
            onCollapse={() => (matchMedia("(max-width: 900px)").matches ? setDrawer(false) : setCollapsedPersist(true))} onExpand={() => setCollapsedPersist(false)} />
        </div>
      </div>
      <button className="fab" aria-label="Open skill filter" onClick={() => setDrawer(true)}>Skills{filter.picked.size + (tokens(filter.q).length ? 1 : 0) > 0 ? ` · ${filter.picked.size + (tokens(filter.q).length ? 1 : 0)}` : ""}</button>
      <div className={"scrim" + (drawer ? " show" : "")} onClick={() => setDrawer(false)} />
      <Palette items={paletteItems} open={paletteOpen} onClose={() => setPaletteOpen(false)} />
      <PaletteTrigger onOpen={() => setPaletteOpen(true)} toast={toast} />
    </>
  );
}

/** Lets header components open the palette without prop drilling. */
function PaletteTrigger({ onOpen }: { onOpen: () => void; toast: (m: string) => void }) {
  useEffect(() => {
    const h = () => onOpen();
    window.addEventListener("hub:open-palette", h);
    return () => window.removeEventListener("hub:open-palette", h);
  }, [onOpen]);
  return null;
}

