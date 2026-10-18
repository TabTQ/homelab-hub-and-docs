import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { EditorState } from "@codemirror/state";
import { drawSelection, EditorView, highlightActiveLine, keymap, lineNumbers } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { bracketMatching, HighlightStyle, indentOnInput, syntaxHighlighting } from "@codemirror/language";
import { yaml } from "@codemirror/lang-yaml";
import { lintGutter, setDiagnostics, type Diagnostic } from "@codemirror/lint";
import { tags as t } from "@lezer/highlight";
import { api, ApiError, primeCsrf } from "../../shared/api";
import type { ConfigIssue, ConfigState, ConfigVersion } from "../../shared/types";
import { useToast } from "../../shared/components/Toast";
import { Modal } from "../components/Modal";
import { diffLines, hasChanges, type DiffLine } from "../../shared/diff";
import { addAction, addService, listActions, listGroups, listServices, removeAction, removeService, slugId, type Edited, type ActionRef, type ServiceRef } from "../yamlEdit";

/** Kept for existing imports; the dialog lives in components/Modal so light pages need not load the editor. */
export { Modal };

const highlight = HighlightStyle.define([
  { tag: t.comment, color: "var(--muted)", fontStyle: "italic" },
  { tag: [t.string, t.special(t.string)], color: "var(--ok)" },
  { tag: [t.number, t.bool, t.null], color: "var(--warn)" },
  { tag: [t.propertyName, t.definition(t.propertyName)], color: "var(--ink)", fontWeight: "600" },
  { tag: [t.keyword, t.operator, t.punctuation], color: "var(--accent)" },
]);

const theme = EditorView.theme({
  "&": { backgroundColor: "var(--surface)", color: "var(--ink)" },
  ".cm-content": { fontFamily: "var(--f-mono)", caretColor: "var(--accent)" },
  ".cm-gutters": { backgroundColor: "var(--surface2)", color: "var(--muted)", border: "none" },
  ".cm-activeLine": { backgroundColor: "color-mix(in srgb, var(--accent-soft) 45%, transparent)" },
  ".cm-activeLineGutter": { backgroundColor: "var(--accent-soft)" },
  ".cm-selectionBackground, &.cm-focused .cm-selectionBackground": { backgroundColor: "var(--accent-soft)" },
  ".cm-cursor": { borderLeftColor: "var(--accent)" },
});

type Dialog = null | { kind: "diff"; lines: DiffLine[] } | { kind: "conflict"; theirs: ConfigState } | { kind: "service" } | { kind: "action" } | { kind: "rmservice" } | { kind: "rmaction" } | { kind: "restore"; v: ConfigVersion };

export default function ConfigPage({ onUnauthorized, onSaved }: { onUnauthorized: () => void; onSaved: () => void }) {
  const toast = useToast();
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const [server, setServer] = useState<ConfigState | null>(null);
  const serverRef = useRef<ConfigState | null>(null);
  const [text, setText] = useState("");
  const [issues, setIssues] = useState<ConfigIssue[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [versions, setVersions] = useState<ConfigVersion[]>([]);
  const [dialog, setDialog] = useState<Dialog>(null);
  const dirty = server != null && text !== server.text;

  const fail = useCallback((e: ApiError) => { if (e.status === 401) onUnauthorized(); else setError(e.message); }, [onUnauthorized]);

  const loadVersions = useCallback(() => { api<ConfigVersion[]>("/api/admin/config/versions").then(setVersions).catch(fail); }, [fail]);

  const load = useCallback(() => {
    api<ConfigState>("/api/admin/config").then((s) => {
      setServer(s); serverRef.current = s; setText(s.text); setIssues(s.issues); setError(null);
      const v = view.current;
      if (v) v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: s.text } });
    }).catch(fail);
    loadVersions();
  }, [fail, loadVersions]);

  // ---- editor ----
  useEffect(() => {
    if (!host.current) return;
    const v = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: "",
        extensions: [
          lineNumbers(), history(), drawSelection(), indentOnInput(), bracketMatching(), highlightActiveLine(), lintGutter(), yaml(),
          syntaxHighlighting(highlight), theme, EditorView.lineWrapping,
          keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab, { key: "Mod-s", run: () => { window.dispatchEvent(new Event("hub:config-save")); return true; } }]),
          EditorView.updateListener.of((u) => { if (u.docChanged) setText(u.state.doc.toString()); }),
          EditorView.contentAttributes.of({ "aria-label": "dashboard.yaml" }),
        ],
      }),
    });
    view.current = v;
    load();
    return () => { v.destroy(); view.current = null; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---- live validation (server is the source of truth, including line numbers) ----
  useEffect(() => {
    if (!server) return;
    const h = window.setTimeout(() => {
      api<{ ok: boolean; issues: ConfigIssue[] }>("/api/admin/config/validate", { method: "POST", body: { text } }).then((r) => setIssues(r.issues)).catch(() => {});
    }, 600);
    return () => window.clearTimeout(h);
  }, [text, server]);

  useEffect(() => {
    const v = view.current;
    if (!v) return;
    const diags: Diagnostic[] = issues.filter((i) => i.line > 0 && i.line <= v.state.doc.lines).map((i) => {
      const l = v.state.doc.line(i.line);
      return { from: l.from, to: Math.max(l.from, l.to), severity: i.level === "ERROR" ? "error" : "warning", message: (i.path ? i.path + ": " : "") + i.message };
    });
    v.dispatch(setDiagnostics(v.state, diags));
  }, [issues, text]);

  const errors = issues.filter((i) => i.level === "ERROR");
  const warnings = issues.filter((i) => i.level === "WARNING");

  const setEditorText = (next: string) => {
    const v = view.current;
    if (v) v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: next }, scrollIntoView: true });
  };

  // ---- saving ----
  const askSave = useCallback(() => {
    if (!serverRef.current) return;
    const d = diffLines(serverRef.current.text, view.current?.state.doc.toString() ?? "");
    if (!hasChanges(d)) { toast("Nothing to save."); return; }
    setDialog({ kind: "diff", lines: d });
  }, [toast]);
  useEffect(() => { window.addEventListener("hub:config-save", askSave); return () => window.removeEventListener("hub:config-save", askSave); }, [askSave]);

  async function save(sha: string) {
    setBusy(true);
    try {
      await primeCsrf();
      const body = { text: view.current?.state.doc.toString() ?? "" };
      const r = await fetch("/api/admin/config", {
        method: "PUT", credentials: "same-origin",
        headers: { "Content-Type": "application/json", "If-Match": `"${sha}"`, "X-XSRF-TOKEN": xsrf() }, body: JSON.stringify(body),
      });
      const json = await r.json().catch(() => ({}));
      if (r.status === 401) { onUnauthorized(); return; }
      if (r.status === 409) { setDialog({ kind: "conflict", theirs: json as ConfigState }); return; }
      if (r.status === 422) { setIssues(json.issues ?? []); setDialog(null); setError("The file has errors, so it was not saved. The lines are marked in the editor."); return; }
      if (!r.ok) { setError(json.message ?? `Save failed (HTTP ${r.status})`); return; }
      const s = json as ConfigState;
      setServer(s); serverRef.current = s; setIssues(s.issues); setDialog(null); setError(null);
      toast("Saved. The dashboard is using it now.");
      loadVersions();
      onSaved();
    } finally { setBusy(false); }
  }

  async function restore(v: ConfigVersion) {
    setBusy(true);
    try {
      await primeCsrf();
      const r = await fetch(`/api/admin/config/versions/${v.id}/restore`, { method: "POST", credentials: "same-origin", headers: { "If-Match": `"${serverRef.current?.sha256}"`, "X-XSRF-TOKEN": xsrf() } });
      if (r.status === 409) { setDialog({ kind: "conflict", theirs: await r.json() }); return; }
      if (!r.ok) { const j = await r.json().catch(() => ({})); setError(j.issues?.[0]?.message ?? j.message ?? `Restore failed (HTTP ${r.status})`); setDialog(null); return; }
      const s = (await r.json()) as ConfigState;
      setServer(s); serverRef.current = s; setEditorText(s.text); setIssues(s.issues); setDialog(null); setError(null);
      toast("Restored.");
      loadVersions();
      onSaved();
    } finally { setBusy(false); }
  }

  const groups = useMemo(() => listGroups(text), [text]);

  function applyEdit(r: Edited, what: string, verb = "added") {
    setEditorText(r.text);
    setDialog(null);
    toast(r.reformatted ? `${what} ${verb}. The file layout was unusual, so it was re-formatted. Check the diff before saving.` : `${what} ${verb}${verb === "added" ? " to the editor" : " in the editor"}. Review it, then Save.`);
  }
  const services = useMemo(() => listServices(text), [text]);
  const actionRefs = useMemo(() => listActions(text), [text]);

  const health = server?.health ?? "ok";
  return (
    <>
      <h1>Config</h1>
      <p className="sub">This is <span className="mono">dashboard.yaml</span>. It is checked before it is saved, kept in a version history, and a mistake never takes the dashboard down: the last working version keeps being used.</p>
      {health !== "ok" && server && (
        <div className="banner bad" role="alert"><b>The file on disk has errors.</b> {health === "using_default" ? "The starter dashboard is being shown." : "The last working version is being shown."} Fix the marked lines below and save.</div>
      )}
      {error && <div className="banner bad" role="alert">{error}</div>}
      <div className="cfg">
        <div>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", margin: "0 0 10px", alignItems: "center" }}>
            <button className="btn pri" disabled={!dirty || busy || errors.length > 0} onClick={askSave} title={errors.length ? "Fix the errors first" : "Ctrl+S"}>Save…</button>
            <button className="btn" disabled={!dirty || busy} onClick={() => server && setEditorText(server.text)}>Discard changes</button>
            <span className="grow" />
            <button className="btn" onClick={() => setDialog({ kind: "service" })}>Add service</button>
            <button className="btn" onClick={() => setDialog({ kind: "action" })}>Add action</button>
            <button className="btn" disabled={services.length === 0} onClick={() => setDialog({ kind: "rmservice" })}>Remove service</button>
            <button className="btn" disabled={actionRefs.length === 0} onClick={() => setDialog({ kind: "rmaction" })}>Remove action</button>
            <span className="state" aria-live="polite">{dirty ? "unsaved changes" : "saved"}</span>
          </div>
          <div className="cm-host" ref={host} />
          <div className="eyebrow" style={{ marginTop: 14 }}>{errors.length} error{errors.length === 1 ? "" : "s"} · {warnings.length} warning{warnings.length === 1 ? "" : "s"}</div>
          <ul className="issues" aria-label="Problems">
            {issues.map((i, k) => (
              <li key={k}><span className={"lv " + i.level.toLowerCase()}>{i.level}</span><span>{i.line > 0 && <button className="linkbtn" onClick={() => { const v = view.current; if (v && i.line <= v.state.doc.lines) { const l = v.state.doc.line(i.line); v.dispatch({ selection: { anchor: l.from }, scrollIntoView: true }); v.focus(); } }}>line {i.line}</button>}{i.line > 0 ? ": " : ""}{i.path && <span className="mono">{i.path} </span>}{i.message}</span></li>
            ))}
            {issues.length === 0 && <li style={{ color: "var(--muted)" }}>No problems found.</li>}
          </ul>
        </div>
        <aside>
          <h2 className="tape">Versions</h2>
          <p className="sub" style={{ margin: "8px 0" }}>The last 50 saved versions. Restoring one is itself a saved change.</p>
          <ul className="vlist">
            {versions.map((v, idx) => (
              <li key={v.id}>
                <span><span className="mono">{v.ts.replace("T", " ").slice(0, 16)}</span><br /><span style={{ color: "var(--muted)" }}>{v.source}{v.note ? ` · ${v.note}` : ""}</span></span>
                {idx === 0 ? <span className="state">current</span> : <button className="btn sm" onClick={() => setDialog({ kind: "restore", v })}>Restore</button>}
              </li>
            ))}
          </ul>
        </aside>
      </div>

      {dialog?.kind === "diff" && (
        <Modal title="Review your changes" onClose={() => setDialog(null)} wide
          actions={<><button className="btn" onClick={() => setDialog(null)}>Back</button><button className="btn pri" disabled={busy} onClick={() => server && save(server.sha256)}>{busy ? "Saving…" : "Save these changes"}</button></>}>
          <DiffView lines={dialog.lines} />
        </Modal>
      )}
      {dialog?.kind === "conflict" && (
        <Modal title="The file changed while you were editing" onClose={() => setDialog(null)} wide
          actions={<>
            <button className="btn" onClick={() => setDialog(null)}>Keep editing</button>
            <button className="btn" onClick={() => { setServer(dialog.theirs); serverRef.current = dialog.theirs; setEditorText(dialog.theirs.text); setIssues(dialog.theirs.issues); setDialog(null); }}>Discard mine, load the file on disk</button>
            <button className="btn danger" disabled={busy} onClick={() => { const th = dialog.theirs; setServer(th); serverRef.current = th; save(th.sha256); }}>Overwrite with mine</button></>}>
          <p>Someone (or you, over SSH) saved a different version. Below is the file on disk compared with your version.</p>
          <DiffView lines={diffLines(dialog.theirs.text, view.current?.state.doc.toString() ?? "")} />
        </Modal>
      )}
      {dialog?.kind === "restore" && (
        <Modal title={`Restore the version from ${dialog.v.ts.replace("T", " ").slice(0, 16)}?`} onClose={() => setDialog(null)}
          actions={<><button className="btn" onClick={() => setDialog(null)}>Cancel</button><button className="btn pri" disabled={busy} onClick={() => restore(dialog.v)}>Restore</button></>}>
          <p>Your unsaved edits in the editor will be replaced. The current file stays in the version history.</p>
        </Modal>
      )}
      {dialog?.kind === "service" && <ServiceForm groups={groups} onCancel={() => setDialog(null)} onAdd={(svc) => { try { applyEdit(addService(text, svc), "Service"); } catch (e) { setError((e as Error).message); setDialog(null); } }} />}
      {dialog?.kind === "rmservice" && <RemoveServiceForm services={services} onCancel={() => setDialog(null)} onRemove={(sv) => { try { applyEdit(removeService(text, sv.path), `“${sv.name}”`, "removed"); } catch (e) { setError((e as Error).message); setDialog(null); } }} />}
      {dialog?.kind === "rmaction" && <RemoveActionForm actions={actionRefs} onCancel={() => setDialog(null)} onRemove={(a) => { try { const r = removeAction(text, a.id); applyEdit(r, `Action “${a.label || a.id}”`, "removed"); } catch (e) { setError((e as Error).message); setDialog(null); } }} />}
      {dialog?.kind === "action" && <ActionForm groups={groups} onCancel={() => setDialog(null)} onAdd={(a) => { try { applyEdit(addAction(text, a), "Action"); } catch (e) { setError((e as Error).message); setDialog(null); } }} />}
    </>
  );
}

function xsrf(): string {
  const m = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="));
  return m ? decodeURIComponent(m.slice(11)) : "";
}

export function DiffView({ lines }: { lines: DiffLine[] }) {
  const changed = lines.map((l, i) => (l.type !== "same" ? i : -1)).filter((i) => i >= 0);
  const show = new Set<number>();
  changed.forEach((i) => { for (let k = Math.max(0, i - 2); k <= Math.min(lines.length - 1, i + 2); k++) show.add(k); });
  const rows: React.ReactElement[] = [];
  let skipped = false;
  lines.forEach((l, i) => {
    if (!show.has(i)) { if (!skipped) rows.push(<div key={"s" + i} style={{ color: "var(--muted)" }}>…</div>); skipped = true; return; }
    skipped = false;
    rows.push(<div key={i} className={l.type === "add" ? "add" : l.type === "del" ? "del" : ""}>{l.type === "add" ? "+ " : l.type === "del" ? "− " : "  "}{l.text || " "}</div>);
  });
  return <pre className="diff-pre" style={{ margin: 0, padding: 10, background: "var(--bg)", border: "1px solid var(--line)", borderRadius: 4, font: "12px/1.5 var(--f-mono)", overflow: "auto", maxHeight: "50vh", whiteSpace: "pre-wrap" }}>
    {style()}{rows}
  </pre>;
}
const style = () => <style>{`.diff-pre .add{background:color-mix(in srgb,var(--ok) 22%,transparent)}.diff-pre .del{background:color-mix(in srgb,var(--bad) 22%,transparent)}`}</style>;

function ServiceForm({ groups, onAdd, onCancel }: { groups: string[]; onAdd: (s: Parameters<typeof addService>[1]) => void; onCancel: () => void }) {
  const [group, setGroup] = useState(groups[0] ?? "");
  const [name, setName] = useState(""); const [href, setHref] = useState(""); const [icon, setIcon] = useState(""); const [desc, setDesc] = useState("");
  const [kind, setKind] = useState<"" | "tcp" | "http" | "icmp">(""); const [target, setTarget] = useState("");
  const ok = group.trim() !== "" && name.trim() !== "";
  return (
    <Modal title="Add a service" onClose={onCancel}
      actions={<><button className="btn" onClick={onCancel}>Cancel</button><button className="btn pri" disabled={!ok} onClick={() => onAdd({ group, name, href, icon, description: desc, status: kind && target ? { kind, target } : undefined })}>Add to the editor</button></>}>
      <div className="field"><label htmlFor="sv-g">Group</label>
        <input id="sv-g" type="text" list="sv-groups" value={group} onChange={(e) => setGroup(e.target.value)} /><datalist id="sv-groups">{groups.map((g) => <option key={g} value={g} />)}</datalist>
        <div className="help">Pick an existing group, or type a new name to create one.</div></div>
      <div className="field"><label htmlFor="sv-n">Name <span className="req">*</span></label><input id="sv-n" type="text" value={name} onChange={(e) => setName(e.target.value)} autoFocus /></div>
      <div className="field"><label htmlFor="sv-h">Link</label><input id="sv-h" type="text" value={href} placeholder="http://192.168.10.101:3000/" onChange={(e) => setHref(e.target.value)} /></div>
      <div className="field"><label htmlFor="sv-i">Icon</label><input id="sv-i" type="text" value={icon} placeholder="/images/logo.png or an https address (optional)" onChange={(e) => setIcon(e.target.value)} /></div>
      <div className="field"><label htmlFor="sv-d">Description</label><input id="sv-d" type="text" value={desc} onChange={(e) => setDesc(e.target.value)} /></div>
      <div className="field"><label htmlFor="sv-k">Status check</label>
        <select id="sv-k" value={kind} onChange={(e) => setKind(e.target.value as typeof kind)}><option value="">None</option><option value="tcp">TCP port (host:port)</option><option value="http">Web address (http://…)</option><option value="icmp">Ping (host)</option></select>
        {kind && <input aria-label="Status target" type="text" style={{ marginTop: 6 }} value={target} placeholder={kind === "tcp" ? "192.168.10.101:3000" : kind === "http" ? "http://192.168.10.101:3000/" : "192.168.10.101"} onChange={(e) => setTarget(e.target.value)} />}</div>
    </Modal>
  );
}

function ActionForm({ groups, onAdd, onCancel }: { groups: string[]; onAdd: (a: Parameters<typeof addAction>[1]) => void; onCancel: () => void }) {
  const [type, setType] = useState<"wol" | "proxmox-node" | "proxmox-guest">("wol");
  const [label, setLabel] = useState(""); const [id, setId] = useState(""); const [idTouched, setIdTouched] = useState(false);
  const [mac, setMac] = useState(""); const [node, setNode] = useState("pve"); const [command, setCommand] = useState<"shutdown" | "reboot">("shutdown");
  const [kind, setKind] = useState<"lxc" | "qemu">("lxc"); const [vmid, setVmid] = useState(""); const [icmp, setIcmp] = useState("");
  const [group, setGroup] = useState(""); const [pin, setPin] = useState(false);
  const finalId = idTouched ? id : slugId(label);
  const ok = label.trim() && finalId && (type === "wol" ? mac.trim() : type === "proxmox-node" ? node.trim() : node.trim() && /^\d+$/.test(vmid));
  return (
    <Modal title="Add an action" onClose={onCancel}
      actions={<><button className="btn" onClick={onCancel}>Cancel</button><button className="btn pri" disabled={!ok}
        onClick={() => onAdd({ type, id: finalId, label, mac, node, command, kind, vmid: vmid ? Number(vmid) : undefined, icmp, placeInGroup: group, pin })}>Add to the editor</button></>}>
      <div className="field"><label htmlFor="ac-t">What should it do?</label>
        <select id="ac-t" value={type} onChange={(e) => setType(e.target.value as typeof type)}><option value="wol">Wake a device (Wake-on-LAN)</option><option value="proxmox-node">Shut down or reboot the Proxmox host</option><option value="proxmox-guest">Start or stop a container / VM</option></select></div>
      <div className="field"><label htmlFor="ac-l">Label <span className="req">*</span></label><input id="ac-l" type="text" value={label} onChange={(e) => setLabel(e.target.value)} autoFocus /></div>
      <div className="field"><label htmlFor="ac-i">Id</label><input id="ac-i" type="text" value={finalId} onChange={(e) => { setId(e.target.value); setIdTouched(true); }} /><div className="help">Used in pinned and buttons. Lower-case letters, digits, - and _.</div></div>
      {type === "wol" && <div className="field"><label htmlFor="ac-m">MAC address</label><input id="ac-m" type="text" value={mac} placeholder="AA:BB:CC:DD:EE:01" onChange={(e) => setMac(e.target.value)} /></div>}
      {type !== "wol" && <div className="field"><label htmlFor="ac-n">Proxmox node</label><input id="ac-n" type="text" value={node} onChange={(e) => setNode(e.target.value)} /></div>}
      {type === "proxmox-node" && <div className="field"><label htmlFor="ac-c">Command</label><select id="ac-c" value={command} onChange={(e) => setCommand(e.target.value as typeof command)}><option value="shutdown">Shut down</option><option value="reboot">Reboot</option></select></div>}
      {type === "proxmox-guest" && <><div className="field"><label htmlFor="ac-k">Kind</label><select id="ac-k" value={kind} onChange={(e) => setKind(e.target.value as typeof kind)}><option value="lxc">LXC container</option><option value="qemu">QEMU VM</option></select></div>
        <div className="field"><label htmlFor="ac-v">ID number</label><input id="ac-v" type="text" inputMode="numeric" value={vmid} placeholder="100" onChange={(e) => setVmid(e.target.value)} /></div></>}
      <div className="field"><label htmlFor="ac-p">Ping this address to show status (optional)</label><input id="ac-p" type="text" value={icmp} placeholder="192.168.10.102" onChange={(e) => setIcmp(e.target.value)} /></div>
      <div className="field"><label htmlFor="ac-g">Also add a button to a group (optional)</label>
        <input id="ac-g" type="text" list="ac-groups" value={group} onChange={(e) => setGroup(e.target.value)} /><datalist id="ac-groups">{groups.map((g) => <option key={g} value={g} />)}</datalist></div>
      <div className="field"><label><input type="checkbox" checked={pin} onChange={(e) => setPin(e.target.checked)} /> Pin to “Quick actions” at the top</label></div>
    </Modal>
  );
}

function RemoveServiceForm({ services, onRemove, onCancel }: { services: ServiceRef[]; onRemove: (s: ServiceRef) => void; onCancel: () => void }) {
  const [q, setQ] = useState("");
  const [pick, setPick] = useState<number | null>(null);
  const shown = services.map((s, i) => ({ s, i })).filter(({ s }) => s.label.toLowerCase().includes(q.trim().toLowerCase()));
  const chosen = pick != null ? services[pick] : null;
  return (
    <Modal title="Remove a service" onClose={onCancel}
      actions={<><button className="btn" onClick={onCancel}>Cancel</button><button className="btn danger" disabled={!chosen} onClick={() => chosen && onRemove(chosen)}>Remove from the editor</button></>}>
      <div className="field"><label htmlFor="rs-q">Find</label><input id="rs-q" type="text" value={q} placeholder="Type part of the name or group" onChange={(e) => setQ(e.target.value)} autoFocus /></div>
      <div className="pick" role="radiogroup" aria-label="Service to remove">
        {shown.map(({ s, i }) => (
          <label key={i} className={pick === i ? "on" : ""}><input type="radio" name="rs" checked={pick === i} onChange={() => setPick(i)} /> {s.label}{s.isButton && <span className="tagx">button</span>}</label>
        ))}
        {shown.length === 0 && <div className="help">Nothing matches.</div>}
      </div>
      <p className="help">Nothing is saved yet: the entry is removed in the editor, and you review the change before saving.</p>
    </Modal>
  );
}

function RemoveActionForm({ actions, onRemove, onCancel }: { actions: ActionRef[]; onRemove: (a: ActionRef) => void; onCancel: () => void }) {
  const [pick, setPick] = useState<string>("");
  const chosen = actions.find((a) => a.id === pick) ?? null;
  return (
    <Modal title="Remove an action" onClose={onCancel}
      actions={<><button className="btn" onClick={onCancel}>Cancel</button><button className="btn danger" disabled={!chosen} onClick={() => chosen && onRemove(chosen)}>Remove from the editor</button></>}>
      <div className="pick" role="radiogroup" aria-label="Action to remove">
        {actions.map((a) => (
          <label key={a.id} className={pick === a.id ? "on" : ""}><input type="radio" name="ra" checked={pick === a.id} onChange={() => setPick(a.id)} /> {a.label || a.id} <span className="tagx">{a.type}</span></label>
        ))}
      </div>
      {chosen && <div className="impact">
        <div>This also removes {chosen.buttons} dashboard button{chosen.buttons === 1 ? "" : "s"}{chosen.pinned ? " and its “Quick actions” pin" : ""} that use it, so nothing is left pointing at a missing action.</div>
      </div>}
      <p className="help">Nothing is saved yet: you review the change before saving.</p>
    </Modal>
  );
}
