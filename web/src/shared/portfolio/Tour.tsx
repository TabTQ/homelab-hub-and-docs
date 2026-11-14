import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";

export const STEP_MS = 2500;
export const STEPS: { sel: string; title: string; text: string }[] = [
  { sel: ".vh-tabs", title: "Sections", text: "Each tab is a screen. Click a tab, or press Q and E to move left and right." },
  { sel: "[data-tour-main]", title: "Explore", text: "Slots, stars, cards and rows are clickable. Open one to see the detail." },
  { sel: '.vh-tabs a[href$="/summary"]', title: "Summary", text: "Short on time? Summary has everything on one page, with a skill filter." },
  { sel: ".vh-actions", title: "Tools", text: "Switch between the stone and parchment themes here, and replay this guide any time." },
];

interface Geo { spot: { top: number; left: number; width: number; height: number }; card: { top: number; left: number } }

/** The 10 second spotlight tour. Mount it to start it; it calls onClose when it ends, is skipped or Esc is pressed. */
export function Tour({ onClose }: { onClose: () => void }) {
  const [i, setI] = useState(0);
  const [geo, setGeo] = useState<Geo | null>(null);
  const card = useRef<HTMLDivElement>(null);
  const next = useCallback(() => setI((n) => n + 1), []);

  // Find the target, skipping a step whose element is not on screen.
  const target = i < STEPS.length ? document.querySelector(STEPS[i].sel) : null;
  useEffect(() => { if (i >= STEPS.length) onClose(); else if (!target) next(); }, [i, target, onClose, next]);

  useEffect(() => {
    if (i >= STEPS.length) return;
    const t = setTimeout(next, STEP_MS);
    return () => clearTimeout(t);
  }, [i, next]);

  useEffect(() => {
    const k = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowRight") next();
    };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [onClose, next]);

  useLayoutEffect(() => {
    if (!target || !card.current) return;
    const place = () => {
      const r = target.getBoundingClientRect(), pad = 8;
      const top = Math.max(4, r.top - pad), left = Math.max(4, r.left - pad);
      const width = Math.min(innerWidth - left - 4, r.width + pad * 2);
      const height = Math.min(innerHeight - top - 4, Math.min(r.height, innerHeight * 0.6) + pad * 2);
      const cw = card.current!.offsetWidth, ch = card.current!.offsetHeight;
      const below = top + height + 12 + ch < innerHeight;
      setGeo({
        spot: { top, left, width, height },
        card: { top: below ? top + height + 12 : Math.max(8, top + height - ch - 12), left: Math.min(innerWidth - cw - 16, Math.max(16, left + width / 2 - cw / 2)) },
      });
    };
    place();
    addEventListener("resize", place);
    return () => removeEventListener("resize", place);
  }, [target, i]);

  const step = STEPS[Math.min(i, STEPS.length - 1)];
  return (
    <>
      <div className="tour-spot" style={geo?.spot} />
      <div ref={card} className="tour-card" role="dialog" aria-label="Navigation tutorial" aria-live="polite" style={geo?.card ?? { top: -999, left: 0 }}>
        <div className="eyebrow">{Math.min(i + 1, STEPS.length)} of {STEPS.length} · {step.title}</div>
        <p>{step.text}</p>
        <div className="row">
          <button className="btn sm" onClick={next} autoFocus>Next</button>
          <button className="btn sm" onClick={onClose}>Skip</button>
        </div>
        <div className="tour-bar"><i key={i} style={{ "--from": `${(i / STEPS.length) * 100}%`, "--to": `${((i + 1) / STEPS.length) * 100}%` } as React.CSSProperties} /></div>
      </div>
    </>
  );
}
