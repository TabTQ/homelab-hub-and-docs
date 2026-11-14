import { describe, expect, it } from "vitest";
import { backgroundStars, branchOf, layoutConstellations, SKY_H, SKY_W, starRadius } from "./constellation";

const SIZES = [13, 7, 14, 8, 6, 9, 4, 5, 3, 5, 2];

describe("constellation layout", () => {
  it("never overlaps two group circles and stays inside the sky", () => {
    const g = layoutConstellations(SIZES);
    expect(g).toHaveLength(11);
    for (let i = 0; i < g.length; i++) {
      expect(g[i].cx - g[i].radius).toBeGreaterThanOrEqual(0);
      expect(g[i].cx + g[i].radius).toBeLessThanOrEqual(SKY_W);
      expect(g[i].cy - g[i].radius).toBeGreaterThanOrEqual(0);
      expect(g[i].cy + g[i].radius).toBeLessThanOrEqual(SKY_H);
      for (let j = i + 1; j < g.length; j++) expect(Math.hypot(g[i].cx - g[j].cx, g[i].cy - g[j].cy)).toBeGreaterThan(g[i].radius + g[j].radius);
    }
  });
  it("keeps every star inside its group circle and grows the radius with size", () => {
    const g = layoutConstellations(SIZES);
    g.forEach((grp, i) => {
      expect(grp.stars).toHaveLength(SIZES[i]);
      expect(grp.order.slice().sort((a, b) => a - b)).toEqual(grp.stars.map((_, k) => k));
      for (const s of grp.stars) expect(Math.hypot(s.x - grp.cx, s.y - grp.cy)).toBeLessThan(grp.radius);
    });
    expect(g[2].radius).toBeGreaterThan(g[10].radius);
  });
  it("puts the label above upper clusters and below lower ones", () => {
    for (const g of layoutConstellations(SIZES)) {
      if (g.upper) expect(g.labelY).toBeLessThan(g.cy - g.radius + 1); else expect(g.labelY).toBeGreaterThan(g.cy + g.radius);
    }
  });
  it("is deterministic and handles tiny input", () => {
    expect(layoutConstellations(SIZES)).toEqual(layoutConstellations(SIZES));
    expect(layoutConstellations([])).toEqual([]);
    expect(layoutConstellations([0, 1])).toHaveLength(2);
  });
  it("assigns branches in thirds and caps star size", () => {
    expect(Array.from({ length: 11 }, (_, i) => branchOf(i, 11))).toEqual(["raven", "raven", "raven", "raven", "bear", "bear", "bear", "bear", "wolf", "wolf", "wolf"]);
    expect(starRadius(100)).toBe(9);
    expect(starRadius(0)).toBeLessThan(starRadius(1));
  });
  it("makes the same background sky every time", () => {
    expect(backgroundStars(50)).toEqual(backgroundStars(50));
    expect(backgroundStars(50, 8)).not.toEqual(backgroundStars(50));
  });
});
