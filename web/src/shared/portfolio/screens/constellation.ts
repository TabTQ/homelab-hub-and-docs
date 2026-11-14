// Pure layout maths for the skills sky. No DOM, no randomness: the same input always gives the same output.

export const SKY_W = 1600;
export const SKY_H = 860;
export const CENTRE = { x: SKY_W / 2, y: SKY_H / 2 };
const GOLDEN = 2.399963;
const STAR_PAD = 16; // room around the outermost star (star radius plus glow)
const GROUP_GAP = 26;
const LABEL_ROOM = 30;

export interface PlacedStar { x: number; y: number; angle: number }
export interface PlacedGroup {
  cx: number; cy: number; radius: number; upper: boolean;
  stars: PlacedStar[];
  /** Star indexes in angular order around the group centre: the order the constellation line joins them. */
  order: number[];
  labelX: number; labelY: number;
}

export type Branch = "raven" | "bear" | "wolf";
const BRANCHES: Branch[] = ["raven", "bear", "wolf"];

/** Spread groups over the three branches in thirds, so ring neighbours share a colour. */
export function branchOf(index: number, total: number): Branch {
  if (total <= 0) return "raven";
  return BRANCHES[Math.min(2, Math.floor((index * 3) / total))];
}

/** Star radius from the linked count, capped. Hollow stars (n = 0) get a small fixed size. */
export function starRadius(n: number): number {
  return n > 0 ? Math.min(2.6 + n * 1.15, 9) : 3.2;
}

/** Stars around one group centre on a sunflower spiral, rotated by `turn`. */
export function placeStars(count: number, turn: number): PlacedStar[] {
  return Array.from({ length: count }, (_, i) => {
    const angle = turn + i * GOLDEN;
    const r = 30 + 19 * Math.sqrt(i);
    return { x: Math.cos(angle) * r, y: Math.sin(angle) * r, angle: Math.atan2(Math.sin(angle), Math.cos(angle)) };
  });
}

function groupRadius(count: number): number {
  if (count === 0) return 30;
  const far = Math.max(...placeStars(count, 0).map((s) => Math.hypot(s.x, s.y)));
  return far + STAR_PAD;
}

/** Group centres on an ellipse, spaced by size, then nudged apart until no two circles touch. */
export function layoutConstellations(sizes: number[], w = SKY_W, h = SKY_H): PlacedGroup[] {
  const n = sizes.length;
  const radii = sizes.map(groupRadius);
  const cx0 = w / 2, cy0 = h / 2;
  const rx = w / 2 - Math.max(0, ...radii) - 70, ry = h / 2 - Math.max(0, ...radii) - LABEL_ROOM - 24;
  const weights = radii.map((r) => r + GROUP_GAP / 2);
  const total = weights.reduce((a, b) => a + b, 0);
  const start = -Math.PI / 2 + 0.28;
  let acc = 0;
  const pos = radii.map((_, i) => {
    const th = start + ((acc + weights[i] / 2) / total) * Math.PI * 2;
    acc += weights[i];
    return { x: cx0 + Math.cos(th) * rx, y: cy0 + Math.sin(th) * ry, th };
  });
  // Relax: push overlapping circles apart, keep inside the sky. Fixed passes, so it is deterministic.
  for (let pass = 0; pass < 300; pass++) {
    let moved = false;
    for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) {
      const dx = pos[j].x - pos[i].x, dy = pos[j].y - pos[i].y;
      const d = Math.hypot(dx, dy) || 0.01, need = radii[i] + radii[j] + GROUP_GAP;
      if (d < need) {
        const push = (need - d) / 2 + 0.01, ux = dx / d, uy = dy / d;
        pos[i].x -= ux * push; pos[i].y -= uy * push; pos[j].x += ux * push; pos[j].y += uy * push; moved = true;
      }
    }
    for (let i = 0; i < n; i++) {
      pos[i].x = Math.min(w - radii[i] - 8, Math.max(radii[i] + 8, pos[i].x));
      pos[i].y = Math.min(h - radii[i] - LABEL_ROOM, Math.max(radii[i] + LABEL_ROOM, pos[i].y));
    }
    if (!moved) break;
  }
  return pos.map((p, i) => {
    const stars = placeStars(sizes[i], p.th + 0.9);
    const order = stars.map((_, k) => k).sort((a, b) => stars[a].angle - stars[b].angle);
    const upper = p.y < cy0;
    return {
      cx: p.x, cy: p.y, radius: radii[i], upper, stars: stars.map((s) => ({ ...s, x: p.x + s.x, y: p.y + s.y })), order,
      labelX: Math.min(w - 120, Math.max(120, p.x)), labelY: upper ? p.y - radii[i] - 8 : p.y + radii[i] + 18,
    };
  });
}

/** Fixed pseudo-random background stars (Park-Miller). Same seed, same sky. */
export function backgroundStars(count: number, seed = 7, w = SKY_W, h = SKY_H) {
  let s = seed;
  const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647;
  return Array.from({ length: count }, () => ({
    x: +(rnd() * w).toFixed(1), y: +(rnd() * h).toFixed(1), r: +(0.3 + rnd() * 0.9).toFixed(2), o: +(0.15 + rnd() * 0.55).toFixed(2),
  }));
}
