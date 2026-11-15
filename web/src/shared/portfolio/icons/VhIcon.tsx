import { ICONS } from "./registry";

interface Props { name: string; size?: number; className?: string }

/** Inline icon in the shared stroke style. Uses currentColor, so it takes the text colour. */
export function VhIcon({ name, size = 24, className }: Props) {
  const def = ICONS[name] ?? ICONS.diamond;
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {def.r ? def.r.map((a) => <path key={a} d={def.d} transform={a ? `rotate(${a} 12 12)` : undefined} />) : <path d={def.d} />}
      {def.f && <path d={def.f} strokeWidth={3} />}
    </svg>
  );
}
