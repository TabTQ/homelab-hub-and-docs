import { useEffect, useState } from "react";
import { api } from "../shared/api";
import { useToast } from "../shared/components/Toast";
import { A, go, when } from "./docsApi";

interface TrashItem { id: number; title: string; kind: string; deletedAt: string; purgeAt: string; children: number; publicHost: string | null }

/** Deleted pages, restorable for 30 days. A restored page comes back internal: publishing it again is a choice. */
export function Trash({ onChanged, fail }: { onChanged: () => void; fail: (e: any) => void }) {
  const [items, setItems] = useState<TrashItem[] | null>(null);
  const toast = useToast();
  const load = () => api<TrashItem[]>(`${A}/trash`).then(setItems).catch(fail);
  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const restore = (t: TrashItem) => api(`${A}/trash/${t.id}/restore`, { method: "POST" })
    .then(() => { onChanged(); toast(`Restored “${t.title}”.`); go(`#/p/${t.id}`); }).catch(fail);
  return (
    <div className="dx-page">
      <div className="dx-pagehead"><h1>Trash</h1></div>
      <p className="sub">Deleted pages stay here for 30 days, then they are removed for good. Restoring brings back the pages deleted with it.</p>
      {!items ? <p className="hint">Loading…</p> : items.length === 0 ? <p className="hint">The Trash is empty.</p> : (
        <table className="dx-bundles dx-trash">
          <thead><tr><th>Page</th><th>Deleted</th><th>Removed for good</th><th /></tr></thead>
          <tbody>{items.map((t) => (
            <tr key={t.id}>
              <td><b>{t.title}</b>{t.kind === "webapp" && <> <span className="dx-pill kapp">app</span></>}{t.children > 0 && <span className="hint"> + {t.children} page{t.children === 1 ? "" : "s"} under it</span>}</td>
              <td>{when(t.deletedAt)}</td><td>{when(t.purgeAt)}</td>
              <td><button className="btn sm" onClick={() => restore(t)}>Restore</button></td>
            </tr>
          ))}</tbody>
        </table>
      )}
    </div>
  );
}
