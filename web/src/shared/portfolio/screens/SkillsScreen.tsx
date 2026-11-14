import { useEffect, useMemo, useState, type KeyboardEvent } from "react";
import { relatedSkills } from "../../filter";
import type { Portfolio } from "../../types";
import { usePortfolio } from "../context";
import { backgroundStars, branchOf, CENTRE, layoutConstellations, SKY_H, SKY_W, starRadius } from "./constellation";
import "./skills.css";

interface Linked { roles: string[]; projects: string[]; education: string[]; homelab: string[] }

/** Everything in the data that carries this skill, found by its `skills` array. */
export function linkedWork(data: Portfolio, label: string): Linked {
  const has = (s: string[]) => s.includes(label);
  const homelab: string[] = [];
  for (const d of data.homelab) {
    if (has(d.skills)) homelab.push(d.name);
    d.containers.filter((c) => has(c.skills)).forEach((c) => homelab.push(`${c.name} (${d.name})`));
    d.services.filter((c) => has(c.skills)).forEach((c) => homelab.push(`${c.name} (${d.name})`));
    d.network.filter((c) => has(c.skills)).forEach((c) => homelab.push(`${c.name} (${d.name})`));
  }
  return {
    roles: data.experience.filter((e) => has(e.skills)).map((e) => `${e.role} · ${e.company}`),
    projects: data.projects.filter((e) => has(e.skills)).map((e) => e.title),
    education: data.education.filter((e) => has(e.skills)).map((e) => e.title),
    homelab,
  };
}

const tierOf = (n: number) => (n >= 4 ? "gold" : n >= 2 ? "steel" : n === 1 ? "bronze" : "");
const BG = backgroundStars(260);
const plural = (n: number) => `${n} linked item${n === 1 ? "" : "s"}`;

export function SkillsScreen() {
  const { data, filter, pickSkill, go } = usePortfolio();
  const [sel, setSel] = useState<{ g: number; s: number } | null>(null);
  const [listMode, setListMode] = useState(false);
  const groups = data.skills;
  const layout = useMemo(() => layoutConstellations(groups.map((g) => g.skills.length)), [groups]);
  const all = groups.flatMap((g) => g.skills);
  const linkedCount = all.filter((s) => s.n > 0).length;
  const related = useMemo(() => relatedSkills(filter, data), [filter, data]);
  const picked = filter.picked;
  const legend = (["raven", "bear", "wolf"] as const).map((b) => {
    const names = groups.filter((_, i) => branchOf(i, groups.length) === b).map((g) => g.name);
    return { b, text: names.length ? names[0] + (names.length > 1 ? ` + ${names.length - 1} more` : "") : "" };
  }).filter((l) => l.text);

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => { if (e.key === "Escape") setSel(null); };
    addEventListener("keydown", onKey);
    return () => removeEventListener("keydown", onKey);
  }, []);

  const chosen = sel ? { group: groups[sel.g], skill: groups[sel.g]?.skills[sel.s] } : null;
  const work = chosen?.skill ? linkedWork(data, chosen.skill.l) : null;
  const act = (e: KeyboardEvent, fn: () => void) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); fn(); } };

  return (
    <div className={"skills" + (listMode ? " list" : "")}>
      <div className="sky-bar">
        <button className="btn sm" type="button" onClick={() => setListMode((v) => !v)} aria-pressed={listMode}>{listMode ? "Star view" : "List view"}</button>
      </div>
      <div className="sky-wrap">
        <svg className="sky" viewBox={`0 0 ${SKY_W} ${SKY_H}`} preserveAspectRatio="xMidYMid meet" role="group" aria-label="Skill constellations"
          onClick={(e) => { if (!(e.target as Element).closest(".star")) setSel(null); }}>
          {BG.map((b, i) => <circle key={i} className="bg-star" cx={b.x} cy={b.y} r={b.r} opacity={b.o} />)}
          {groups.map((g, gi) => {
            const L = layout[gi];
            const branch = branchOf(gi, groups.length);
            const colour = `var(--b-${branch})`;
            const pts = L.order.map((k) => L.stars[k]);
            const d = pts.length > 1 ? "M" + [...pts, pts[0]].map((p) => `${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join("L") : "";
            return (
              <g key={g.id} className={"grp" + (sel && sel.g !== gi ? " dim" : "")} style={{ color: colour }}>
                <line className="spoke" x1={CENTRE.x} y1={CENTRE.y} x2={L.cx} y2={L.cy} />
                {d && <path className="link" stroke={colour} d={d} />}
                {pts.length > 0 && <path className="link" stroke={colour} d={`M${L.cx} ${L.cy}L${pts[0].x.toFixed(1)} ${pts[0].y.toFixed(1)}`} />}
                <path className="emb" stroke={colour} d={`M${L.cx} ${L.cy - 11}l11 11-11 11-11-11z`} />
                <circle cx={L.cx} cy={L.cy} r={3} fill={colour} />
                <text className="gname" x={L.labelX} y={L.labelY}>{g.name}</text>
                {g.skills.map((s, si) => {
                  const p = L.stars[si];
                  const r = starRadius(s.n);
                  const isSel = sel?.g === gi && sel.s === si;
                  const isPicked = picked.has(s.l);
                  const choose = () => setSel(isSel ? null : { g: gi, s: si });
                  const above = L.upper || p.y < L.cy;
                  return (
                    <g key={s.id} className={"star" + (s.n ? "" : " zero") + (isSel ? " sel" : "") + (isPicked ? " picked" : "") + (related.has(s.l) && !isPicked && picked.size > 0 ? " rel" : "")}
                      role="button" tabIndex={0} aria-label={`${s.l}, ${plural(s.n)}`} aria-pressed={isPicked}
                      onClick={choose} onKeyDown={(e) => act(e, choose)}>
                      <circle className="ring" cx={p.x} cy={p.y} r={r + 6} />
                      <circle className="c" cx={p.x} cy={p.y} r={r} />
                      <circle className="hit" cx={p.x} cy={p.y} r={14} />
                      <text x={p.x} y={above ? p.y - r - 9 : p.y + r + 17} textAnchor="middle">{s.l}</text>
                    </g>
                  );
                })}
              </g>
            );
          })}
          <circle cx={CENTRE.x} cy={CENTRE.y} r={30} fill="var(--bg)" stroke="var(--ink)" strokeWidth={1.6} />
          <circle cx={CENTRE.x} cy={CENTRE.y} r={23} fill="none" stroke="var(--ink)" strokeWidth={0.7} />
          <path d={`M${CENTRE.x} ${CENTRE.y - 11}l11 11-11 11-11-11z`} fill="none" stroke="var(--ink)" strokeWidth={1.2} />
        </svg>
        <div className="legend" aria-hidden="true">
          {legend.map((l) => <span key={l.b}><i style={{ background: `var(--b-${l.b})` }} />{l.text}</span>)}
          <span><i className="hollow" />Hollow star: listed, no linked work yet</span>
        </div>
        <div className="detail" aria-live="polite">
          {chosen?.skill && work && (
            <article className={`card ${tierOf(chosen.skill.n)}`}>
              <div className="emblem"><span>{chosen.skill.n || "–"}</span></div>
              <div className="card-head"><h3>{chosen.skill.l}</h3><p>{chosen.group.name}</p></div>
              <div className="card-body">
                <div className="seg accent" aria-hidden="true">{Array.from({ length: 5 }, (_, i) => <i key={i} className={i < chosen.skill.n ? "f" : ""} />)}</div>
                {work.roles.length + work.projects.length + work.education.length + work.homelab.length === 0 && <div className="perk"><i /><span>Listed skill. No role or project is linked to it yet.</span></div>}
                {(["roles", "projects", "education", "homelab"] as const).map((k) => work[k].length > 0 && (
                  <div key={k} className="perk-group">
                    <div className="perk-title">{k === "homelab" ? "Homelab" : k[0].toUpperCase() + k.slice(1)}</div>
                    {work[k].map((w) => <div key={w} className="perk"><i /><span>{w}</span></div>)}
                  </div>
                ))}
              </div>
              <div className="card-foot">
                <button className="hint" type="button" onClick={() => { if (!picked.has(chosen.skill.l)) pickSkill(chosen.skill.l); go("summary"); }}><span className="key">F</span>Filter site by this skill</button>
                {work.roles.length > 0 && <button className="hint" type="button" onClick={() => { if (!picked.has(chosen.skill.l)) pickSkill(chosen.skill.l); go("quests"); }}><span className="key">Q</span>View in quests</button>}
              </div>
            </article>
          )}
        </div>
        <div className="badge l"><div className="d"><b>{all.length}</b></div><div><div className="eyebrow">Total</div><strong>Skills</strong></div></div>
        <div className="badge r"><div className="d"><b>{linkedCount}</b></div><div><div className="eyebrow">Backed by</div><strong>Linked work</strong></div></div>
      </div>

      <div className="listview">
        {groups.map((g, gi) => (
          <section key={g.id} className="sk-panel">
            <header><h3>{g.name}</h3><span className="count">{g.skills.filter((s) => s.n).length}/{g.skills.length}</span></header>
            <div className="chips">
              {g.skills.map((s) => (
                <button key={s.id} type="button" className={"chip" + (picked.has(s.l) ? " on" : "") + (s.n ? "" : " hollow")} aria-pressed={picked.has(s.l)}
                  title={plural(s.n)} onClick={() => pickSkill(s.l)}>
                  {s.l}<b className="num" style={{ color: `var(--b-${branchOf(gi, groups.length)})` }}>{s.n || "–"}</b>
                </button>
              ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
