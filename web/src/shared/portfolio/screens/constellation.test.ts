import { describe, expect, it } from "vitest";
import { assignConstellations, backgroundStars, branchOf, buildFigure, layoutConstellations, rectsOverlap, SKY_H, SKY_W, starRadius } from "./constellation";
import { CONSTELLATIONS, CONSTELLATION_KEYS, getConstellation } from "./constellations";

const SIZES = [13, 7, 14, 8, 6, 9, 4, 5, 3, 5, 2];
const ns = (count: number) => Array.from({ length: count }, (_, i) => (i * 3) % 6);
const inputs = (keys: string[], sizes = SIZES) => keys.map((key, i) => ({ key, ns: ns(sizes[i]), name: "Data Engineering" }));

describe("assignment", () => {
  const keys = assignConstellations(SIZES.map((count) => ({ count })));
  it("gives the 11 real groups 11 different constellations, deterministically", () => {
    expect(keys).toHaveLength(11);
    expect(new Set(keys).size).toBe(11);
    expect(assignConstellations(SIZES.map((count) => ({ count })))).toEqual(keys);
    keys.forEach((k) => expect(CONSTELLATION_KEYS).toContain(k));
  });
  it("matches big groups to big figures and small groups to small ones", () => {
    const stars = keys.map((k) => getConstellation(k)!.stars.length);
    expect(stars[2]).toBeGreaterThanOrEqual(14); // 14 skills
    expect(stars[0]).toBeGreaterThanOrEqual(13); // 13 skills
    SIZES.forEach((count, i) => expect(stars[i]).toBeGreaterThanOrEqual(Math.min(count, 5)));
    expect(stars[2]).toBeGreaterThan(stars[10]);
  });
  it("honours a known constellation key and keeps it away from automatic picks", () => {
    const k = assignConstellations([{ count: 14 }, { count: 6, constellation: "scorpius" }, { count: 5, constellation: "bogus" }]);
    expect(k[1]).toBe("scorpius");
    expect(k[0]).not.toBe("scorpius");
    expect(CONSTELLATION_KEYS).toContain(k[2]);
  });
  it("reuses entries when the catalogue runs out", () => {
    const many = assignConstellations(Array.from({ length: CONSTELLATIONS.length + 3 }, () => ({ count: 6 })));
    expect(many).toHaveLength(CONSTELLATIONS.length + 3);
    expect(new Set(many).size).toBe(CONSTELLATIONS.length);
  });
  it("gives equal groups different constellations", () => {
    const k = assignConstellations([{ count: 5 }, { count: 5 }]);
    expect(k[0]).not.toBe(k[1]);
  });
});

describe("figure", () => {
  it("puts the most linked skills on the most important stars and leaves field stars", () => {
    const c = getConstellation("cassiopeia")!;
    const f = buildFigure("cassiopeia", [1, 5, 3]);
    expect(f.spots.map((s) => s.star)).toEqual([c.primary[2], c.primary[0], c.primary[1]]);
    expect(f.starSkill.filter((s) => s === -1)).toHaveLength(c.stars.length - 3);
    expect(f.spots.every((s) => s.anchor === -1)).toBe(true);
  });
  it("breaks ties by data order", () => {
    const c = getConstellation("cassiopeia")!;
    const f = buildFigure("cassiopeia", [2, 2]);
    expect(f.spots.map((s) => s.star)).toEqual([c.primary[0], c.primary[1]]);
  });
  it("places every skill when a group has more skills than stars, as satellites joined to a star", () => {
    const f = buildFigure("crux", ns(20));
    expect(f.spots).toHaveLength(20);
    const sats = f.spots.filter((s) => s.star === -1);
    expect(sats).toHaveLength(15);
    for (const s of sats) {
      expect(s.anchor).toBeGreaterThanOrEqual(0);
      expect(s.anchor).toBeLessThan(5);
      expect(Number.isFinite(s.x) && Number.isFinite(s.y)).toBe(true);
    }
    const spots = new Set(f.spots.map((s) => `${s.x.toFixed(1)},${s.y.toFixed(1)}`));
    expect(spots.size).toBe(20);
    expect(f.bounds.maxX).toBeGreaterThanOrEqual(Math.max(...sats.map((s) => s.x)));
  });
  it("is deterministic and centred on the centroid", () => {
    expect(buildFigure("leo", ns(12))).toEqual(buildFigure("leo", ns(12)));
    const f = buildFigure("orion", ns(5));
    expect(f.stars.reduce((a, s) => a + s.x, 0) / f.stars.length).toBeCloseTo(0, 6);
    expect(f.stars.reduce((a, s) => a + s.y, 0) / f.stars.length).toBeCloseTo(0, 6);
  });
  it("handles an empty group", () => {
    const f = buildFigure("lyra", []);
    expect(f.spots).toEqual([]);
    expect(f.starSkill.every((s) => s === -1)).toBe(true);
  });
});

describe("sky layout", () => {
  const keys = assignConstellations(SIZES.map((count) => ({ count })));
  it("never overlaps two figures and stays inside the sky, for the real sizes", () => {
    const g = layoutConstellations(inputs(keys));
    expect(g).toHaveLength(11);
    for (let i = 0; i < g.length; i++) {
      expect(g[i].rect.x0).toBeGreaterThanOrEqual(0);
      expect(g[i].rect.x1).toBeLessThanOrEqual(SKY_W);
      expect(g[i].rect.y0).toBeGreaterThanOrEqual(0);
      expect(g[i].rect.y1).toBeLessThanOrEqual(SKY_H);
      for (let j = i + 1; j < g.length; j++) expect(rectsOverlap(g[i].rect, g[j].rect)).toBe(false);
    }
  });
  it("keeps every star, satellite and label inside its group rectangle", () => {
    for (const p of layoutConstellations(inputs(keys))) {
      for (const s of [...p.fig.stars, ...p.fig.spots]) {
        expect(p.cx + s.x).toBeGreaterThanOrEqual(p.rect.x0);
        expect(p.cx + s.x).toBeLessThanOrEqual(p.rect.x1);
        expect(p.cy + s.y).toBeGreaterThanOrEqual(p.rect.y0);
        expect(p.cy + s.y).toBeLessThanOrEqual(p.rect.y1);
      }
      expect(p.cy + 40).toBeLessThanOrEqual(p.rect.y1);
    }
  });
  it("keeps clear of the centre rings", () => {
    for (const p of layoutConstellations(inputs(keys))) {
      expect(rectsOverlap(p.rect, { x0: SKY_W / 2 - 40, x1: SKY_W / 2 + 40, y0: SKY_H / 2 - 40, y1: SKY_H / 2 + 40 })).toBe(false);
    }
  });
  it("still fits when groups are big or many", () => {
    const sizes = [20, 18, 16, 15, 14, 14, 12, 12, 10, 9, 8, 8, 7];
    const ks = assignConstellations(sizes.map((count) => ({ count })));
    const g = layoutConstellations(inputs(ks, sizes), SKY_W, SKY_H);
    expect(g).toHaveLength(sizes.length);
    for (let i = 0; i < g.length; i++) for (let j = i + 1; j < g.length; j++) expect(rectsOverlap(g[i].rect, g[j].rect)).toBe(false);
  });
  it("is deterministic and handles tiny input", () => {
    expect(layoutConstellations(inputs(keys))).toEqual(layoutConstellations(inputs(keys)));
    expect(layoutConstellations([])).toEqual([]);
    expect(layoutConstellations([{ key: "lyra", ns: [] }, { key: "crux", ns: [1] }])).toHaveLength(2);
  });
});

describe("branches, stars and background", () => {
  it("assigns branches in thirds and caps star size", () => {
    expect(Array.from({ length: 11 }, (_, i) => branchOf(i, 11))).toEqual(["raven", "raven", "raven", "raven", "bear", "bear", "bear", "bear", "wolf", "wolf", "wolf"]);
    expect(starRadius(100)).toBeLessThanOrEqual(10);
    expect(starRadius(0)).toBeLessThan(starRadius(1));
  });
  it("makes the same background sky every time", () => {
    expect(backgroundStars(50)).toEqual(backgroundStars(50));
    expect(backgroundStars(50, 8)).not.toEqual(backgroundStars(50));
  });
});
