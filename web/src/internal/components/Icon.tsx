import { useState } from "react";
import { initials } from "../../shared/util";

export type IconSource = { kind: "img"; src: string } | { kind: "mask"; src: string } | { kind: "none" };

/**
 * Where an icon comes from. Local paths (/images/...) and https URLs are used as they are. Names in the gethomepage style
 * (proxmox.svg, grafana.png, guacamole, mdi-home) are served from /images/icons/, which tools/fetch-icons downloads from the
 * dashboard-icons and Material Design Icons repositories once, so the page never calls a third-party CDN.
 * mdi icons are single-colour, so they are drawn as a mask and follow the text colour in both themes.
 */
export function iconSource(icon: string | null): IconSource {
  if (!icon) return { kind: "none" };
  if (icon.startsWith("/") || icon.startsWith("https://")) return { kind: "img", src: icon };
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$/.test(icon)) return { kind: "none" };
  if (icon.startsWith("mdi-")) return { kind: "mask", src: `/images/icons/${icon}.svg` };
  return { kind: "img", src: `/images/icons/${/\.(svg|png|webp)$/.test(icon) ? icon : icon + ".png"}` };
}

/** A picture that fails to load (icon not downloaded yet) falls back to the letter badge. */
export function Icon({ icon, name }: { icon: string | null; name: string }) {
  const [broken, setBroken] = useState(false);
  const s = broken ? { kind: "none" as const } : iconSource(icon);
  const photo = !!icon && (icon.startsWith("/images/") && !icon.startsWith("/images/icons/") || icon.startsWith("https://"));
  return (
    <span className={"ico" + (s.kind === "img" && !photo ? " glyph" : "")} aria-hidden="true">
      {s.kind === "img" && <img src={s.src} alt="" loading="lazy" onError={() => setBroken(true)} />}
      {s.kind === "mask" && <MaskIcon src={s.src} onError={() => setBroken(true)} />}
      {s.kind === "none" && initials(name)}
    </span>
  );
}

/** A mask cannot report a load failure, so probe the file with an Image first. */
function MaskIcon({ src, onError }: { src: string; onError: () => void }) {
  return (
    <>
      <img src={src} alt="" width={0} height={0} style={{ position: "absolute", opacity: 0 }} onError={onError} />
      <span className="mdi" style={{ maskImage: `url(${src})`, WebkitMaskImage: `url(${src})` }} />
    </>
  );
}
