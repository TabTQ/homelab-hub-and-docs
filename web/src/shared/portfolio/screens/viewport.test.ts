import { describe, expect, it } from "vitest";
import { clampView, clientToWorld, easeInOut, fitBox, fullView, lerpView, panBy, screenScale, wheelFactor, zoomAt, zoomOf, MAX_ZOOM } from "./viewport";

const W = { w: 1800, h: 968 };

describe("viewport maths", () => {
  it("starts at zoom 1 over the whole sky", () => {
    expect(fullView(W)).toEqual({ x: 0, y: 0, w: 1800, h: 968 });
    expect(zoomOf(fullView(W), W)).toBe(1);
  });
  it("zooming about a point keeps that point fixed on screen", () => {
    const v = { x: 300, y: 100, w: 900, h: 484 };
    const px = 700, py = 300;
    const z = zoomAt(v, W, 1.6, px, py);
    expect((px - z.x) / z.w).toBeCloseTo((px - v.x) / v.w, 6);
    expect((py - z.y) / z.h).toBeCloseTo((py - v.y) / v.h, 6);
    expect(zoomOf(z, W)).toBeCloseTo(2 * 1.6, 6);
    expect(z.w / z.h).toBeCloseTo(W.w / W.h, 6);
  });
  it("clamps zoom to 1x and 6x", () => {
    expect(zoomAt(fullView(W), W, 0.2, 900, 480)).toEqual(fullView(W));
    let v = fullView(W);
    for (let i = 0; i < 40; i++) v = zoomAt(v, W, 1.5, 900, 480);
    expect(zoomOf(v, W)).toBeCloseTo(MAX_ZOOM, 6);
  });
  it("keeps the view inside the sky when zooming near an edge or panning", () => {
    const z = zoomAt(fullView(W), W, 4, 5, 5);
    expect(z.x).toBeGreaterThanOrEqual(0);
    expect(z.y).toBeGreaterThanOrEqual(0);
    const p = panBy(z, W, -9999, 9999);
    expect(p.x).toBe(0);
    expect(p.y + p.h).toBeCloseTo(W.h, 6);
    const q = panBy(p, W, 99999, 0);
    expect(q.x + q.w).toBeCloseTo(W.w, 6);
    expect(clampView({ x: -50, y: -50, w: 100000, h: 1 }, W)).toEqual(fullView(W));
  });
  it("cannot pan at zoom 1", () => {
    expect(panBy(fullView(W), W, 300, 300)).toEqual(fullView(W));
  });
  it("fits a box with a margin and keeps the sky aspect", () => {
    const box = { x: 800, y: 300, w: 300, h: 300 };
    const v = fitBox(W, box);
    expect(v.w / v.h).toBeCloseTo(W.w / W.h, 6);
    expect(v.x).toBeLessThanOrEqual(box.x);
    expect(v.x + v.w).toBeGreaterThanOrEqual(box.x + box.w);
    expect(v.y).toBeLessThanOrEqual(box.y);
    expect(v.y + v.h).toBeGreaterThanOrEqual(box.y + box.h);
    expect(zoomOf(v, W)).toBeGreaterThan(1.5);
  });
  it("fits a box near the corner without leaving the sky, and a huge box gives the full view", () => {
    const v = fitBox(W, { x: 0, y: 0, w: 260, h: 200 });
    expect(v.x).toBe(0);
    expect(v.y).toBe(0);
    expect(fitBox(W, { x: 0, y: 0, w: 5000, h: 5000 })).toEqual(fullView(W));
  });
  it("turns wheel input into a zoom factor, pinch stronger than scroll", () => {
    expect(wheelFactor(-100)).toBeGreaterThan(1);
    expect(wheelFactor(100)).toBeLessThan(1);
    expect(wheelFactor(0)).toBe(1);
    expect(wheelFactor(-5, 0, true)).toBeGreaterThan(wheelFactor(-5, 0, false));
    expect(wheelFactor(-3, 1)).toBeCloseTo(wheelFactor(-48, 0), 9);
    expect(wheelFactor(-1e6)).toBeLessThan(5);
  });
  it("maps pointer positions to sky positions, with letterboxing", () => {
    const v = { x: 100, y: 50, w: 900, h: 484 };
    const wide = { left: 10, top: 20, width: 900 * 2, height: 484 * 2 };
    expect(screenScale(v, wide)).toBe(2);
    expect(clientToWorld(v, wide, 10, 20)).toEqual({ x: 100, y: 50 });
    expect(clientToWorld(v, wide, 10 + 900, 20 + 484)).toEqual({ x: 550, y: 292 });
    const tall = { left: 0, top: 0, width: 900, height: 484 + 200 };
    expect(clientToWorld(v, tall, 0, 100).y).toBeCloseTo(50, 6);
  });
  it("eases and interpolates", () => {
    expect(easeInOut(0)).toBe(0);
    expect(easeInOut(1)).toBe(1);
    expect(easeInOut(0.5)).toBe(0.5);
    const m = lerpView(fullView(W), { x: 100, y: 100, w: 900, h: 484 }, 0.5);
    expect(m).toEqual({ x: 50, y: 50, w: 1350, h: 726 });
  });
});
