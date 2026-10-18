import { useEffect, useRef, useState } from "react";
import { acceptHeight, FALLBACK_HEIGHT, frameSrc } from "./frame";
import type { WebappSettings } from "./types";

/**
 * A webapp in its sandbox (D10): `allow-scripts` only, never `allow-same-origin`, so the app has an opaque origin and
 * cannot read this page, its cookies or its APIs. The theme goes in as ?theme= and later as a postMessage.
 */
export function WebappFrame({ base, settings, theme, title, heightOverride, fill }: {
  base: string; settings: Pick<WebappSettings, "height" | "theme">; theme: "light" | "dark"; title: string;
  heightOverride?: number | null; fill?: boolean;
}) {
  const ref = useRef<HTMLIFrameElement>(null);
  const [auto, setAuto] = useState<number | null>(null);
  const passTheme = settings.theme !== false;
  // The src only carries the theme at load time; later changes go by message so the app keeps its state.
  const [src] = useState(() => frameSrc(base, theme, passTheme));

  useEffect(() => {
    const h = (e: MessageEvent) => {
      const px = acceptHeight(e, ref.current?.contentWindow);
      if (px !== null) setAuto(px);
    };
    window.addEventListener("message", h);
    return () => window.removeEventListener("message", h);
  }, []);

  useEffect(() => {
    if (passTheme) ref.current?.contentWindow?.postMessage({ type: "hub:theme", theme }, "*");
  }, [theme, passTheme]);

  const mode = settings.height?.mode ?? "fixed";
  const height = fill ? "100%" : heightOverride ? `${heightOverride}px` : mode === "viewport" ? "calc(100dvh - 140px)"
    : mode === "auto" ? `${auto ?? FALLBACK_HEIGHT}px` : `${settings.height?.px ?? FALLBACK_HEIGHT}px`;
  return <iframe ref={ref} className="dx-frame" src={src} title={title} sandbox="allow-scripts" referrerPolicy="no-referrer"
    style={{ height }} loading="lazy" />;
}
