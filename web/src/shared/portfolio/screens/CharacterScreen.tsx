import { useEffect, useRef, type CSSProperties } from "react";
import { reducedMotion } from "../Embers";
import { usePortfolio } from "../context";
import { VhIcon, resolveProjectIcon, resolveSkillIcon } from "../icons";

const AVATAR = `${import.meta.env.BASE_URL}avatar.webp`;

type CoreSkill = { l: string; n: number; ic?: string | null; core?: boolean };
type CoreGroup<S> = { name?: string; skills: S[] };
export type CoreEntry<S extends CoreSkill = CoreSkill> = S & { group: string };

/** Skills flagged `core` (up to `count`, in data order), or else the ones with the most linked items. */
export function coreSkills<S extends CoreSkill>(groups: CoreGroup<S>[], count = 4): CoreEntry<S>[] {
  const all = groups.flatMap((g) => g.skills.map((s) => ({ ...s, group: g.name ?? "" })));
  const flagged = all.filter((s) => s.core);
  return flagged.length ? flagged.slice(0, count) : all.filter((s) => s.n > 0).sort((a, b) => b.n - a.n).slice(0, count);
}

const pips = (n: number, max: number) => Math.max(1, Math.min(3, Math.ceil((n / Math.max(1, max)) * 3)));
const Pips = ({ n }: { n: number }) => <span className="pips" aria-hidden="true">{Array.from({ length: n }, (_, i) => <i key={i} />)}</span>;

export function Avatar({ name }: { name: string }) {
  const el = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = el.current;
    if (!node || reducedMotion()) return;
    const move = (e: PointerEvent) => {
      node.style.setProperty("--px", ((e.clientX / innerWidth) * 2 - 1).toFixed(3));
      node.style.setProperty("--py", ((e.clientY / innerHeight) * 2 - 1).toFixed(3));
    };
    const leave = () => { node.style.setProperty("--px", "0"); node.style.setProperty("--py", "0"); };
    addEventListener("pointermove", move, { passive: true });
    document.addEventListener("pointerleave", leave);
    return () => { removeEventListener("pointermove", move); document.removeEventListener("pointerleave", leave); };
  }, []);
  const lit = { "--av": `url(${AVATAR})` } as CSSProperties;
  return (
    <div className="avatar" ref={el}>
      <div className="halo" aria-hidden="true">
        <svg viewBox="0 0 400 400" fill="none" stroke="currentColor"><circle cx="200" cy="200" r="190" strokeDasharray="1 9" strokeWidth="3" /><circle cx="200" cy="200" r="172" strokeWidth="0.8" /><circle cx="200" cy="200" r="128" strokeWidth="0.6" /><path d="M200 28 372 200 200 372 28 200Z" strokeWidth="0.6" /><path d="M78 78 322 322M322 78 78 322" strokeWidth="0.4" /></svg>
      </div>
      <div className="fig" style={lit}>
        <div className="body">
          <img src={AVATAR} alt={`Portrait of ${name}`} width={900} height={900} />
          <div className="lit shade" /><div className="lit rim" /><div className="lit fire" />
        </div>
      </div>
    </div>
  );
}

export function CharacterScreen() {
  const { data, setFilter } = usePortfolio();
  const p = data.profile;
  const core = coreSkills(data.skills);
  const maxN = core[0]?.n ?? 1;
  const featured = data.projects.slice(0, 4);
  const first = p.stats[0];
  const pick = (l: string) => setFilter((f) => ({ ...f, picked: new Set([...f.picked, l]) }));

  return (
    <div className="stage">
      <section className="who" aria-label="Profile">
        {(p.location || p.status) && <div className="eyebrow">{[p.location, p.status].filter(Boolean).join(" · ")}</div>}
        <h1>{p.name}</h1>
        <div className="role">{p.title}</div>
        {p.tagline && <p>{p.tagline}</p>}
        <div className="cta">
          <a className="btn pri" href="#/quests">View experience</a>
          <a className="btn" href="#/codex">Contact</a>
        </div>
        <a className="sumlink" href="#/summary">Short on time? Read the one-page summary <span aria-hidden="true">→</span></a>
      </section>

      <section className="slots" aria-label="Core skills">
        <div className="eyebrow">Core skills</div>
        {core.map((s) => {
          const tip = `${s.l}: ${s.n} linked`;
          return (
            <a key={s.l} className="slot" href="#/skills" title={tip} aria-label={tip} onClick={() => pick(s.l)}>
              <VhIcon name={resolveSkillIcon(s.l, s.group, s.ic)} size={32} />
              <span className="lbl">{s.l}</span>
              <Pips n={pips(s.n, maxN)} />
            </a>
          );
        })}
      </section>

      <section className="center" aria-label="Avatar">
        <Avatar name={p.name} />
        {first && <div className="power"><div className="eyebrow">{first.label}</div><div className="num">{first.value}</div></div>}
      </section>

      <section className="slots" aria-label="Featured projects">
        <div className="eyebrow">Featured work</div>
        {featured.map((x, i) => (
          <a key={x.id} className="slot" href="#/inventory" title={x.title} aria-label={x.title}>
            <VhIcon name={resolveProjectIcon(x.title, x.type, x.icon)} size={32} />
            <span className="lbl">{x.title.split(/[:(]/)[0].trim()}</span>
            <Pips n={i === 0 ? 3 : 2} />
          </a>
        ))}
      </section>

      <section className="pf-res" aria-label="Key numbers">
        {p.stats.map((s, i) => <div className="r" key={i}><b>{s.value}</b><div className="tx"><span>{s.label}</span>{s.sub && <small>{s.sub}</small>}</div></div>)}
      </section>
    </div>
  );
}
