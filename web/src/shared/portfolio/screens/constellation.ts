// Pure layout maths for the skills sky. No DOM, no randomness: the same input always gives the same output.
//
// HOW A SKILL LANDS ON THE SKY (so a new skill needs no code change)
// 1. Every skill group gets a constellation. `group.constellation` wins if it is a known key. Otherwise groups are
//    handled from the biggest skill count to the smallest, and each takes the unused catalogue entry whose star count
//    is closest to its skill count, preferring one with at least as many stars. If the catalogue runs out, entries are reused.
// 2. Skills fill the constellation's stars in "primary" order (brightest first). The skills with the most linked work
//    (highest n) take the most important stars. Ties keep the order of the data.
// 3. Stars left over (fewer skills than stars) stay as small faint unlabelled "field" stars, so the figure keeps its shape.
// 4. Skills left over (more skills than stars) become satellite stars. Each sits just outside a line of the figure, longest
//    lines first, and is joined to the nearest end star by a thin line.
// 5. The group's centre node sits at the centroid of the catalogue stars. Figures are then packed onto the sky without overlap.
// Add a skill in the configuration screen and it appears in its group through rules 2 to 4.

import { CONSTELLATIONS, getConstellation, type Constellation } from "./constellations";

export const SKY_W = 1800;
export const SKY_H = 968;
export const CENTRE = { x: SKY_W / 2, y: SKY_H / 2 };
const STAR_PAD = 14; // room around the outermost star (star radius plus glow)
const GROUP_GAP = 22;
const EDGE = 12;
/** Half size of the box kept clear around the centre double ring. */
const CENTRE_HALF = { w: 64, h: 54 };
/** Label box, in sky units, hanging under the centre node. Assumes roughly 0.6 screen pixels per sky unit. */
const LABEL_MIN_DY = 24;
const LABEL_H = 54;
const LABEL_CHAR_W = 14;
/** Parts of the sky the page draws over (legend top left, corner badges), kept free of figures. */
const OBSTACLES: Rect[] = [
  { x0: 0, y0: 0, x1: 400, y1: 215 },
  { x0: 0, y0: SKY_H - 140, x1: 330, y1: SKY_H },
  { x0: SKY_W - 330, y0: SKY_H - 140, x1: SKY_W, y1: SKY_H },
];

export type Branch = "raven" | "bear" | "wolf";
const BRANCHES: Branch[] = ["raven", "bear", "wolf"];

/** Spread groups over the three branches in thirds, so ring neighbours share a colour. */
export function branchOf(index: number, total: number): Branch {
  if (total <= 0) return "raven";
  return BRANCHES[Math.min(2, Math.floor((index * 3) / total))];
}

/** Star radius from the linked count, capped. Hollow stars (n = 0) get a small fixed size. */
export function starRadius(n: number): number {
  return n > 0 ? Math.min(2.8 + n * 1.2, 9.5) : 3.4;
}

// ---- assignment ----

export interface AssignInput { count: number; constellation?: string | null }

/** One constellation key per group, deterministic. See rule 1 at the top of this file. */
export function assignConstellations(groups: AssignInput[]): string[] {
  const out: (string | null)[] = groups.map((g) => (g.constellation && getConstellation(g.constellation) ? g.constellation : null));
  const used = new Set<string>(out.filter((k): k is string => k !== null));
  const order = groups.map((_, i) => i).filter((i) => out[i] === null).sort((a, b) => groups[b].count - groups[a].count || a - b);
  for (const i of order) {
    const need = groups[i].count;
    const free = CONSTELLATIONS.filter((c) => !used.has(c.key));
    const pool = free.length ? free : CONSTELLATIONS;
    let best: Constellation = pool[0];
    let bestCost: [number, number, number] = [9, 0, 0];
    pool.forEach((c, idx) => {
      const n = c.stars.length;
      const cost: [number, number, number] = [n >= need ? 0 : 1, Math.abs(n - need), idx];
      if (cost[0] < bestCost[0] || (cost[0] === bestCost[0] && (cost[1] < bestCost[1] || (cost[1] === bestCost[1] && cost[2] < bestCost[2])))) { best = c; bestCost = cost; }
    });
    out[i] = best.key;
    used.add(best.key);
  }
  return out as string[];
}

// ---- one figure ----

export interface FigStar { x: number; y: number }
export interface SkillSpot {
  x: number; y: number;
  /** Catalogue star index this skill sits on, or -1 for a satellite. */
  star: number;
  /** Satellites only: the catalogue star the thin line joins. */
  anchor: number;
}
export interface Figure {
  key: string; name: string;
  /** Catalogue stars, relative to the figure centroid, in sky units. */
  stars: FigStar[];
  segments: [number, number][];
  /** One per skill, in the order of the input. */
  spots: SkillSpot[];
  /** Per catalogue star: the skill index placed on it, or -1 for a field star. */
  starSkill: number[];
  /** Label box under the centre node: top edge below the centroid and half width, in sky units. */
  labelDy: number; labelHalfW: number;
  bounds: { minX: number; minY: number; maxX: number; maxY: number };
}

const SAT_T = [0.5, 0.28, 0.72, 0.4, 0.6];

/** Length of the longer side of a figure, in sky units, from its star count. */
export function figureSpan(starCount: number, scale = 1): number {
  return (215 + 12 * starCount) * scale;
}

/** Place the skills of one group on a constellation. `ns` are the linked counts, in data order. */
export function buildFigure(key: string, ns: number[], scale = 1, name = ""): Figure {
  const c = getConstellation(key) ?? CONSTELLATIONS[0];
  const span = figureSpan(c.stars.length, scale);
  const mx = c.stars.reduce((a, s) => a + s[0], 0) / c.stars.length;
  const my = c.stars.reduce((a, s) => a + s[1], 0) / c.stars.length;
  const stars = c.stars.map(([x, y]) => ({ x: (x - mx) * span, y: (y - my) * span }));
  const rank = ns.map((_, i) => i).sort((a, b) => ns[b] - ns[a] || a - b);
  const starSkill = stars.map(() => -1);
  const spots: SkillSpot[] = ns.map(() => ({ x: 0, y: 0, star: -1, anchor: -1 }));
  // Longest lines first, ties by position in the list.
  const lines = c.segments.map((s, i) => ({ s, i, len: Math.hypot(stars[s[0]].x - stars[s[1]].x, stars[s[0]].y - stars[s[1]].y) }))
    .sort((a, b) => b.len - a.len || a.i - b.i);
  rank.forEach((skill, r) => {
    if (r < c.primary.length) {
      const si = c.primary[r];
      starSkill[si] = skill;
      spots[skill] = { x: stars[si].x, y: stars[si].y, star: si, anchor: -1 };
      return;
    }
    const j = r - c.primary.length;
    const seg = lines[j % lines.length];
    const round = Math.floor(j / lines.length);
    const a = stars[seg.s[0]], b = stars[seg.s[1]];
    const t = SAT_T[round % SAT_T.length];
    const px = a.x + (b.x - a.x) * t, py = a.y + (b.y - a.y) * t;
    let nx = -(b.y - a.y) / (seg.len || 1), ny = (b.x - a.x) / (seg.len || 1);
    if (nx * px + ny * py < 0) { nx = -nx; ny = -ny; }
    const dist = (30 + 12 * Math.floor(round / SAT_T.length) + 6 * (round % 2)) * scale;
    spots[skill] = { x: px + nx * dist, y: py + ny * dist, star: -1, anchor: t < 0.5 ? seg.s[0] : seg.s[1] };
  });
  const all = [...stars, ...spots];
  const bounds = {
    minX: Math.min(...all.map((p) => p.x)), maxX: Math.max(...all.map((p) => p.x)),
    minY: Math.min(...all.map((p) => p.y)), maxY: Math.max(...all.map((p) => p.y)),
  };
  // Hang the label just under the centre node. If that spot is crowded, slide it down to the first clear gap.
  const labelHalfW = Math.max(name.length * LABEL_CHAR_W, c.name.length * 9) / 2;
  const clear = (dy: number) => {
    const box = { x0: -labelHalfW - 6, x1: labelHalfW + 6, y0: dy - 4, y1: dy + LABEL_H + 4 };
    if (all.some((p) => p.x > box.x0 - 8 && p.x < box.x1 + 8 && p.y > box.y0 - 8 && p.y < box.y1 + 8)) return false;
    const lines: [FigStar, FigStar][] = [...c.segments.map(([a, b]) => [stars[a], stars[b]] as [FigStar, FigStar]),
      ...spots.filter((sp) => sp.star < 0).map((sp) => [stars[sp.anchor], sp] as [FigStar, FigStar])];
    return !lines.some(([a, b]) => segHitsBox(a.x, a.y, b.x, b.y, box));
  };
  let labelDy = bounds.maxY + 14;
  for (let dy = LABEL_MIN_DY; dy <= bounds.maxY; dy += 5) if (clear(dy)) { labelDy = dy; break; }
  return { key: c.key, name: c.name, stars, segments: c.segments, spots, starSkill, labelDy, labelHalfW, bounds };
}

// ---- the whole sky ----

export interface Rect { x0: number; y0: number; x1: number; y1: number }
export interface PlacedFigure {
  fig: Figure;
  /** Centroid, which is also where the centre node sits. */
  cx: number; cy: number;
  /** Everything the group occupies: stars, satellites and the label. */
  rect: Rect;
  scale: number;
}
export interface LayoutInput { key: string; ns: number[]; name?: string }

function figureRect(f: Figure): Rect {
  return {
    x0: Math.min(f.bounds.minX - STAR_PAD, -f.labelHalfW), x1: Math.max(f.bounds.maxX + STAR_PAD, f.labelHalfW),
    y0: f.bounds.minY - STAR_PAD, y1: Math.max(f.bounds.maxY + STAR_PAD, f.labelDy + LABEL_H),
  };
}

/** Does the line from (x1, y1) to (x2, y2) cross the box? (Liang-Barsky clipping.) */
export function segHitsBox(x1: number, y1: number, x2: number, y2: number, b: Rect): boolean {
  let t0 = 0, t1 = 1;
  const dx = x2 - x1, dy = y2 - y1;
  for (const [p, q] of [[-dx, x1 - b.x0], [dx, b.x1 - x1], [-dy, y1 - b.y0], [dy, b.y1 - y1]]) {
    if (p === 0) { if (q < 0) return false; continue; }
    const t = q / p;
    if (p < 0) { if (t > t1) return false; if (t > t0) t0 = t; } else { if (t < t0) return false; if (t < t1) t1 = t; }
  }
  return true;
}

export function rectsOverlap(a: Rect, b: Rect, gap = 0): boolean {
  return a.x0 < b.x1 + gap && a.x1 + gap > b.x0 && a.y0 < b.y1 + gap && a.y1 + gap > b.y0;
}

function tryLayout(inputs: LayoutInput[], w: number, h: number, scale: number): PlacedFigure[] | null {
  const figs = inputs.map((g) => buildFigure(g.key, g.ns, scale, g.name ?? ""));
  const rects = figs.map((f) => figureRect(f));
  const n = figs.length;
  const cx0 = w / 2, cy0 = h / 2;
  const sizes = rects.map((r) => Math.sqrt((r.x1 - r.x0) * (r.y1 - r.y0)));
  const total = sizes.reduce((a, b) => a + b, 0) || 1;
  const rx = w / 2 - Math.max(...rects.map((r) => r.x1 - r.x0), 0) / 2 - EDGE;
  const ry = h / 2 - Math.max(...rects.map((r) => r.y1 - r.y0), 0) / 2 - EDGE;
  const start = -Math.PI / 2 + 0.28;
  let acc = 0;
  const pos = sizes.map((s) => {
    const th = start + ((acc + s / 2) / total) * Math.PI * 2;
    acc += s;
    return { x: cx0 + Math.cos(th) * Math.max(rx, 10), y: cy0 + Math.sin(th) * Math.max(ry, 10) };
  });
  const world = (i: number): Rect => ({ x0: pos[i].x + rects[i].x0, x1: pos[i].x + rects[i].x1, y0: pos[i].y + rects[i].y0, y1: pos[i].y + rects[i].y1 });
  const centreRect: Rect = { x0: cx0 - CENTRE_HALF.w, x1: cx0 + CENTRE_HALF.w, y0: cy0 - CENTRE_HALF.h, y1: cy0 + CENTRE_HALF.h };
  const fixed = [centreRect, ...OBSTACLES];
  // Relax with fixed passes, so the result is deterministic.
  for (let pass = 0; pass < 600; pass++) {
    let moved = false;
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const a = world(i), b = world(j);
        const ox = Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0) + GROUP_GAP, oy = Math.min(a.y1, b.y1) - Math.max(a.y0, b.y0) + GROUP_GAP;
        if (ox <= 0 || oy <= 0) continue;
        moved = true;
        if (ox < oy) {
          const s = (a.x0 + a.x1 < b.x0 + b.x1 ? -1 : 1) * (ox / 2 + 0.01);
          pos[i].x += s; pos[j].x -= s;
        } else {
          const s = (a.y0 + a.y1 < b.y0 + b.y1 ? -1 : 1) * (oy / 2 + 0.01);
          pos[i].y += s; pos[j].y -= s;
        }
      }
      for (const o of fixed) {
        const a = world(i);
        const ox = Math.min(a.x1, o.x1) - Math.max(a.x0, o.x0), oy = Math.min(a.y1, o.y1) - Math.max(a.y0, o.y0);
        if (ox <= 0 || oy <= 0) continue;
        moved = true;
        // Leave by the shortest way that stays on the sky.
        const moves = [
          { dx: o.x1 - a.x0 + 0.01, dy: 0, ok: a.x1 + (o.x1 - a.x0) <= w - EDGE },
          { dx: -(a.x1 - o.x0) - 0.01, dy: 0, ok: a.x0 - (a.x1 - o.x0) >= EDGE },
          { dx: 0, dy: o.y1 - a.y0 + 0.01, ok: a.y1 + (o.y1 - a.y0) <= h - EDGE },
          { dx: 0, dy: -(a.y1 - o.y0) - 0.01, ok: a.y0 - (a.y1 - o.y0) >= EDGE },
        ].filter((m) => m.ok).sort((p, q) => Math.abs(p.dx + p.dy) - Math.abs(q.dx + q.dy));
        if (moves[0]) { pos[i].x += moves[0].dx; pos[i].y += moves[0].dy; }
      }
    }
    for (let i = 0; i < n; i++) {
      pos[i].x = Math.min(w - EDGE - rects[i].x1, Math.max(EDGE - rects[i].x0, pos[i].x));
      pos[i].y = Math.min(h - EDGE - rects[i].y1, Math.max(EDGE - rects[i].y0, pos[i].y));
    }
    if (!moved) break;
  }
  const placed = figs.map((fig, i) => ({ fig, cx: pos[i].x, cy: pos[i].y, rect: world(i), scale }));
  for (let i = 0; i < n; i++) {
    if (fixed.some((o) => rectsOverlap(placed[i].rect, o))) return null;
    for (let j = i + 1; j < n; j++) if (rectsOverlap(placed[i].rect, placed[j].rect)) return null;
  }
  return placed;
}

/** Pack the figures onto the sky. If they do not fit at full size they are shrunk a step at a time until they do. */
export function layoutConstellations(inputs: LayoutInput[], w = SKY_W, h = SKY_H): PlacedFigure[] {
  if (inputs.length === 0) return [];
  let scale = 1;
  for (let step = 0; step < 40; step++, scale *= 0.96) {
    const r = tryLayout(inputs, w, h, scale);
    if (r) return r;
  }
  return tryLayout(inputs, w, h, scale) ?? [];
}

/** Fixed pseudo-random background stars (Park-Miller). Same seed, same sky. */
export function backgroundStars(count: number, seed = 7, w = SKY_W, h = SKY_H) {
  let s = seed;
  const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647;
  return Array.from({ length: count }, () => ({
    x: +(rnd() * w).toFixed(1), y: +(rnd() * h).toFixed(1), r: +(0.3 + rnd() * 0.9).toFixed(2), o: +(0.15 + rnd() * 0.55).toFixed(2),
  }));
}
