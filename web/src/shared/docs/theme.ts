import { useEffect, useState } from "react";

/**
 * The theme actually shown, for webapp frames and diagrams. Stone (dark) is the page with no data-theme attribute,
 * Parchment (light) is data-theme="light". The shared theme code already resolves "system" into one of those two,
 * so this never needs matchMedia.
 */
export function currentTheme(): "light" | "dark" {
  return document.documentElement.dataset.theme === "light" ? "light" : "dark";
}

export function useCurrentTheme(): "light" | "dark" {
  const [t, setT] = useState(currentTheme);
  useEffect(() => {
    const update = () => setT(currentTheme());
    update();
    const mo = new MutationObserver(update);
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    return () => mo.disconnect();
  }, []);
  return t;
}
