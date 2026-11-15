import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../shared/fonts";
import "../shared/styles.css";
import { ToastProvider } from "../shared/components/Toast";
import { PortfolioShell } from "../shared/portfolio/PortfolioShell";
import { applyTheme, loadTheme } from "../shared/theme";
import { staticDataFile } from "../shared/docs/static";
import type { Portfolio, SnapshotEnvelope } from "../shared/types";

function App() {
  const [data, setData] = useState<Portfolio | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Apply the stored theme right away, so the loading and error screens are themed too. The shell takes over after that.
  useEffect(() => applyTheme(loadTheme()), []);

  useEffect(() => {
    let cancelled = false;
    // Static copy (GitHub Pages): the shell names a JSON file next to the page. Otherwise the API, as before.
    let file: string | null = null;
    try { file = staticDataFile(); } catch (e) { setError((e as Error).message); return; }
    fetch(file ?? "/api/public/portfolio", { headers: { Accept: "application/json" } })
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

  return <PortfolioShell data={data} />;
}

createRoot(document.getElementById("root")!).render(<StrictMode><ToastProvider><App /></ToastProvider></StrictMode>);
