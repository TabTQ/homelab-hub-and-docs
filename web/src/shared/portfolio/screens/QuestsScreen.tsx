import { useMemo, useState, type KeyboardEvent } from "react";
import { matches, isActive } from "../../filter";
import { usePortfolio } from "../context";
import { FilterBar, Rule, ScreenTitle, SkillChips } from "./parts";
import "./screens.css";

const isActiveRole = (duration: string) => /present\s*$/i.test(duration.trim());

export function QuestsScreen() {
  const { data, filter } = usePortfolio();
  const [sel, setSel] = useState<string | null>(null);
  const active = isActive(filter);
  const roles = useMemo(() => {
    const hit = (e: (typeof data.experience)[number]) => !active || matches(filter, e);
    return [...data.experience.filter(hit), ...data.experience.filter((e) => !hit(e))];
  }, [data.experience, filter, active]);
  const cur = roles.find((r) => r.id === sel) ?? roles[0];

  const onKey = (e: KeyboardEvent) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    e.preventDefault();
    const i = roles.findIndex((r) => r.id === cur?.id);
    const n = Math.max(0, Math.min(roles.length - 1, i + (e.key === "ArrowDown" ? 1 : -1)));
    setSel(roles[n].id);
    requestAnimationFrame(() => document.getElementById("role-" + roles[n].id)?.focus());
  };

  return (
    <div className="sc">
      <ScreenTitle eyebrow="Experience" title="Quest log" sub="Past and current roles. Pick one to read what I did there." />
      <FilterBar />
      {roles.length === 0 ? <p className="sub">No roles listed yet.</p> : (
        <div className="split">
          <aside>
            <Rule>Roles</Rule>
            <ul className="rows" onKeyDown={onKey} aria-label="Roles">
              {roles.map((r) => {
                const dim = active && !matches(filter, r);
                const act = isActiveRole(r.duration);
                return (
                  <li key={r.id} className={dim ? "sc-dim" : undefined}>
                    <button id={"role-" + r.id} type="button" aria-current={r.id === cur?.id ? "true" : undefined} tabIndex={r.id === cur?.id ? 0 : -1} onClick={() => setSel(r.id)}>
                      <span className={"gem " + (act ? "up" : "unknown")} aria-hidden="true" />
                      <span className="t"><b>{r.role}</b><span>{r.company} · {r.duration}</span></span>
                      <span className="tag">{act ? "Active" : "Done"}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </aside>
          {cur && (
            <article className="panel sc-detail" aria-live="polite">
              <header>
                <div>
                  <div className="eyebrow">{isActiveRole(cur.duration) ? "Current role" : "Past role"}</div>
                  <h2 className="sc-h">{cur.role}</h2>
                  <div className="sub sc-meta">{cur.company} · {cur.duration}{cur.location ? ` · ${cur.location}` : ""}</div>
                  {cur.client && <div className="sub sc-meta">Client: {cur.client}</div>}
                </div>
              </header>
              <p className="sc-text">{cur.summary}</p>
              {cur.highlights.length > 0 && (
                <>
                  <Rule>Objectives completed</Rule>
                  <div className="grid sc-perks">
                    {cur.highlights.map((h, i) => <div key={i} className="perk" style={{ "--tier": "var(--accent)" } as React.CSSProperties}><i /><span>{h}</span></div>)}
                  </div>
                </>
              )}
              {(cur.skills.length > 0 || cur.tags.length > 0) && (<><Rule>Skills used</Rule><SkillChips skills={cur.skills} tags={cur.tags} /></>)}
            </article>
          )}
        </div>
      )}
    </div>
  );
}
