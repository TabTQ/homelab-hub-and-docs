import { useState } from "react";
import { matches, isActive } from "../../filter";
import { safeHref } from "../../util";
import type { Education } from "../../types";
import { usePortfolio } from "../context";
import { FilterBar, ScreenTitle, SkillChips } from "./parts";
import "./screens.css";

const plural = (t: string) => (/s$/i.test(t) ? t : /y$/i.test(t) ? t.slice(0, -1) + "ies" : t + "s");
const SOCIAL_NAMES: Record<string, string> = { github: "GitHub", linkedin: "LinkedIn" };
const socialName = (k: string) => SOCIAL_NAMES[k] ?? k.charAt(0).toUpperCase() + k.slice(1);

function EduRow({ e, dim }: { e: Education; dim: boolean }) {
  const [open, setOpen] = useState(false);
  const href = safeHref(e.url);
  const more = !!(e.desc || href || e.skills.length);
  const head = (
    <>
      <span className="gem unknown" aria-hidden="true" />
      <span className="t"><b>{e.title}</b><span>{e.inst} · {e.date}</span></span>
      <span className="tag">{e.type}</span>
    </>
  );
  return (
    <li className={dim ? "sc-dim" : undefined}>
      {more ? <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>{head}</button> : <span className="row">{head}</span>}
      {more && open && (
        <div className="sc-more">
          {e.desc && <p>{e.desc}</p>}
          {href && <p className="sc-links"><a href={href} target="_blank" rel="noopener noreferrer">View ↗</a></p>}
          <SkillChips skills={e.skills} />
        </div>
      )}
    </li>
  );
}

export function CodexScreen() {
  const { data, filter } = usePortfolio();
  const p = data.profile;
  const active = isActive(filter);
  const types = [...new Set(data.education.map((e) => e.type))];
  const socials = Object.entries(p.socials ?? {}).filter(([k, v]) => !k.endsWith("Label") && safeHref(v));
  const tel = p.phone?.replace(/[^\d+]/g, "");
  const facts = [["Location", p.location], ["Status", p.status]].filter((f): f is [string, string] => !!f[1]);
  const hasContact = !!(p.email || tel || socials.length);

  return (
    <div className="sc">
      <ScreenTitle eyebrow="Education and contact" title="Codex" sub="Degrees, publications, certificates, a short bio and how to reach me." />
      <FilterBar />
      <div className="split sc-codex">
        <div className="grid">
          {types.map((t) => {
            const list = data.education.filter((e) => e.type === t);
            return (
              <section key={t} className="panel" aria-label={plural(t)}>
                <header><h3>{plural(t)}</h3><span className="count">{list.length} {list.length === 1 ? "entry" : "entries"}</span></header>
                <ul className="rows">{list.map((e) => <EduRow key={e.id} e={e} dim={active && !matches(filter, e)} />)}</ul>
              </section>
            );
          })}
          {p.bio.length > 0 && (
            <section className="panel" aria-label="About">
              <header><h3>About</h3></header>
              {p.bio.map((b, i) => <p key={i} className="sc-text">{b}</p>)}
            </section>
          )}
        </div>
        {(hasContact || facts.length > 0) && (
          <aside className="card gold sc-raven" aria-label="Contact">
            <div className="emblem"><span>R</span></div>
            <div className="card-head"><h3>Send a raven</h3><p>Ways to reach me</p></div>
            <div className="card-body">
              {facts.length > 0 && <dl className="stats one">{facts.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>}
              {p.email && <a className="btn pri" href={`mailto:${p.email}`}>Email</a>}
              {tel && <a className="btn" href={`tel:${tel}`}>Phone</a>}
              {socials.map(([k, v]) => <a key={k} className="btn" href={safeHref(v)} target="_blank" rel="noopener noreferrer">{socialName(k)}</a>)}
            </div>
          </aside>
        )}
      </div>
    </div>
  );
}
