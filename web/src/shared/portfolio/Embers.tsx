import { useEffect, useRef } from "react";

const N = 46;
interface P { x: number; y: number; vx: number; vy: number; r: number; life: number; max: number; ph: number }

export function reducedMotion(): boolean {
  return !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
}

/** Drifting embers behind the portfolio. Absent under reduced motion, paused while the tab is hidden. */
export function Embers() {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const cv = ref.current;
    if (!cv || reducedMotion()) return;
    const ctx = cv.getContext("2d");
    if (!ctx) return;
    let w = 0, h = 0, raf = 0;
    const size = () => { w = cv.width = innerWidth; h = cv.height = innerHeight; };
    size();
    const spawn = (p: Partial<P>, anywhere: boolean): P => {
      p.x = Math.random() * w; p.y = anywhere ? Math.random() * h : h + 10;
      p.vx = (Math.random() - 0.5) * 0.35; p.vy = -(0.25 + Math.random() * 0.75);
      p.r = 0.6 + Math.random() * 1.6; p.life = 0; p.max = 260 + Math.random() * 420; p.ph = Math.random() * 6.28;
      return p as P;
    };
    const ps = Array.from({ length: N }, () => spawn({}, true));
    const tick = () => {
      ctx.clearRect(0, 0, w, h);
      const light = document.documentElement.dataset.theme === "light";
      for (const p of ps) {
        p.life++; p.ph += 0.03; p.x += p.vx + Math.sin(p.ph) * 0.25; p.y += p.vy;
        if (p.life > p.max || p.y < -10) spawn(p, false);
        const a = Math.sin((p.life / p.max) * Math.PI) * (light ? 0.35 : 0.85) * Math.min(1, p.y / (h * 0.5) + 0.25);
        ctx.fillStyle = `rgba(${light ? "180,83,26" : "255,150,60"},${a.toFixed(3)})`;
        ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, 6.28); ctx.fill();
      }
      raf = requestAnimationFrame(tick);
    };
    const onVis = () => {
      cancelAnimationFrame(raf);
      if (!document.hidden) raf = requestAnimationFrame(tick);
    };
    document.addEventListener("visibilitychange", onVis);
    addEventListener("resize", size);
    onVis();
    return () => { cancelAnimationFrame(raf); document.removeEventListener("visibilitychange", onVis); removeEventListener("resize", size); };
  }, []);
  return <canvas ref={ref} className="embers" aria-hidden="true" />;
}
