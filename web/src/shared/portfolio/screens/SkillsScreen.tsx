import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from "react";
import { relatedSkills } from "../../filter";
import type { Portfolio } from "../../types";
import { usePortfolio } from "../context";
import { assignConstellations, backgroundStars, branchOf, CENTRE, layoutConstellations, SKY_H, SKY_W, segHitsBox, starRadius, type PlacedFigure } from "./constellation";
import { constellationLabel } from "./constellations";
import { VhIcon, resolveSkillIcon } from "../icons";
import { AddBtn, EditBtn } from "./parts";
import { centreBox, clientToWorld, easeInOut, fullView, lerpView, panBy, screenScale, wheelFactor, zoomAt, zoomOf, type View } from "./viewport";
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
const BG = backgroundStars(300);
const plural = (n: number) => `${n} linked item${n === 1 ? "" : "s"}`;
const WORLD = { w: SKY_W, h: SKY_H };
/** Skill labels appear once the sky is zoomed in past this. */
const LABEL_ZOOM = 1.9;
const ANIM_MS = 400;
const fmt = (n: number) => n.toFixed(1);

const reducedMotion = () => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
const isPhone = () => typeof matchMedia === "function" && matchMedia("(max-width: 760px)").matches;

interface LabelSpot { x: number; y: number; anchor: "start" | "middle" | "end" }
interface Bx { x0: number; y0: number; x1: number; y1: number }

/** Where the group name and constellation name sit: under the centre node, nudged down by the figure's clear gap. */
function groupLabel(P: PlacedFigure, f: number) {
  const e = 12 * f;
  const top = P.cy + Math.max(P.fig.labelDy, e + 4 * f);
  return { e, nameY: top + 11 * f, constY: top + 25 * f, top };
}

/** Pick which skill labels to draw: stars in view, most important first, skipping any label that would collide. */
function chooseLabels(placed: PlacedFigure[], groups: Portfolio["skills"], view: View, f: number, zoom: number, focus: number | null, picked: Set<string>, sel: { g: number; s: number } | null): Map<string, LabelSpot> {
  const out = new Map<string, LabelSpot>();
  const fs = 12.5 * f;
  const boxes: Bx[] = [];
  // The centre node and group names are already on the sky: keep star labels off them.
  placed.forEach((P, gi) => {
    const g = groupLabel(P, f);
    const w = Math.max(groups[gi].name.length * 8.6 * f, 60 * f) / 2;
    boxes.push({ x0: P.cx - g.e * 1.2, x1: P.cx + g.e * 1.2, y0: P.cy - g.e * 1.2, y1: P.cy + g.e * 1.2 });
    boxes.push({ x0: P.cx - w, x1: P.cx + w, y0: g.top, y1: g.constY + 4 * f });
  });
  const cand: { gi: number; si: number; x: number; y: number; r: number; w: number; rank: number }[] = [];
  placed.forEach((p, gi) => {
    if (focus === null ? zoom < LABEL_ZOOM : focus !== gi) return;
    groups[gi].skills.forEach((s, si) => {
      const sp = p.fig.spots[si];
      const x = p.cx + sp.x, y = p.cy + sp.y;
      if (x < view.x - 20 * f || x > view.x + view.w + 20 * f || y < view.y - 20 * f || y > view.y + view.h + 20 * f) return;
      const important = picked.has(s.l) || (sel?.g === gi && sel.s === si);
      cand.push({ gi, si, x, y, r: starRadius(s.n) / Math.sqrt(zoom), w: s.l.length * fs * 0.62 + 8 * f, rank: (important ? 1000 : 0) + s.n });
    });
  });
  cand.sort((a, b) => b.rank - a.rank || a.gi - b.gi || a.si - b.si);
  const overlaps = (b: Bx) => boxes.some((o) => b.x0 < o.x1 && b.x1 > o.x0 && b.y0 < o.y1 && b.y1 > o.y0);
  for (const c of cand) {
    const P = placed[c.gi];
    const h = fs * 1.3, gap = c.r + 4 * f;
    const spots: { box: Bx; spot: LabelSpot }[] = [
      { box: { x0: c.x - c.w / 2, x1: c.x + c.w / 2, y0: c.y - gap - h, y1: c.y - gap }, spot: { x: c.x, y: c.y - gap - 0.25 * fs, anchor: "middle" } },
      { box: { x0: c.x - c.w / 2, x1: c.x + c.w / 2, y0: c.y + gap, y1: c.y + gap + h }, spot: { x: c.x, y: c.y + gap + 0.95 * fs, anchor: "middle" } },
      { box: { x0: c.x + gap, x1: c.x + gap + c.w, y0: c.y - h / 2, y1: c.y + h / 2 }, spot: { x: c.x + gap, y: c.y + 0.35 * fs, anchor: "start" } },
      { box: { x0: c.x - gap - c.w, x1: c.x - gap, y0: c.y - h / 2, y1: c.y + h / 2 }, spot: { x: c.x - gap, y: c.y + 0.35 * fs, anchor: "end" } },
    ];
    const free = spots.filter((o) => !overlaps(o.box));
    const crossesLine = (b: Bx) => P.fig.segments.some(([i, j]) => segHitsBox(P.cx + P.fig.stars[i].x, P.cy + P.fig.stars[i].y, P.cx + P.fig.stars[j].x, P.cy + P.fig.stars[j].y, b));
    const pick = free.find((o) => !crossesLine(o.box)) ?? free[0];
    if (pick) { boxes.push(pick.box); out.set(`${c.gi}:${c.si}`, pick.spot); }
  }
  return out;
}

export function SkillsScreen() {
  const { data, filter, pickSkill, go, edit } = usePortfolio();
  const [sel, setSel] = useState<{ g: number; s: number } | null>(null);
  const [listMode, setListMode] = useState(() => isPhone() || !!edit);
  const [view, setView] = useState<View>(() => fullView(WORLD));
  const [focus, setFocus] = useState<number | null>(null);
  const [box, setBox] = useState({ left: 0, top: 0, width: 1100, height: 590 });
  const svgRef = useRef<SVGSVGElement>(null);
  const viewRef = useRef(view);
  const targetRef = useRef(view);
  const raf = useRef(0);
  const ptrs = useRef(new Map<number, { x: number; y: number }>());
  const drag = useRef({ moved: false, sx: 0, sy: 0, dist: 0, mx: 0, my: 0 });

  const groups = data.skills;
  const assigned = useMemo(() => assignConstellations(groups.map((g) => ({ count: g.skills.length, constellation: g.constellation }))), [groups]);
  const placed = useMemo(() => layoutConstellations(groups.map((g, i) => ({ key: assigned[i], ns: g.skills.map((s) => s.n), name: g.name }))), [groups, assigned]);
  const all = groups.flatMap((g) => g.skills);
  const linkedCount = all.filter((s) => s.n > 0).length;
  const related = useMemo(() => relatedSkills(filter, data), [filter, data]);
  const picked = filter.picked;

  // ---- view control ----
  const cancelAnim = useCallback(() => { if (raf.current) { cancelAnimationFrame(raf.current); raf.current = 0; } }, []);
  const apply = useCallback((v: View) => { viewRef.current = v; setView(v); }, []);
  /** Jump straight to a view (wheel, drag, pinch). */
  const setNow = useCallback((v: View) => { cancelAnim(); targetRef.current = v; apply(v); if (zoomOf(v, WORLD) < 1.02) setFocus(null); }, [apply, cancelAnim]);
  /** Glide to a view over about 400 ms, or jump when the visitor prefers reduced motion. */
  const animateTo = useCallback((target: View) => {
    cancelAnim();
    targetRef.current = target;
    const from = viewRef.current;
    if (reducedMotion() || typeof requestAnimationFrame !== "function") { apply(target); return; }
    const t0 = performance.now();
    const step = (now: number) => {
      const t = Math.min(1, (now - t0) / ANIM_MS);
      apply(t >= 1 ? target : lerpView(from, target, easeInOut(t)));
      raf.current = t < 1 ? requestAnimationFrame(step) : 0;
    };
    raf.current = requestAnimationFrame(step);
  }, [apply, cancelAnim]);
  const resetView = useCallback(() => { setFocus(null); animateTo(fullView(WORLD)); }, [animateTo]);
  const zoomButton = (factor: number) => {
    const v = targetRef.current;
    const next = zoomAt(v, WORLD, factor, v.x + v.w / 2, v.y + v.h / 2);
    if (zoomOf(next, WORLD) < 1.02) setFocus(null);
    animateTo(next);
  };
  const zoomToGroup = (gi: number) => {
    if (focus === gi) { resetView(); return; }
    const r = placed[gi]?.rect;
    if (!r) return;
    setFocus(gi);
    animateTo(centreBox(WORLD, { x: r.x0, y: r.y0, w: r.x1 - r.x0, h: r.y1 - r.y0 }, 0.9));
  };

  useEffect(() => () => { if (raf.current) cancelAnimationFrame(raf.current); }, []);

  // Size of the drawn sky, to keep text a steady size on screen while the view changes.
  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    const measure = () => { const r = el.getBoundingClientRect(); if (r.width > 0) setBox({ left: r.left, top: r.top, width: r.width, height: r.height }); };
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [listMode]);

  // Wheel and trackpad. Needs a non-passive listener so the page does not scroll or browser-zoom over the sky.
  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const p = clientToWorld(viewRef.current, el.getBoundingClientRect(), e.clientX, e.clientY);
      setNow(zoomAt(viewRef.current, WORLD, wheelFactor(e.deltaY, e.deltaMode, e.ctrlKey), p.x, p.y));
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, [setNow]);

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setSel(null);
      if (focus !== null) resetView();
    };
    addEventListener("keydown", onKey);
    return () => removeEventListener("keydown", onKey);
  }, [focus, resetView]);

  // ---- pointer: drag to pan, two fingers to pinch ----
  const onPointerDown = (e: ReactPointerEvent<SVGSVGElement>) => {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    cancelAnim();
    ptrs.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (ptrs.current.size === 1) drag.current = { moved: false, sx: e.clientX, sy: e.clientY, dist: 0, mx: 0, my: 0 };
    if (ptrs.current.size === 2) {
      const [a, b] = [...ptrs.current.values()];
      drag.current = { ...drag.current, moved: true, dist: Math.hypot(a.x - b.x, a.y - b.y), mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 };
    }
  };
  const onPointerMove = (e: ReactPointerEvent<SVGSVGElement>) => {
    const prev = ptrs.current.get(e.pointerId);
    if (!prev) return;
    ptrs.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const el = e.currentTarget;
    const rect = el.getBoundingClientRect();
    const v = viewRef.current;
    const s = screenScale(v, rect);
    if (ptrs.current.size >= 2) {
      const [a, b] = [...ptrs.current.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y), mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
      const d = drag.current;
      if (d.dist > 0 && dist > 0) {
        const at = clientToWorld(v, rect, d.mx, d.my);
        const zoomed = zoomAt(v, WORLD, dist / d.dist, at.x, at.y);
        setNow(panBy(zoomed, WORLD, -(mx - d.mx) / screenScale(zoomed, rect), -(my - d.my) / screenScale(zoomed, rect)));
      }
      drag.current = { ...d, dist, mx, my };
      return;
    }
    const d = drag.current;
    if (!d.moved && Math.hypot(e.clientX - d.sx, e.clientY - d.sy) > 5) {
      d.moved = true;
      try { el.setPointerCapture(e.pointerId); } catch { /* not supported here */ }
    }
    if (d.moved) setNow(panBy(v, WORLD, -(e.clientX - prev.x) / s, -(e.clientY - prev.y) / s));
  };
  const onPointerEnd = (e: ReactPointerEvent<SVGSVGElement>) => {
    ptrs.current.delete(e.pointerId);
    // The click that ends a drag arrives right after pointerup. Forget the drag shortly after.
    if (ptrs.current.size === 0) setTimeout(() => { drag.current.moved = false; }, 60);
    if (ptrs.current.size === 1) { const [p] = [...ptrs.current.values()]; drag.current = { ...drag.current, dist: 0, sx: p.x, sy: p.y }; }
  };
  /** A drag ends with a click on whatever is under the pointer. Swallow that one. */
  const onClickCapture = (e: ReactMouseEvent) => {
    if (drag.current.moved) { e.stopPropagation(); e.preventDefault(); drag.current.moved = false; }
  };
  const onSkyKey = (e: ReactKeyboardEvent<SVGSVGElement>) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const v = targetRef.current;
    const step = v.w * 0.12;
    if (e.key === "+" || e.key === "=") zoomButton(1.4);
    else if (e.key === "-" || e.key === "_") zoomButton(1 / 1.4);
    else if (e.key === "0") resetView();
    else if (e.key === "ArrowLeft") animateTo(panBy(v, WORLD, -step, 0));
    else if (e.key === "ArrowRight") animateTo(panBy(v, WORLD, step, 0));
    else if (e.key === "ArrowUp") animateTo(panBy(v, WORLD, 0, -step));
    else if (e.key === "ArrowDown") animateTo(panBy(v, WORLD, 0, step));
    else return;
    e.preventDefault();
  };

  // ---- drawing ----
  const zoom = zoomOf(view, WORLD);
  const s = screenScale(view, box);
  const f = 1 / s; // one screen pixel, in sky units
  const k = 1 / Math.sqrt(zoom); // stars grow a little when zoomed, not in step with the zoom
  const labels = useMemo(() => chooseLabels(placed, groups, view, f, zoom, focus, picked, sel), [placed, groups, view, f, zoom, focus, picked, sel]);
  const hitR = Math.max(12, 20 * f);
  // On a narrow sky the group names would pile up, so they wait until the visitor zooms in.
  const compact = box.width < 640;

  const chosen = sel ? { group: groups[sel.g], skill: groups[sel.g]?.skills[sel.s] } : null;
  const work = chosen?.skill ? linkedWork(data, chosen.skill.l) : null;
  const act = (e: ReactKeyboardEvent, fn: () => void) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); e.stopPropagation(); fn(); } };

  return (
    <div className={"skills" + (listMode ? " list" : "")}>
      <div className="sky-bar">
        <div className="zoom-ctl" role="group" aria-label="Sky zoom">
          <button className="btn sm" type="button" aria-label="Zoom out" onClick={() => zoomButton(1 / 1.4)} disabled={zoom <= 1.001}>−</button>
          <span className="zoom-read" aria-hidden="true">{fmt(zoom)}×</span>
          <button className="btn sm" type="button" aria-label="Zoom in" onClick={() => zoomButton(1.4)} disabled={zoom >= 5.99}>+</button>
          <button className="btn sm" type="button" aria-label="Reset zoom" onClick={resetView} disabled={zoom <= 1.001 && focus === null}>Reset</button>
        </div>
        <button className="btn sm" type="button" onClick={() => setListMode((v) => !v)} aria-pressed={listMode}>{listMode ? "Star view" : "List view"}</button>
        {edit && !listMode && focus !== null && groups[focus] && (
          <>
            <EditBtn label={`group: ${groups[focus].name}`} resource="skill-groups" id={groups[focus].id} text="Edit group" />
            <AddBtn label="skill" resource="skills" parent={{ api: "groupId", id: groups[focus].id }} />
          </>
        )}
        {edit && listMode && <AddBtn label="group" resource="skill-groups" />}
      </div>
      <div className="sky-wrap">
        <svg ref={svgRef} className="sky" viewBox={`${fmt(view.x)} ${fmt(view.y)} ${fmt(view.w)} ${fmt(view.h)}`} preserveAspectRatio="xMidYMid meet" role="group"
          aria-label="Skill constellations. Scroll or pinch to zoom, drag to pan, plus and minus to zoom, arrow keys to pan, zero to reset." tabIndex={0}
          data-zoom={fmt(zoom)}
          onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerEnd} onPointerCancel={onPointerEnd} onClickCapture={onClickCapture} onKeyDown={onSkyKey}
          onClick={(e) => { if (!(e.target as Element).closest(".star, .node")) setSel(null); }}>
          {BG.map((b, i) => <circle key={i} className="bg-star" cx={b.x} cy={b.y} r={b.r * Math.max(0.5, k)} opacity={b.o} />)}
          {groups.map((g, gi) => {
            const P = placed[gi];
            if (!P) return null;
            const F = P.fig;
            const branch = branchOf(gi, groups.length);
            const colour = `var(--b-${branch})`;
            const pt = (i: number) => `${(P.cx + F.stars[i].x).toFixed(1)} ${(P.cy + F.stars[i].y).toFixed(1)}`;
            let solid = "", faint = "";
            for (const [a, b] of F.segments) {
              const seg = `M${pt(a)}L${pt(b)}`;
              if (F.starSkill[a] >= 0 && F.starSkill[b] >= 0) solid += seg; else faint += seg;
            }
            const dimmed = focus !== null ? focus !== gi : sel !== null && sel.g !== gi;
            const cname = constellationLabel(F.key);
            const GL = groupLabel(P, f);
            const e = GL.e;
            const isFocus = focus === gi;
            return (
              <g key={g.id} className={"grp" + (dimmed ? " dim" : "") + (isFocus ? " focus" : "")} style={{ color: colour }}>
                <line className="spoke" x1={CENTRE.x} y1={CENTRE.y} x2={P.cx} y2={P.cy} />
                {faint && <path className="link faint" stroke={colour} d={faint} />}
                {solid && <path className="link" stroke={colour} d={solid} />}
                {F.spots.map((sp, si) => sp.star < 0 && (
                  <line key={`sat-${g.skills[si].id}`} className="link sat" stroke={colour}
                    x1={P.cx + F.stars[sp.anchor].x} y1={P.cy + F.stars[sp.anchor].y} x2={P.cx + sp.x} y2={P.cy + sp.y} />
                ))}
                {F.stars.map((st, i) => F.starSkill[i] < 0 && <circle key={`f${i}`} className="field" cx={P.cx + st.x} cy={P.cy + st.y} r={2.2 * k} />)}
                {g.skills.map((sk, si) => {
                  const p = { x: P.cx + F.spots[si].x, y: P.cy + F.spots[si].y };
                  const r = starRadius(sk.n) * k;
                  const isSel = sel?.g === gi && sel.s === si;
                  const isPicked = picked.has(sk.l);
                  const choose = () => setSel(isSel ? null : { g: gi, s: si });
                  const lab = labels.get(`${gi}:${si}`);
                  const tx = lab ? lab.x : p.x, ty = lab ? lab.y : (p.y <= P.cy ? p.y - r - 5 * f : p.y + r + 14 * f);
                  return (
                    <g key={sk.id} className={"star" + (sk.n ? "" : " zero") + (isSel ? " sel" : "") + (isPicked ? " picked" : "") + (lab ? " lbl" : "") + (related.has(sk.l) && !isPicked && picked.size > 0 ? " rel" : "")}
                      role="button" tabIndex={0} aria-label={`${sk.l}, ${plural(sk.n)}`} aria-pressed={isPicked}
                      onClick={choose} onKeyDown={(ev) => act(ev, choose)}>
                      <circle className="ring" cx={p.x} cy={p.y} r={r + 6 * f} />
                      <circle className="c" cx={p.x} cy={p.y} r={r} />
                      <circle className="hit" cx={p.x} cy={p.y} r={hitR} />
                      <text x={tx} y={ty} textAnchor={lab ? lab.anchor : "middle"} style={{ fontSize: 12.5 * f, strokeWidth: 3 * f }}>{sk.l}</text>
                    </g>
                  );
                })}
                <g className="node" role="button" tabIndex={0} aria-pressed={isFocus}
                  aria-label={isFocus ? `${g.name}, ${cname}. Zoom back out` : `${g.name}, ${cname}. Zoom to this constellation`}
                  onClick={() => zoomToGroup(gi)} onKeyDown={(ev) => act(ev, () => zoomToGroup(gi))}>
                  <circle className="hit" cx={P.cx} cy={P.cy} r={e * 1.7} />
                  <path className="emb" stroke={colour} strokeWidth={1.3 * f} d={`M${P.cx} ${P.cy - e}l${e} ${e}-${e} ${e}-${e}-${e}z`} />
                  <circle cx={P.cx} cy={P.cy} r={2.6 * f} fill={colour} />
                  <title>{`${g.name} (${cname}). Click to zoom.`}</title>
                </g>
                {(!compact || zoom >= 2.2 || isFocus) && <>
                <text className="gname" x={P.cx} y={GL.nameY} style={{ fontSize: 12 * f, strokeWidth: 3.5 * f }}>{g.name}</text>
                <text className="cname" x={P.cx} y={GL.constY} style={{ fontSize: 10.5 * f, strokeWidth: 3 * f }}>{cname}</text>
                </>}
              </g>
            );
          })}
          <circle cx={CENTRE.x} cy={CENTRE.y} r={30} fill="var(--bg)" stroke="var(--ink)" strokeWidth={1.6} vectorEffect="non-scaling-stroke" />
          <circle cx={CENTRE.x} cy={CENTRE.y} r={23} fill="none" stroke="var(--ink)" strokeWidth={0.7} vectorEffect="non-scaling-stroke" />
          <path d={`M${CENTRE.x} ${CENTRE.y - 11}l11 11-11 11-11-11z`} fill="none" stroke="var(--ink)" strokeWidth={1.2} vectorEffect="non-scaling-stroke" />
        </svg>
        <div className="detail" aria-live="polite">
          {chosen?.skill && work && (
            <article className={`card ${tierOf(chosen.skill.n)}`}>
              <div className="emblem"><span><VhIcon name={resolveSkillIcon(chosen.skill.l, chosen.group.name, chosen.skill.ic)} size={20} /></span></div>
              <div className="card-head"><h3>{chosen.skill.l}</h3><p>{chosen.group.name} · {plural(chosen.skill.n)}</p></div>
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
                {edit && (
                  <div className="ed-row">
                    <EditBtn label={`skill: ${chosen.skill.l}`} resource="skills" id={chosen.skill.id} text="Edit skill" />
                    <EditBtn label={`group: ${chosen.group.name}`} resource="skill-groups" id={chosen.group.id} text="Edit group" />
                  </div>
                )}
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
                <span key={s.id} className={"chip-wrap" + (edit ? " ed" : "")}>
                  <button type="button" className={"chip" + (picked.has(s.l) ? " on" : "") + (s.n ? "" : " hollow")} aria-pressed={picked.has(s.l)}
                    title={plural(s.n)} onClick={() => pickSkill(s.l)}>
                    <VhIcon name={resolveSkillIcon(s.l, g.name, s.ic)} size={14} className="chip-ic" />{s.l}<b className="num" style={{ color: `var(--b-${branchOf(gi, groups.length)})` }}>{s.n || "–"}</b>
                  </button>
                  <EditBtn label={`skill: ${s.l}`} resource="skills" id={s.id} className="ed-chip" />
                </span>
              ))}
            </div>
            {edit && (
              <div className="ed-row">
                <EditBtn label={`group: ${g.name}`} resource="skill-groups" id={g.id} text="Edit group" />
                <AddBtn label="skill" aria={`Add skill to ${g.name}`} resource="skills" parent={{ api: "groupId", id: g.id }} />
              </div>
            )}
          </section>
        ))}
      </div>
    </div>
  );
}
