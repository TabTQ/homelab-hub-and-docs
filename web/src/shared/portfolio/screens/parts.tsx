import type { ReactNode } from "react";
import { isActive, tokens } from "../../filter";
import { useMemo } from "react";
import { usePortfolio } from "../context";
import { VhIcon, resolveSkillIcon } from "../icons";
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
export function SkillChips({ skills, tags, icons = false }: { skills: string[]; tags?: string[]; icons?: boolean }) {
  const { data, filter, pickSkill } = usePortfolio();
  const index = useMemo(() => {
    const m = new Map<string, { group: string; ic?: string | null }>();
    if (icons) data.skills.forEach((g) => g.skills.forEach((s) => m.set(s.l, { group: g.name, ic: s.ic })));
    return m;
  }, [data.skills, icons]);
  if (skills.length === 0 && !(tags?.length)) return null;
  return (
    <div className="sc-chips">
      {skills.map((s) => (
        <button key={s} type="button" className={"chip" + (filter.picked.has(s) ? " on" : "")} aria-pressed={filter.picked.has(s)} title={`Filter by ${s}`} onClick={() => pickSkill(s)}>{icons && <VhIcon name={resolveSkillIcon(s, index.get(s)?.group, index.get(s)?.ic)} size={14} className="chip-ic" />}{s}</button>
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

const Pencil = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d="M4 20l1-4L16.5 4.5a2.1 2.1 0 013 3L8 19z" /><path d="M14 7l3 3" /></svg>
);

/** A small pencil button. Renders nothing outside the editor. `text` adds a visible word next to the pencil. */
export function EditBtn({ label, resource, id, text, aria, className = "" }: { label: string; resource: string; id?: number; text?: string; aria?: string; className?: string }) {
  const { edit } = usePortfolio();
  if (!edit) return null;
  return (
    <button type="button" className={`btn sm ed-btn ${className}`.trim()} aria-label={aria ?? `Edit ${label}`} title={aria ?? `Edit ${label}`} onClick={(e) => { e.stopPropagation(); edit.onEdit(resource, id); }}>
      <Pencil />{text && <span>{text}</span>}
    </button>
  );
}

/** An "Add ..." button. Renders nothing outside the editor. `parent` is the parent row for child resources. */
export function AddBtn({ label, resource, parent, aria, className = "" }: { label: string; resource: string; parent?: { api: string; id: number }; aria?: string; className?: string }) {
  const { edit } = usePortfolio();
  if (!edit) return null;
  return <button type="button" className={`btn sm ed-add ${className}`.trim()} aria-label={aria ?? `Add ${label}`} onClick={() => edit.onAdd(resource, parent)}>+ Add {label}</button>;
}

/** A short hint shown only in the editor, optionally with a link to another tab. */
export function EditHint({ children }: { children: ReactNode }) {
  const { edit } = usePortfolio();
  if (!edit) return null;
  return <p className="ed-hint">{children}</p>;
}
