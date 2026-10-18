import { useEffect } from "react";

/**
 * A dialog: Escape and a click outside close it, the body scrolls inside short windows and the button row stays
 * sticky at the bottom. Lives here (not in ConfigPage) so pages that use it do not pull in CodeMirror.
 */
export function Modal({ title, children, actions, onClose, wide, className }: {
  title: string; children: React.ReactNode; actions: React.ReactNode; onClose: () => void; wide?: boolean; className?: string;
}) {
  useEffect(() => { const h = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); }; window.addEventListener("keydown", h); return () => window.removeEventListener("keydown", h); }, [onClose]);
  return (
    <div className={className ? `overlay ${className}` : "overlay"} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="dialog" style={wide ? { width: "min(900px, 100%)" } : undefined} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal"><h2>{title}</h2>{children}<div className="mrow">{actions}</div></div>
      </div>
    </div>
  );
}
