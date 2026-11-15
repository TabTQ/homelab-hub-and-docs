import { useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiError } from "../api";
import { useToast } from "../components/Toast";
import type { Portfolio, ResourceSchema } from "../types";
import { EditorDrawer, type OpenTarget } from "./EditorDrawer";
import type { PaletteItem } from "../components/Palette";
import { PortfolioShell } from "./PortfolioShell";

const NOTE_KEY = "hub-edit-note";
const noteGone = () => { try { return localStorage.getItem(NOTE_KEY) === "1"; } catch { return false; } };

/** A short, dismissible note on how the editor works. The choice is remembered in this browser. */
export function EditingNote() {
  const [gone, setGone] = useState(noteGone);
  if (gone) return null;
  return (
    <div className="edit-note" role="note" aria-label="How editing works">
      <div>
        <b>How editing works.</b> One dataset feeds all seven tabs, so you edit each thing once and every tab shows it. Edit anything where you see it, using the pencil buttons.
        Display options (icon, core skill, constellation) are in the skill, group and project forms. Publish makes it live: every save is published to the public site within a moment.
      </div>
      <button className="btn sm" onClick={() => { setGone(true); try { localStorage.setItem(NOTE_KEY, "1"); } catch { /* not saved */ } }}>Got it</button>
    </div>
  );
}

interface Publish { upToDate: boolean; lastPublished: string | null; lastError: string | null }

export function PortfolioAdmin({ header, onUnauthorized, extraPalette }: { header: React.ReactNode; onUnauthorized: () => void; extraPalette: PaletteItem[] }) {
  const toast = useToast();
  const [data, setData] = useState<Portfolio | null>(null);
  const [schema, setSchema] = useState<ResourceSchema[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [target, setTarget] = useState<OpenTarget | null>(null);
  const [publish, setPublish] = useState<Publish | null>(null);
  const [managing, setManaging] = useState(false);

  const hooks = useMemo(() => ({ onEdit: (resource: string, id?: number) => setTarget({ resource, id }), onAdd: (resource: string, parent?: { api: string; id: number }) => setTarget({ resource, parent }) }), []);
  const fail = useCallback((e: ApiError) => { if (e.status === 401) onUnauthorized(); else setError(e.message); }, [onUnauthorized]);
  const reload = useCallback(() => {
    api<Portfolio>("/api/portfolio").then((d) => { setData(d); setError(null); }).catch(fail);
    api<Publish>("/api/admin/portfolio/publish-status").then(setPublish).catch(() => {});
  }, [fail]);

  useEffect(() => { reload(); api<ResourceSchema[]>("/api/admin/portfolio/schema").then(setSchema).catch(fail); }, [reload, fail]);

  if (error) return <div className="wrap"><div className="banner bad" role="alert" style={{ marginTop: 24 }}>{error}</div></div>;
  if (!data) return <>{header}<div className="wrap"><p className="sub">Loading the portfolio…</p></div></>;

  const edit = hooks;
  const ribbon = (
    <>
    <EditingNote />
    <div className="ribbon">
      <span><b>Editing view.</b> Every save is published to the public site within a moment.{" "}
        {publish && (publish.upToDate ? <span>Public site is up to date.</span> : <b style={{ color: "var(--bad)" }}>Public site is out of date. {publish.lastError}</b>)}</span>
      <span style={{ display: "flex", gap: 8 }}>
        {publish && !publish.upToDate && <button className="btn" onClick={() => api("/api/admin/portfolio/republish", { method: "POST" }).then(() => { toast("Published."); reload(); }).catch(fail)}>Retry publishing</button>}
        <button className="btn" onClick={() => setManaging(true)}>Manage skills</button>
      </span>
    </div>
    </>
  );

  return (
    <>
      <PortfolioShell data={data} header={header} ribbon={ribbon} extraPalette={extraPalette} edit={edit} />
      {target && schema.length > 0 && (
        <EditorDrawer key={`${target.resource}:${target.id ?? "new"}`} schema={schema} target={target} portfolio={data} onUnauthorized={onUnauthorized}
          onClose={() => setTarget(null)} onOpen={setTarget}
          onSaved={(stale) => { setTarget(null); reload(); toast(stale ? "Saved, but the public site could not be updated yet. It will retry." : "Saved and published."); }} />
      )}
      {managing && (
        <div>
          <div className="scrim show" style={{ zIndex: 85 }} onClick={() => setManaging(false)} />
          <aside className="drawer" role="dialog" aria-modal="true" aria-label="Manage skills" style={{ zIndex: 90 }}>
            <header><h2>Manage skills</h2><button className="iconbtn" aria-label="Close" onClick={() => setManaging(false)}>×</button></header>
            <div className="body">
              <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
                <button className="btn" onClick={() => { setManaging(false); setTarget({ resource: "skill-groups" }); }}>Add group</button>
              </div>
              {data.skills.map((g) => (
                <div key={g.id} style={{ marginBottom: 14 }}>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                    <b>{g.name}</b>
                    <span style={{ display: "flex", gap: 6 }}>
                      <button className="btn sm" onClick={() => { setManaging(false); setTarget({ resource: "skill-groups", id: g.id }); }}>Edit group</button>
                      <button className="btn sm" onClick={() => { setManaging(false); setTarget({ resource: "skills", parent: { api: "groupId", id: g.id } }); }}>Add skill</button>
                    </span>
                  </div>
                  <div className="chips">{g.skills.map((s) => <button key={s.id} className="sk" onClick={() => { setManaging(false); setTarget({ resource: "skills", id: s.id }); }}>{s.l}<span className="n">{s.n || "—"}</span></button>)}</div>
                </div>
              ))}
            </div>
          </aside>
        </div>
      )}
    </>
  );
}
