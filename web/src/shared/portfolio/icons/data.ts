import type { IconDef } from "./shape";

const C = "Data engineering";
export const DATA_ICONS: Record<string, IconDef> = {
  etl: { l: "ETL pipeline", c: C, d: "M2 12h5 M7 7h10v10H7z M17 12h5 M19.5 9.5l2.5 2.5-2.5 2.5 M10 10h4 M10 14h4" },
  merge: { l: "Merge / upsert", c: C, d: "M6 3v6c0 3 3 4 6 5 M18 3v6c0 3-3 4-6 5 M12 14v7 M9.5 18.5L12 21l2.5-2.5" },
  dedup: { l: "Remove duplicates", c: C, d: "M3 3h12v12H3z M9 9h12v12H9z M9 15V9h6" },
  except: { l: "Set difference", c: C, d: "M9 6.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11z M15 6.5a5.5 5.5 0 0 1 0 11 M13 9a4 4 0 0 1 0 6" },
  watermark: { l: "Watermark loads", c: C, d: "M3 16c3-2 4.5 2 7.5 0s4.5 2 7.5 0h3 M3 9h2 M8 9h3 M14 9h3 M20 9h1 M12 3v5 M9.5 5.5L12 3l2.5 2.5" },
  distkey: { l: "Distribution key", c: C, d: "M3 12h6l5-7h5 M9 12h10 M9 12l5 7h5 M21 5h.01 M21 12h.01 M21 19h.01" },
  mpp: { l: "MPP slices", c: C, d: "M4 4h4v16H4z M10 4h4v16h-4z M16 4h4v16h-4z" },
  manifest: { l: "Manifest load", c: C, d: "M6 3h9l4 4v14H6z M15 3v4h4 M9 12h7 M9 16h7 M9 8h3" },
  autoloader: { l: "Auto Loader", c: C, d: "M3 6h6l2 2h10v11H3z M12 11v6 M9.5 14.5L12 17l2.5-2.5" },
  medallion: { l: "Medallion layers", c: C, d: "M12 3a6 6 0 1 1 0 12 6 6 0 0 1 0-12z M8.5 14l-2 7 5.5-3 5.5 3-2-7 M12 9h.01" },
  parquet: { l: "Parquet", c: C, d: "M7 3h14l-3 4H4z M7 10h14l-3 4H4z M7 17h14l-3 4H4z" },
  matview: { l: "Materialized view", c: C, d: "M3 4h14v11H3z M3 8h14 M8 8v7 M13.5 20a4.5 4.5 0 0 0 7.5-3.5 M21 13.5v3h-3" },
  event: { l: "Event driven", c: C, d: "M13 2.5L5 13.5h6l-1 8 8-11h-6z" },
};
