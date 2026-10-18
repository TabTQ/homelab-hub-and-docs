import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "../shared/fonts";
import "../shared/styles.css";
import { ToastProvider } from "../shared/components/Toast";
import { App } from "./App";

createRoot(document.getElementById("root")!).render(<StrictMode><ToastProvider><App /></ToastProvider></StrictMode>);

// Installable app: only where the browser allows service workers (https, or localhost). Over plain http on the LAN the site still works.
if (import.meta.env.PROD && "serviceWorker" in navigator && window.isSecureContext) {
  window.addEventListener("load", () => { navigator.serviceWorker.register("/sw.js").catch(() => { /* not installable here; the site works anyway */ }); });
}
