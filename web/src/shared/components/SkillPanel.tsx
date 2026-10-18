import { useMemo } from "react";
import { relatedSkills, tokens, type FilterState } from "../filter";
import type { Portfolio } from "../types";

export type Visibility = "hide" | "dim";

interface Props {
  data: Portfolio; filter: FilterState; vis: Visibility; openGroups: Set<string>;
  onToggleSkill: (s: string) => void; onMode: (m: "all" | "any") => void; onVis: (v: Visibility) => void; onQuery: (q: string) => void;
  onGroupToggle: (name: string, open: boolean) => void; onCollapse: () => void; collapsed: boolean; onExpand: () => void; drawerOpen: boolean;
}

export function SkillPanel(p: Props) {
  const { data, filter } = p;
  const qt = tokens(filter.q);
  const rel = useMemo(() => relatedSkills(filter, data), [filter, data]);
  const total = data.skills.reduce((a, g) => a + g.skills.length, 0);
  const covered = data.skills.reduce((a, g) => a + g.skills.filter((s) => s.n > 0).length, 0);
  const shownGroups = data.skills.map((g) => ({ g, list: g.skills.filter((s) => qt.every((t) => s.l.toLowerCase().includes(t))) })).filter((x) => x.list.length > 0);
  const badge = filter.picked.size + (qt.length ? 1 : 0);

  return (
    <aside className={"panel" + (p.drawerOpen ? " open" : "")} aria-label="Skill filter" onClick={p.collapsed ? p.onExpand : undefined}>
      <button className="rail" aria-label="Open skill filter" onClick={p.onExpand}>
        <span className="bd">{badge}</span><span className="vt">Skills</span><span aria-hidden="true">«</span>
      </button>
      <div className="pbody">
        <div className="phead">
          <h2 className="tape">Skill filter</h2>
          <button className="iconbtn" aria-label="Collapse skill filter" title="Collapse" onClick={p.onCollapse}>»</button>
        </div>
        <input className="psearch" type="search" value={filter.q} placeholder="Search skills or any text" aria-label="Search skills or page text" autoComplete="off"
          onChange={(e) => p.onQuery(e.target.value)} />
        <div className="ptools">
          <div className="seg" role="group" aria-label="Skill match mode">
            <button aria-pressed={filter.mode === "all"} onClick={() => p.onMode("all")}>Match all</button>
            <button aria-pressed={filter.mode === "any"} onClick={() => p.onMode("any")}>Match any</button>
          </div>
          <div className="seg" role="group" aria-label="Non-matching items">
            <button aria-pressed={p.vis === "hide"} onClick={() => p.onVis("hide")}>Hide others</button>
            <button aria-pressed={p.vis === "dim"} onClick={() => p.onVis("dim")}>Dim others</button>
          </div>
        </div>
        <div className="active" aria-live="polite">
          {[...filter.picked].map((s) => <button key={s} className="chip on" aria-label={`Remove ${s}`} onClick={() => p.onToggleSkill(s)}>{s} ×</button>)}
        </div>
        <div>
          {shownGroups.map(({ g, list }) => {
            const open = list.some((s) => filter.picked.has(s.l)) || p.openGroups.has(g.name) || qt.length > 0;
            return (
              <details key={g.id} className="pgroup" open={open} onToggle={(e) => p.onGroupToggle(g.name, (e.target as HTMLDetailsElement).open)}>
                <summary>{g.name}<span className="c">{g.skills.filter((s) => s.n > 0).length}/{g.skills.length}</span></summary>
                <div className="chips">
                  {list.map((s) => (
                    <button key={s.id} className={"sk" + (s.n ? "" : " zero") + (filter.picked.size && rel.has(s.l) && !filter.picked.has(s.l) ? " rel" : "")}
                      aria-pressed={filter.picked.has(s.l)} title={s.n ? `${s.n} linked item${s.n === 1 ? "" : "s"}` : "No linked content yet"} onClick={() => p.onToggleSkill(s.l)}>
                      {s.l}<span className="n">{s.n || "—"}</span>
                    </button>
                  ))}
                </div>
              </details>
            );
          })}
          {shownGroups.length === 0 && <div className="none">No skill name matches “{filter.q}”. The page text filter still applies.</div>}
        </div>
        <div className="pfoot">{covered} of {total} skills have linked content. Dashed skills are listed but not linked to any role, project or homelab item yet.</div>
      </div>
    </aside>
  );
}
