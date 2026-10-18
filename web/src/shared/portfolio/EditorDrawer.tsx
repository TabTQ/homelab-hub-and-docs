import { useEffect, useMemo, useState } from "react";
import { ApiError, apiWithHeaders } from "../api";
import { SLUG_RE, slugify } from "../util";
import type { FieldSchema, Portfolio, ResourceSchema, Row } from "../types";

const BASE = "/api/admin/portfolio/";

/** Content types that own child rows, so a parent's drawer can list, edit and add them. */
const CHILDREN: Record<string, { path: string; label: string; parentApi: string }[]> = {
  projects: [{ path: "project-case-studies", label: "Case study", parentApi: "projectId" }, { path: "project-deliverables", label: "Deliverables", parentApi: "projectId" }],
  "homelab-devices": [{ path: "homelab-containers", label: "Containers", parentApi: "deviceId" }, { path: "homelab-services", label: "Services", parentApi: "deviceId" }, { path: "homelab-network", label: "Network entries", parentApi: "deviceId" }],
  "skill-groups": [{ path: "skills", label: "Skills", parentApi: "groupId" }],
};

export interface OpenTarget { resource: string; id?: number; parent?: { api: string; id: number } }

interface Props {
  schema: ResourceSchema[]; target: OpenTarget; portfolio: Portfolio;
  onClose: () => void; onSaved: (stale: boolean) => void; onOpen: (t: OpenTarget) => void; onUnauthorized: () => void;
}

const isList = (t: string) => t === "text_list";
const isJson = (t: string) => t === "json_list" || t === "json_object";

function toForm(f: FieldSchema, v: unknown): string {
  if (v == null) return "";
  if (isList(f.type)) return (v as string[]).join("\n");
  if (isJson(f.type)) return JSON.stringify(v, null, 2);
  return String(v);
}

export function EditorDrawer({ schema, target, portfolio, onClose, onSaved, onOpen, onUnauthorized }: Props) {
  const spec = schema.find((s) => s.path === target.resource)!;
  const [row, setRow] = useState<Row | null>(null);
  const [form, setForm] = useState<Record<string, string>>({});
  const [skillIds, setSkillIds] = useState<Set<number>>(new Set());
  const [tags, setTags] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<Record<string, number> | null>(null);
  const [children, setChildren] = useState<Record<string, Row[]>>({});
  const [skillQuery, setSkillQuery] = useState("");
  const [slugTouched, setSlugTouched] = useState(false);
  const editing = row != null;

  const fail = (e: ApiError) => { if (e.status === 401) onUnauthorized(); else setMessage(e.message); };

  useEffect(() => {
    let dead = false;
    setLoaded(false); setRow(null); setErrors({}); setMessage(null); setConfirmDelete(null); setSlugTouched(false);
    const blank: Record<string, string> = {};
    spec.fields.forEach((f) => { blank[f.api] = f.type === "json_list" ? "[]" : f.type === "json_object" ? "{}" : ""; });
    const load = target.id != null
      ? apiWithHeaders<Row>(`${BASE}${target.resource}/${target.id}`).then((r) => r.data).catch((e: ApiError) => (e.status === 404 && spec.singleton ? null : Promise.reject(e)))
      : Promise.resolve(null);
    load.then((r) => {
      if (dead) return;
      setRow(r);
      const f: Record<string, string> = { ...blank };
      if (r) spec.fields.forEach((fld) => { f[fld.api] = toForm(fld, r[fld.api]); });
      setForm(f);
      setSkillIds(new Set((r?.skillIds as number[]) ?? []));
      setTags(((r?.tags as string[]) ?? []).join("\n"));
      setLoaded(true);
    }).catch((e) => { if (!dead) { fail(e as ApiError); setLoaded(true); } });
    (CHILDREN[target.resource] ?? []).forEach((c) => {
      if (target.id == null) return;
      apiWithHeaders<Row[]>(`${BASE}${c.path}`).then((r) => { if (!dead) setChildren((m) => ({ ...m, [c.path]: r.data.filter((x) => x[c.parentApi] === target.id) })); }).catch(() => {});
    });
    return () => { dead = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target.resource, target.id]);

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [onClose]);

  function body(): { body: Record<string, unknown> } | { local: Record<string, string> } {
    const out: Record<string, unknown> = {};
    const local: Record<string, string> = {};
    for (const f of spec.fields) {
      const v = form[f.api] ?? "";
      if (isList(f.type)) out[f.api] = v.split("\n").map((s) => s.trim()).filter(Boolean);
      else if (isJson(f.type)) {
        try { out[f.api] = JSON.parse(v || (f.type === "json_list" ? "[]" : "{}")); } catch (e) { local[f.api] = "Not valid JSON: " + (e as Error).message; }
      } else if (f.type === "int") out[f.api] = v === "" ? 0 : Number(v);
      else out[f.api] = v;
    }
    if (spec.parent) {
      const p = target.parent?.api === spec.parent.api ? target.parent.id : (row?.[spec.parent.api] as number | undefined) ?? Number(form[spec.parent.api]);
      out[spec.parent.api] = p;
    }
    if (row) out.version = row.version;
    return Object.keys(local).length ? { local } : { body: out };
  }

  async function save() {
    setMessage(null); setErrors({});
    const b = body();
    if ("local" in b) { setErrors(b.local); return; }
    setBusy(true);
    try {
      const path = spec.singleton ? `${BASE}${spec.path}` : row ? `${BASE}${spec.path}/${row.id}` : `${BASE}${spec.path}`;
      let r = await apiWithHeaders<Row>(path, { method: row || spec.singleton ? "PUT" : "POST", body: b.body });
      let stale = r.headers.get("X-Public-Site") === "stale";
      const id = r.data.id;
      if (spec.skills) { r = await apiWithHeaders<Row>(`${BASE}${spec.path}/${id}/skills`, { method: "PUT", body: { skillIds: [...skillIds] } }); stale ||= r.headers.get("X-Public-Site") === "stale"; }
      if (spec.tags) { r = await apiWithHeaders<Row>(`${BASE}${spec.path}/${id}/tags`, { method: "PUT", body: { tags: tags.split("\n").map((s) => s.trim()).filter(Boolean) } }); stale ||= r.headers.get("X-Public-Site") === "stale"; }
      onSaved(stale);
    } catch (x) {
      const e = x as ApiError;
      if (e.status === 401) onUnauthorized();
      else if (e.status === 422 && e.body?.errors) { setErrors(e.body.errors); setMessage("Some fields need attention."); }
      else if (e.status === 409) { setMessage("Someone changed this while you were editing. Reload to see the latest version."); }
      else setMessage(e.message);
    } finally { setBusy(false); }
  }

  async function askDelete() {
    if (!row) return;
    try { setConfirmDelete((await apiWithHeaders<Record<string, number>>(`${BASE}${spec.path}/${row.id}/usage`)).data); } catch (e) { fail(e as ApiError); }
  }
  async function doDelete() {
    if (!row) return;
    setBusy(true);
    try { const r = await apiWithHeaders(`${BASE}${spec.path}/${row.id}`, { method: "DELETE" }); onSaved(r.headers.get("X-Public-Site") === "stale"); }
    catch (e) { fail(e as ApiError); } finally { setBusy(false); }
  }

  const title = `${row ? "Edit" : spec.singleton ? "Set up" : "Add"} ${spec.title.toLowerCase()}`;
  const groupedSkills = useMemo(() => portfolio.skills.map((g) => ({ ...g, skills: g.skills.filter((s) => s.l.toLowerCase().includes(skillQuery.toLowerCase())) })).filter((g) => g.skills.length), [portfolio.skills, skillQuery]);
  const slugField = spec.fields.find((f) => f.type === "slug");
  const sourceField = spec.fields.find((f) => f.type === "text");
  const parentLabel = spec.parent && !target.parent && !row ? spec.parent.path : null;

  return (
    <div>
      <div className="scrim show" style={{ zIndex: 85 }} onClick={onClose} />
      <aside className="drawer" role="dialog" aria-modal="true" aria-label={title} style={{ zIndex: 90 }}>
        <header><h2>{title}</h2><button className="iconbtn" aria-label="Close" onClick={onClose}>×</button></header>
        <div className="body">
          {!loaded && <p className="sub">Loading…</p>}
          {message && <div className="banner bad" role="alert">{message}</div>}
          {loaded && parentLabel && <div className="banner warn">Open this from its {parentLabel.replace("-", " ").replace(/s$/, "")} to add it in the right place.</div>}
          {loaded && spec.fields.map((f) => <Field key={f.api} f={f} value={form[f.api] ?? ""} error={errors[f.api]}
            onChange={(v) => {
              const next = { ...form, [f.api]: v };
              // a new row: suggest the slug from the first text field until the user types a slug themselves
              if (f.type === "slug") setSlugTouched(true);
              else if (!editing && !slugTouched && f.api === sourceField?.api && slugField) next[slugField.api] = slugify(v);
              setForm(next);
            }} />)}
          {errors[spec.parent?.api ?? "_"] && <div className="field"><div className="err">{errors[spec.parent!.api]}</div></div>}
          {errors._ && <div className="field"><div className="err">{errors._}</div></div>}
          {loaded && spec.skills && (
            <div className="field">
              <label>Skills used</label>
              <input type="text" value={skillQuery} placeholder="Filter skills" aria-label="Filter skills" onChange={(e) => setSkillQuery(e.target.value)} />
              <div style={{ maxHeight: 220, overflow: "auto", border: "1px solid var(--line)", borderRadius: 4, padding: 8, marginTop: 6 }}>
                {groupedSkills.map((g) => (
                  <div key={g.id}><div className="eyebrow" style={{ margin: "6px 0 2px" }}>{g.name}</div>
                    <div style={{ display: "flex", flexWrap: "wrap", gap: "2px 12px" }}>
                      {g.skills.map((s) => <label key={s.id} style={{ fontWeight: 400, display: "flex", gap: 5, alignItems: "center" }}>
                        <input type="checkbox" checked={skillIds.has(s.id)} onChange={() => setSkillIds((set) => { const n = new Set(set); if (n.has(s.id)) n.delete(s.id); else n.add(s.id); return n; })} />{s.l}</label>)}
                    </div></div>))}
              </div>
              {errors.skillIds && <div className="err">{errors.skillIds}</div>}
              <div className="help">{skillIds.size} selected. Skills that are not in the list can be added under “Manage skills”.</div>
            </div>
          )}
          {loaded && spec.tags && (
            <div className="field"><label htmlFor="ed-tags">Other tags (not skills)</label>
              <textarea id="ed-tags" value={tags} onChange={(e) => setTags(e.target.value)} /><div className="help">One per line. Shown as plain tags, not part of the skill filter.</div>
              {errors.tags && <div className="err">{errors.tags}</div>}</div>
          )}
          {editing && (CHILDREN[spec.path] ?? []).map((c) => (
            <div className="field" key={c.path}>
              <div className="sub-h" style={{ marginTop: 8 }}>{c.label}</div>
              {(children[c.path] ?? []).map((r) => (
                <div key={r.id} style={{ display: "flex", justifyContent: "space-between", gap: 8, padding: "4px 0", borderTop: "1px solid var(--line)" }}>
                  <span>{String(r.title ?? r.name ?? r.label ?? r.serviceName ?? r.key ?? r.id)}</span>
                  <button className="btn sm" onClick={() => onOpen({ resource: c.path, id: r.id })}>Edit</button></div>))}
              <button className="btn sm" style={{ marginTop: 6 }} onClick={() => onOpen({ resource: c.path, parent: { api: c.parentApi, id: row!.id } })}>Add {c.label.toLowerCase().replace(/s$/, "")}</button>
            </div>
          ))}
          {confirmDelete && (
            <div className="banner bad" role="alert">
              <b>Delete this?</b> {Object.entries(confirmDelete).filter(([, n]) => n > 0).length
                ? "It is used by: " + Object.entries(confirmDelete).filter(([, n]) => n > 0).map(([k, n]) => `${n} ${k}`).join(", ") + ". Those links or rows will be removed too."
                : "Nothing else depends on it."}
              <div style={{ marginTop: 8, display: "flex", gap: 8 }}><button className="btn danger" disabled={busy} onClick={doDelete}>Delete for good</button><button className="btn" onClick={() => setConfirmDelete(null)}>Keep it</button></div>
            </div>
          )}
        </div>
        <footer>
          <div>{editing && !spec.singleton && <button className="btn" onClick={askDelete} disabled={busy}>Delete…</button>}</div>
          <div style={{ display: "flex", gap: 8 }}><button className="btn" onClick={onClose}>Cancel</button><button className="btn pri" onClick={save} disabled={busy || !loaded}>{busy ? "Saving…" : "Save"}</button></div>
        </footer>
      </aside>
    </div>
  );
}

function Field({ f, value, error, onChange }: { f: FieldSchema; value: string; error?: string; onChange: (v: string) => void }) {
  const id = "f-" + f.api;
  let input: React.ReactNode;
  if (f.type === "longtext" || isList(f.type)) input = <textarea id={id} value={value} onChange={(e) => onChange(e.target.value)} />;
  else if (isJson(f.type)) input = <textarea id={id} className="mono" value={value} spellCheck={false} onChange={(e) => onChange(e.target.value)} />;
  else if (f.type === "enum") input = <select id={id} value={value} onChange={(e) => onChange(e.target.value)}><option value="">Choose…</option>{f.allowed.map((a) => <option key={a} value={a}>{a}</option>)}</select>;
  else if (f.type === "int") input = <input id={id} type="number" min={0} max={f.max} value={value} onChange={(e) => onChange(e.target.value)} />;
  else input = <input id={id} type="text" value={value} maxLength={f.max} onChange={(e) => onChange(e.target.value)} />;
  return (
    <div className="field">
      <label htmlFor={id}>{f.label}{f.required && <span className="req"> *</span>}</label>
      {input}
      {f.help && <div className="help">{f.help}</div>}
      {f.type === "slug" && value !== "" && !SLUG_RE.test(value) && !error && (
        <div className="err" role="alert">Not a valid slug: use only lower-case letters, digits and “-”, no spaces or capitals. <button type="button" className="linkbtn" onClick={() => onChange(slugify(value))}>Use “{slugify(value) || "…"}”</button></div>
      )}
      {isList(f.type) && !f.help && <div className="help">One per line.</div>}
      {error && <div className="err" role="alert">{error}</div>}
    </div>
  );
}
