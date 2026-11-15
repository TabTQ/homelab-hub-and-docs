// Zoom and pan maths for the skills sky. Pure: no DOM. A view is the rectangle of the sky that the SVG viewBox shows.
// Its aspect ratio always matches the sky, so zoom is simply sky width divided by view width.

export interface Size { w: number; h: number }
export interface Box { x: number; y: number; w: number; h: number }
export type View = Box;

export const MIN_ZOOM = 1;
export const MAX_ZOOM = 6;

export const fullView = (world: Size): View => ({ x: 0, y: 0, w: world.w, h: world.h });
export const zoomOf = (v: View, world: Size): number => world.w / v.w;

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, n));

/** Keep the view inside the sky, so panning can never lose the content. Size is clamped to the zoom limits. */
export function clampView(v: View, world: Size, maxZoom = MAX_ZOOM): View {
  const w = clamp(v.w, world.w / maxZoom, world.w);
  const h = (w * world.h) / world.w;
  const cx = v.x + v.w / 2, cy = v.y + v.h / 2;
  return { x: clamp(cx - w / 2, 0, world.w - w), y: clamp(cy - h / 2, 0, world.h - h), w, h };
}

/** Zoom by `factor` (above 1 zooms in) keeping the sky point (px, py) at the same place on screen. */
export function zoomAt(v: View, world: Size, factor: number, px: number, py: number, maxZoom = MAX_ZOOM): View {
  const w = clamp(v.w / factor, world.w / maxZoom, world.w);
  const f = v.w / w;
  const h = (w * world.h) / world.w;
  const x = px - (px - v.x) / f, y = py - (py - v.y) / f;
  return { x: clamp(x, 0, world.w - w), y: clamp(y, 0, world.h - h), w, h };
}

/** Move the view by a distance in sky units. */
export function panBy(v: View, world: Size, dx: number, dy: number): View {
  return { ...v, x: clamp(v.x + dx, 0, world.w - v.w), y: clamp(v.y + dy, 0, world.h - v.h) };
}

/** The view that frames `box`, leaving a margin: the box fills `fill` of the view on its tighter axis. */
export function fitBox(world: Size, box: Box, fill = 0.88, maxZoom = MAX_ZOOM): View {
  const aspect = world.w / world.h;
  const w = Math.max(box.w / fill, (box.h / fill) * aspect);
  return clampView({ x: box.x + box.w / 2 - w / 2, y: box.y + box.h / 2 - w / aspect / 2, w, h: w / aspect }, world, maxZoom);
}

export function lerpView(a: View, b: View, t: number): View {
  return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t, w: a.w + (b.w - a.w) * t, h: a.h + (b.h - a.h) * t };
}

export const easeInOut = (t: number): number => (t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2);

/** Zoom factor for a wheel event. Pinch on a trackpad arrives as a wheel with ctrlKey and small deltas, so it is stronger. */
export function wheelFactor(deltaY: number, deltaMode = 0, ctrlKey = false): number {
  const px = deltaMode === 1 ? deltaY * 16 : deltaMode === 2 ? deltaY * 400 : deltaY;
  const capped = clamp(px, -240, 240);
  return Math.exp(-capped * (ctrlKey ? 0.01 : 0.0018));
}

export interface ClientRect { left: number; top: number; width: number; height: number }

/** Screen pixels per sky unit when the view is drawn "meet" inside the element. */
export function screenScale(v: View, rect: ClientRect): number {
  return Math.min(rect.width / v.w, rect.height / v.h) || 1;
}

/** Convert a client (pointer) position to a sky position. */
export function clientToWorld(v: View, rect: ClientRect, clientX: number, clientY: number): { x: number; y: number } {
  const s = screenScale(v, rect);
  const offX = (rect.width - v.w * s) / 2, offY = (rect.height - v.h * s) / 2;
  return { x: v.x + (clientX - rect.left - offX) / s, y: v.y + (clientY - rect.top - offY) / s };
}

export const viewsEqual = (a: View, b: View, eps = 0.01): boolean =>
  Math.abs(a.x - b.x) < eps && Math.abs(a.y - b.y) < eps && Math.abs(a.w - b.w) < eps && Math.abs(a.h - b.h) < eps;
