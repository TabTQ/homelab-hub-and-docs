import type { ReactNode } from "react";
import { isActive, tokens } from "../../filter";
import { usePortfolio } from "../context";
import type { PortfolioLink } from "../../util";

/** Screen heading: a plain-name eyebrow, the game-style title, and an optional side block. */
export function ScreenTitle({ eyebrow, title, sub, side }: { eyebrow: string; title: string; sub?: string; side?: ReactNode }) {
  return (
    <div className="screen-title">
      <div>
        <div className="eyebrow">{eyebrow}</div>
        <h1 className="display">{title}</h1>
        {sub && <p className="sub">{sub}</p>}
      </div>
      {side}
    </div>
  );
}

/** Skill chips. Clicking one toggles it in the shared filter. */
export function SkillChips({ skills, tags }: { skills: string[]; tags?: string[] }) {
  const { filter, pickSkill } = usePortfolio();
  if (skills.length === 0 && !(tags?.length)) return null;
  return (
    <div className="sc-chips">
      {skills.map((s) => (
        <button key={s} type="button" className={"chip" + (filter.picked.has(s) ? " on" : "")} aria-pressed={filter.picked.has(s)} title={`Filter by ${s}`} onClick={() => pickSkill(s)}>{s}</button>
      ))}
      {tags?.map((t) => <span key={t} className="chip tag" title="Free-text tag, not linked to a skill">{t}</span>)}
    </div>
  );
}

export function ExtLinks({ links }: { links: PortfolioLink[] }) {
  if (links.length === 0) return null;
  return (
    <p className="sc-links">
      {links.map((l) => <a key={l.label} href={l.href} target="_blank" rel="noopener noreferrer">{l.label} ↗</a>)}
    </p>
  );
}

/** Active skill filter: one chip per picked skill with an x, plus Clear. Hidden when nothing is picked. */
export function FilterBar() {
  const { filter, pickSkill, clear } = usePortfolio();
  if (!isActive(filter)) return null;
  const q = tokens(filter.q).length > 0;
  return (
    <div className="sc-filter" role="status" aria-label="Active filter">
      <span className="eyebrow">Filtered by</span>
      {[...filter.picked].map((s) => (
        <button key={s} type="button" className="chip on" aria-label={`Remove ${s}`} onClick={() => pickSkill(s)}>{s} <span aria-hidden="true">×</span></button>
      ))}
      {q && <span className="chip tag">text: {filter.q}</span>}
      <button type="button" className="btn sm" onClick={clear}>Clear</button>
    </div>
  );
}

export const Rule = ({ children }: { children: ReactNode }) => <div className="rule"><i /><span className="eyebrow">{children}</span><i /></div>;

export const isScrollable = (el: Element | null): el is Element & { scrollIntoView: (o?: ScrollIntoViewOptions) => void } => !!el && typeof (el as HTMLElement).scrollIntoView === "function";
