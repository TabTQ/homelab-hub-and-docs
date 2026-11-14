import { useId } from "react";

// Original crest mark: a T made of a braided shaft and two axe blades, over a base arc. Uses currentColor, so it takes the text colour.
const CUTS = [
  "M50 24 L49 25.1 L48.2 26.2 L47.6 27.2 L47.4 28.3 L47.6 29.4 L48.2 30.5 L49 31.6 L50 32.7 L51 33.8 L51.8 34.8 L52.4 35.9 L52.6 37 L52.4 38.1 L51.8 39.2 L51 40.2 L50 41.3 L49 42.4 L48.2 43.5 L47.6 44.6 L47.4 45.7 L47.6 46.8 L48.2 47.8 L49 48.9 L50 50 L51 51.1 L51.8 52.2 L52.4 53.2 L52.6 54.3 L52.4 55.4 L51.8 56.5 L51 57.6 L50 58.7 L49 59.8 L48.2 60.8 L47.6 61.9 L47.4 63 L47.6 64.1 L48.2 65.2 L49 66.2 L50 67.3 L51 68.4 L51.8 69.5 L52.4 70.6 L52.6 71.7 L52.4 72.8 L51.8 73.8 L51 74.9 L50 76",
  "M50 24 L51 25.1 L51.8 26.2 L52.4 27.2 L52.6 28.3 L52.4 29.4 L51.8 30.5 L51 31.6 L50 32.7 L49 33.8 L48.2 34.8 L47.6 35.9 L47.4 37 L47.6 38.1 L48.2 39.2 L49 40.2 L50 41.3 L51 42.4 L51.8 43.5 L52.4 44.6 L52.6 45.7 L52.4 46.8 L51.8 47.8 L51 48.9 L50 50 L49 51.1 L48.2 52.2 L47.6 53.2 L47.4 54.3 L47.6 55.4 L48.2 56.5 L49 57.6 L50 58.7 L51 59.8 L51.8 60.8 L52.4 61.9 L52.6 63 L52.4 64.1 L51.8 65.2 L51 66.2 L50 67.3 L49 68.4 L48.2 69.5 L47.6 70.6 L47.4 71.7 L47.6 72.8 L48.2 73.8 L49 74.9 L50 76",
  "M 11.5 9 C 8.5 18 11 28 17.5 36",
  "M 88.5 9 C 91.5 18 89 28 82.5 36",
  "M 45 100 L 50 98.3 L 55 100"
];
const SHAPES = [
  "M 44.5 20 L 55.5 20 L 56.5 74 L 50 92 L 43.5 74 Z",
  "M 50 4 L 43 12 C 33 13 21 10 9 3 C 3 16 6 31 17 42 C 23 30 33 23 44.5 22 L 50 22 Z",
  "M 50 4 L 57 12 C 67 13 79 10 91 3 C 97 16 94 31 83 42 C 77 30 67 23 55.5 22 L 50 22 Z",
  "M 22 92 C 33 100 44 101.5 50 97.5 C 56 101.5 67 100 78 92 C 68 105 57 106 50 102 C 43 106 32 105 22 92 Z"
];

export function Crest({ className }: { className?: string }) {
  const id = "crest" + useId().replace(/:/g, "");
  return (
    <svg className={className} viewBox="0 0 100 108" fill="currentColor" aria-hidden="true" focusable="false">
      <defs>
        <mask id={id}>
          <rect width="100" height="108" fill="#fff" />
          <g fill="none" stroke="#000" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
            {CUTS.map((d, i) => <path key={i} d={d} />)}
          </g>
        </mask>
      </defs>
      <g mask={`url(#${id})`}>
        {SHAPES.map((d, i) => <path key={i} d={d} />)}
      </g>
    </svg>
  );
}
