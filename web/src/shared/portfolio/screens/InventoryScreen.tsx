import { useEffect, useMemo, useRef, useState } from "react";
import { isActive, matches } from "../../filter";
import { projectLinks } from "../../util";
import type { Project } from "../../types";
import { usePortfolio } from "../context";
import { ExtLinks, FilterBar, Rule, ScreenTitle, SkillChips } from "./parts";
import "./screens.css";

type Tier = "gold" | "steel" | "bronze" | "grey";

/** Tiers come from the data only: flagship projects first (gold, then steel), then the rest by order. */
export function tiers(projects: Project[]): Map<string, Tier> {
  const out = new Map<string, Tier>();
  const flag = projects.filter((p) => p.kind === "flagship");
  const rest = projects.filter((p) => p.kind !== "flagship");
  flag.forEach((p, i) => out.set(p.id, i === 0 ? "gold" : "steel"));
  rest.forEach((p, i) => out.set(p.id, i < 2 ? "bronze" : "grey"));
  return out;
}

/** Real facts only: nothing here is made up. */
function facts(p: Project): [string, string][] {
  const f: [string, string][] = [];
  if (p.type) f.push(["Type", p.type]);
  if (p.case?.role) f.push(["Role", p.case.role]);
  if (p.case?.client) f.push(["Client", p.case.client]);
  if (p.case?.duration) f.push(["Period", p.case.duration]);
  if (p.case?.metrics.length) f.push(["Measured results", String(p.case.metrics.length)]);
  if (p.deliverables?.length) f.push(["Deliverables", String(p.deliverables.length)]);
  if (f.length === 0 && p.skills.length) f.push(["Skills", String(p.skills.length)]);
  return f.slice(0, 4);
}

const subtitle = (p: Project) => [p.kind === "flagship" ? "Flagship" : null, p.type].filter(Boolean).join(" · ") || "Project";

function Detail({ p, onClose }: { p: Project; onClose: () => void }) {
  const ref = useRef<HTMLElement>(null);
  useEffect(() => { const el = ref.current; if (el) { el.scrollIntoView?.({ block: "nearest", behavior: "smooth" }); el.focus({ preventScroll: true }); } }, [p.id]);
  const c = p.case;
  return (
    <section ref={ref} tabIndex={-1} className="panel sc-detail" aria-label={`${p.title} details`}>
      <header>
        <div><div className="eyebrow">{subtitle(p)}{c?.duration ? ` · ${c.duration}` : ""}{c?.client ? ` · ${c.client}` : ""}</div><h2 className="sc-h">{p.title}</h2></div>
        <button type="button" className="btn sm" onClick={onClose}>Close</button>
      </header>
      <p className="sc-text">{p.description}</p>
      <ExtLinks links={projectLinks(p)} />
      {c?.context && (<><Rule>Context</Rule><p className="sc-text">{c.context}</p></>)}
      {c && c.metrics.length > 0 && (
        <>
          <Rule>Results</Rule>
          <div className="sc-table"><table><thead><tr><th>Metric</th><th>Before</th><th>After</th></tr></thead>
            <tbody>{c.metrics.map((m, i) => <tr key={i}><td>{m.label}</td><td>{m.before}</td><td className={m.emphasis === "accent" ? "sc-after" : undefined}>{m.after}</td></tr>)}</tbody></table></div>
        </>
      )}
      {c && c.platform.length > 0 && (<><Rule>Platform</Rule><div className="sc-chips">{c.platform.map((x) => <span key={x} className="chip">{x}</span>)}</div></>)}
      {p.deliverables && p.deliverables.length > 0 && (
        <>
          <Rule>Deliverables</Rule>
          <ul className="sc-dl">{p.deliverables.map((d) => <li key={d.key}><b>{d.title}</b> <span className="tag">{d.tag}</span><p>{d.body}</p></li>)}</ul>
        </>
      )}
      <Rule>Skills used</Rule>
      <SkillChips skills={p.skills} tags={p.tags} />
    </section>
  );
}

export function InventoryScreen() {
  const { data, filter } = usePortfolio();
  const [type, setType] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const active = isActive(filter);
  const tier = useMemo(() => tiers(data.projects), [data.projects]);
  const types = useMemo(() => [...new Set(data.projects.map((p) => p.type).filter((t): t is string => !!t))], [data.projects]);
  const order: Tier[] = ["gold", "steel", "bronze", "grey"];
  const list = data.projects.filter((p) => !type || p.type === type).sort((a, b) => order.indexOf(tier.get(a.id)!) - order.indexOf(tier.get(b.id)!));
  const open = data.projects.find((p) => p.id === openId);

  return (
    <div className="sc">
      <ScreenTitle eyebrow="Projects" title="Inventory" sub="Card colour marks the tier: gold for the lead flagship, steel for other flagships, bronze and stone for the rest. Every figure on a card comes from the project itself."
        side={types.length > 1 ? (
          <div className="sc-chips" role="group" aria-label="Project type">
            <button type="button" className={"chip" + (type === null ? " on" : "")} aria-pressed={type === null} onClick={() => setType(null)}>All</button>
            {types.map((t) => <button key={t} type="button" className={"chip" + (type === t ? " on" : "")} aria-pressed={type === t} onClick={() => setType(t)}>{t}</button>)}
          </div>
        ) : undefined} />
      <FilterBar />
      {list.length === 0 ? <p className="sub">No projects to show.</p> : (
        <div className="grid sc-cards">
          {list.map((p) => {
            const t = tier.get(p.id);
            const dim = active && !matches(filter, p);
            const links = projectLinks(p);
            const fs = facts(p);
            return (
              <article key={p.id} className={`card ${t === "grey" ? "" : t} ${dim ? "sc-dim" : ""}`.trim()} data-tier={t} data-dim={dim ? "true" : undefined} aria-label={p.title}>
                <div className="emblem"><span>{p.title.trim().charAt(0).toUpperCase()}</span></div>
                <div className="card-head"><h3>{p.title}</h3><p>{subtitle(p)}</p></div>
                <div className="card-body">
                  {fs.length > 0 && <dl className="stats one">{fs.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>}
                  {p.description && <div className="perk"><i /><span className="sc-clamp">{p.description}</span></div>}
                  {p.case?.metrics[0] && <div className="perk"><i /><span className="sc-clamp">{p.case.metrics[0].label}: {p.case.metrics[0].after}</span></div>}
                  <SkillChips skills={p.skills.slice(0, 6)} />
                </div>
                <div className="card-foot">
                  <button type="button" className="hint" aria-label={`Details of ${p.title}`} aria-expanded={openId === p.id} onClick={() => setOpenId(openId === p.id ? null : p.id)}><span className="key">A</span>Details</button>
                  {links.map((l) => <a key={l.label} className="hint" href={l.href} target="_blank" rel="noopener noreferrer"><span className="key">↗</span>{l.label}</a>)}
                </div>
              </article>
            );
          })}
        </div>
      )}
      {open && <Detail p={open} onClose={() => setOpenId(null)} />}
    </div>
  );
}
