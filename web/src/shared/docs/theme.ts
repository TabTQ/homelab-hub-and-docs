import { useEffect, useState } from "react";

/** The theme actually shown (the hub's choice can be "system"), for webapp frames and dark diagrams. */
export function currentTheme(): "light" | "dark" {
  const forced = document.documentElement.dataset.theme;
  if (forced === "dark" || forced === "light") return forced;
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

export function useCurrentTheme(): "light" | "dark" {
  const [t, setT] = useState(currentTheme);
  useEffect(() => {
    const update = () => setT(currentTheme());
    const mo = new MutationObserver(update);
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    const mq = window.matchMedia?.("(prefers-color-scheme: dark)");
    mq?.addEventListener?.("change", update);
    return () => { mo.disconnect(); mq?.removeEventListener?.("change", update); };
  }, []);
  return t;
}
