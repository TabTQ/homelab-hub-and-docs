import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../shared/fonts";
import "../shared/styles.css";
import { PortfolioPage } from "../shared/components/PortfolioPage";
import { openPalette } from "../shared/components/Palette";
import { ToastProvider } from "../shared/components/Toast";
import { applyTheme, loadTheme, nextTheme, type ThemeChoice } from "../shared/theme";
import type { Portfolio, SnapshotEnvelope } from "../shared/types";

function App() {
  const [data, setData] = useState<Portfolio | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [theme, setTheme] = useState<ThemeChoice>(loadTheme);
  useEffect(() => applyTheme(theme), [theme]);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/public/portfolio", { headers: { Accept: "application/json" } })
      .then(async (r) => {
        if (r.status === 503) throw new Error("The portfolio is not published yet. Please check back soon.");
        if (!r.ok) throw new Error(`The portfolio could not be loaded (HTTP ${r.status}).`);
        return (await r.json()) as SnapshotEnvelope;
      })
      .then((env) => { if (!cancelled) setData(env.data); })
      .catch((e: Error) => { if (!cancelled) setError(e.message); });
    return () => { cancelled = true; };
  }, []);

  if (error) return <div className="wrap"><div className="login" role="alert"><h2>Portfolio unavailable</h2><p className="sub">{error}</p></div></div>;
  if (!data) return <div className="wrap"><p className="sub" style={{ marginTop: 40 }}>Loading…</p></div>;

  const header = (
    <div className="wrap">
      <header className="top">
        <div className="brand"><i aria-hidden="true" />{data.profile.name}</div>
        <span className="grow" />
        <button className="btn" onClick={() => setTheme(nextTheme(theme))} aria-label="Change theme">Theme: {theme}</button>
        <button className="kbd-search" onClick={openPalette} aria-label="Search portfolio"><span>Search portfolio</span><kbd>Ctrl K</kbd></button>
      </header>
    </div>
  );
  return <PortfolioPage data={data} header={header} />;
}

createRoot(document.getElementById("root")!).render(<StrictMode><ToastProvider><App /></ToastProvider></StrictMode>);
