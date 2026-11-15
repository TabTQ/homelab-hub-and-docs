import { isFiltered, NO_FILTER, toggleId, type LedgerFilter } from "./filter";
import type { PersonRef } from "./types";

/** Show: All / Split only, People: a multi-select. Combinable; mockup "Ledger 6". */
export function FilterBar({ filter, onChange, people, summary }: { filter: LedgerFilter; onChange: (f: LedgerFilter) => void; people: PersonRef[]; summary?: string }) {
  const shown = people.filter((p) => !p.archived || filter.people.includes(p.id));
  return (
    <div className="bud-fbar" role="group" aria-label="Filter the entries">
      <span className="bud-lab">Show</span>
      <button type="button" className="bud-chip" aria-pressed={!isFiltered(filter)} onClick={() => onChange(NO_FILTER)}>All</button>
      <button type="button" className="bud-chip" aria-pressed={filter.split} onClick={() => onChange({ ...filter, split: !filter.split })}>Split only</button>
      <span className="bud-sep" aria-hidden="true" />
      <span className="bud-lab" id="bud-fbar-people">People</span>
      <span className="bud-fchips" role="group" aria-labelledby="bud-fbar-people">
        {shown.map((p) => (
          <button key={p.id} type="button" className="bud-chip" aria-pressed={filter.people.includes(p.id)} onClick={() => onChange({ ...filter, people: toggleId(filter.people, p.id) })}>{p.name}</button>
        ))}
      </span>
      {summary && <span className="bud-hint bud-fsum" aria-live="polite">{summary}</span>}
    </div>
  );
}
