import { describe, expect, it } from "vitest";
import { CONSTELLATIONS, CONSTELLATION_KEYS, constellationLabel, getConstellation } from "./constellations";

describe("constellation catalogue", () => {
  it("has at least 16 well known figures with unique keys and names", () => {
    expect(CONSTELLATIONS.length).toBeGreaterThanOrEqual(16);
    expect(new Set(CONSTELLATION_KEYS).size).toBe(CONSTELLATIONS.length);
    expect(new Set(CONSTELLATIONS.map((c) => c.name)).size).toBe(CONSTELLATIONS.length);
    for (const k of ["orion", "ursa-major", "ursa-minor", "cassiopeia", "cygnus", "lyra", "scorpius", "leo", "gemini", "taurus", "pegasus", "andromeda", "draco", "aquila", "canis-major", "crux"]) {
      expect(CONSTELLATION_KEYS).toContain(k);
    }
  });
  it("spans roughly 5 to 16 stars", () => {
    const sizes = CONSTELLATIONS.map((c) => c.stars.length);
    expect(Math.min(...sizes)).toBeLessThanOrEqual(5);
    expect(Math.max(...sizes)).toBeGreaterThanOrEqual(15);
    expect(Math.max(...sizes)).toBeLessThanOrEqual(16);
    expect(Math.min(...sizes)).toBeGreaterThanOrEqual(4);
  });
  it.each(CONSTELLATIONS.map((c) => [c.key, c] as const))("%s is well formed", (_k, c) => {
    const n = c.stars.length;
    for (const [x, y] of c.stars) {
      expect(x).toBeGreaterThanOrEqual(0); expect(x).toBeLessThanOrEqual(1);
      expect(y).toBeGreaterThanOrEqual(0); expect(y).toBeLessThanOrEqual(1);
    }
    expect(Math.max(...c.stars.flat())).toBe(1); // the longer side spans the box
    expect(c.segments.length).toBeGreaterThan(0);
    for (const [a, b] of c.segments) {
      expect(Number.isInteger(a) && a >= 0 && a < n).toBe(true);
      expect(Number.isInteger(b) && b >= 0 && b < n).toBe(true);
      expect(a).not.toBe(b);
    }
    expect(new Set(c.segments.map(([a, b]) => `${Math.min(a, b)}-${Math.max(a, b)}`)).size).toBe(c.segments.length);
    expect(c.primary.slice().sort((a, b) => a - b)).toEqual(c.stars.map((_, i) => i));
    // every star is on a line, except none: keep figures connected enough to read
    const touched = new Set(c.segments.flat());
    expect(touched.size).toBeGreaterThanOrEqual(n - 1);
  });
  it("looks up and labels keys", () => {
    expect(getConstellation("orion")?.name).toBe("Orion");
    expect(constellationLabel("ursa-major")).toBe("Ursa Major");
    expect(constellationLabel("nope")).toBe("nope");
    expect(getConstellation("nope")).toBeUndefined();
  });
  it("keeps the recognisable geometry of a few figures", () => {
    const orion = getConstellation("orion")!;
    // belt: three stars in a row, shoulders above, feet below
    const [bet, bel, , a, b, c, saiph, rigel] = orion.stars;
    expect(bet[1]).toBeLessThan(a[1]); expect(bel[1]).toBeLessThan(c[1]);
    expect(saiph[1]).toBeGreaterThan(a[1]); expect(rigel[1]).toBeGreaterThan(c[1]);
    expect(a[0]).toBeLessThan(b[0]); expect(b[0]).toBeLessThan(c[0]);
    const cas = getConstellation("cassiopeia")!;
    const ys = cas.stars.map((s) => s[1]);
    expect(ys[0] < ys[1] && ys[1] > ys[2] && ys[2] < ys[3] && ys[3] > ys[4]).toBe(true); // the W
    const sco = getConstellation("scorpius")!;
    expect(sco.stars.length).toBe(16);
  });
});
