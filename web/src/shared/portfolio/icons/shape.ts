/** Icon definition: one 24x24 drawing made of strokes. `f` adds small solid accents, `r` repeats `d` rotated about the centre. */
export interface IconDef { l: string; c: string; d: string; f?: string; r?: number[] }

const n = (v: number) => +v.toFixed(2);
/** Circle outline as path data. */
export const ci = (x: number, y: number, r: number) => `M${n(x - r)} ${y}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0-${2 * r} 0z`;
/** A round dot (a zero-length stroke). */
export const dot = (x: number, y: number) => `M${x} ${y}h.01`;
