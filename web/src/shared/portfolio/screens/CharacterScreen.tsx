import { useEffect, useRef, type CSSProperties } from "react";
import { reducedMotion } from "../Embers";
import { usePortfolio } from "../context";

const AVATAR = `${import.meta.env.BASE_URL}avatar.webp`;

/** The 4 skills with the most linked items, most linked first. */
export function coreSkills(groups: { skills: { l: string; n: number }[] }[], count = 4): { l: string; n: number }[] {
  return groups.flatMap((g) => g.skills).filter((s) => s.n > 0).sort((a, b) => b.n - a.n).slice(0, count);
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
        {core.map((s) => <a key={s.l} className="slot" href="#/skills" title={`${s.l}: ${s.n} linked`} onClick={() => pick(s.l)}><span className="lbl">{s.l}</span><Pips n={pips(s.n, maxN)} /></a>)}
      </section>

      <section className="center" aria-label="Avatar">
        <Avatar name={p.name} />
        {first && <div className="power"><div className="eyebrow">{first.label}</div><div className="num">{first.value}</div></div>}
      </section>

      <section className="slots" aria-label="Featured projects">
        <div className="eyebrow">Featured work</div>
        {featured.map((x, i) => <a key={x.id} className="slot" href="#/inventory" title={x.title}><span className="lbl">{x.title.split(/[:(]/)[0].trim()}</span><Pips n={i === 0 ? 3 : 2} /></a>)}
      </section>

      <section className="pf-res" aria-label="Key numbers">
        {p.stats.map((s, i) => <div className="r" key={i}><b>{s.value}</b><span>{s.label}</span>{s.sub && <small>{s.sub}</small>}</div>)}
      </section>
    </div>
  );
}
