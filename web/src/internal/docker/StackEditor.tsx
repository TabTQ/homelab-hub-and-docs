import { useEffect, useRef, useState } from "react";
import { EditorState } from "@codemirror/state";
import { drawSelection, EditorView, highlightActiveLine, keymap, lineNumbers } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { bracketMatching, HighlightStyle, indentOnInput, syntaxHighlighting } from "@codemirror/language";
import { yaml } from "@codemirror/lang-yaml";
import { tags as t } from "@lezer/highlight";
import { api, ApiError, primeCsrf } from "../../shared/api";
import { useToast } from "../../shared/components/Toast";
import { Modal } from "../components/Modal";
import { diffLines, hasChanges, type DiffLine } from "../../shared/diff";
import { DiffView } from "../pages/ConfigPage";
import { STACK_RE } from "./format";
import { StackDelete } from "./StackDelete";
import type { DockerHost, StackFiles, StackVersion } from "./types";

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

function Editor({ value, onChange, label, isYaml }: { value: string; onChange: (s: string) => void; label: string; isYaml: boolean }) {
  const host = useRef<HTMLDivElement>(null);
  const cb = useRef(onChange);
  cb.current = onChange;
  useEffect(() => {
    if (!host.current) return;
    const v = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          lineNumbers(), history(), drawSelection(), indentOnInput(), bracketMatching(), highlightActiveLine(),
          ...(isYaml ? [yaml(), syntaxHighlighting(highlight)] : []), theme, EditorView.lineWrapping,
          keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab, { key: "Mod-s", run: () => { window.dispatchEvent(new Event("hub:stack-save")); return true; } }]),
          EditorView.updateListener.of((u) => { if (u.docChanged) cb.current(u.state.doc.toString()); }),
          EditorView.contentAttributes.of({ "aria-label": label }),
        ],
      }),
    });
    return () => v.destroy();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return <div ref={host} className="cm-host dk-cm" />;
}

type Dialog = null | { kind: "diff"; lines: DiffLine[] } | { kind: "versions"; list: StackVersion[] } | { kind: "restore"; v: StackVersion };

/**
 * Edit (or create) a compose stack on a host: compose.yaml and its .env. Saving writes both, lets `docker compose config`
 * validate them on the host, and only then replaces the old files. Deploying is a separate, confirmed step.
 */
export default function StackEditor({ host, initial, onClose, onSaved, onStackAction, onUnauthorized }: {
  host: DockerHost; initial: { name: string; compose: string; env: string; isNew: boolean };
  onClose: () => void; onSaved: (name: string, deployNow: boolean) => void;
  /** Stop, take down or delete the whole stack (each confirmed by the page); the editor closes first. */
  onStackAction: (op: "stop" | "down" | "delete", volumes?: boolean) => void; onUnauthorized: () => void;
}) {
  const toast = useToast();
  const [name, setName] = useState(initial.name);
  const [server, setServer] = useState<StackFiles | null>(initial.isNew ? null : { name: initial.name, compose: initial.compose, env: initial.env });
  const [compose, setCompose] = useState(initial.compose);
  const [env, setEnv] = useState(initial.env);
  const [tab, setTab] = useState<"compose" | "env">("compose");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(!initial.isNew && initial.compose === "");
  const [error, setError] = useState<{ message: string; detail?: string } | null>(null);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [gen, setGen] = useState(0);
  const isNew = initial.isNew;
  const base = `/api/admin/docker/hosts/${encodeURIComponent(host.id)}/stacks`;
  const dirty = isNew || server == null || compose !== server.compose || env !== server.env;
  const nameOk = STACK_RE.test(name);
  const edited = server ? compose !== server.compose || env !== server.env : compose !== initial.compose || env !== initial.env || name !== initial.name;

  const fail = (e: ApiError) => { if (e.status === 401) onUnauthorized(); setError({ message: e.message, detail: e.body?.detail }); };

  useEffect(() => {
    if (isNew || !loading) return;
    api<StackFiles>(`${base}/${encodeURIComponent(initial.name)}`)
      .then((s) => { setServer(s); setCompose(s.compose); setEnv(s.env); setGen((g) => g + 1); })
      .catch(fail).finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function save(deployNow: boolean) {
    setBusy(true); setError(null);
    try {
      await primeCsrf();
      const r = isNew
        ? await api<{ ok: boolean; message: string }>(base, { method: "POST", body: { name, compose, env } })
        : await api<{ ok: boolean; message: string }>(`${base}/${encodeURIComponent(name)}`, { method: "PUT", body: { compose, env } });
      toast(r.message);
      setServer({ name, compose, env });
      setDialog(null);
      onSaved(name, deployNow);
    } catch (e) {
      fail(e as ApiError);
      setDialog(null);
    } finally {
      setBusy(false);
    }
  }

  const review = (deployNow: boolean) => {
    if (isNew || !server) { save(deployNow); return; }
    const lines = diffLines(server.compose, compose);
    if (!hasChanges(lines) && env === server.env) { if (deployNow) onSaved(name, true); else toast("No changes to save."); return; }
    setDialog({ kind: "diff", lines });
  };
  const pendingDeploy = useRef(false);

  useEffect(() => { const h = () => { if (dirty && !busy) review(false); }; window.addEventListener("hub:stack-save", h); return () => window.removeEventListener("hub:stack-save", h); });

  const lifecycle = (op: "stop" | "down" | "delete", volumes?: boolean) => {
    if (!edited || window.confirm("Discard unsaved changes?")) onStackAction(op, volumes);
  };

  const openVersions = () => {
    api<StackVersion[]>(`${base}/${encodeURIComponent(name)}/versions`).then((list) => setDialog({ kind: "versions", list })).catch(fail);
  };

  return (
    <Modal title={isNew ? `New stack on ${host.label}` : `Stack ${name} on ${host.label}`} onClose={() => { if (dialog) return; if (!edited || window.confirm("Discard unsaved changes?")) onClose(); }} wide className="dk-stack-modal"
      actions={<>
        {!isNew && (
          <span className="dk-stack-life">
            <button className="btn sm" disabled={busy || loading} onClick={() => lifecycle("stop")} title="Stop every container of the stack">Stop</button>
            <button className="btn sm" disabled={busy || loading} onClick={() => lifecycle("down")} title="Stop and remove the containers; the files stay">Down</button>
            <StackDelete onDelete={(v) => lifecycle("delete", v)} />
          </span>
        )}
        <button className="btn" onClick={() => { if (!edited || window.confirm("Discard unsaved changes?")) onClose(); }}>Close</button>
        {!isNew && <button className="btn" onClick={openVersions}>History</button>}
        <button className="btn" disabled={busy || !dirty || !nameOk || loading} onClick={() => { pendingDeploy.current = false; review(false); }}>{isNew ? "Create" : "Save"}</button>
        <button className="btn pri" disabled={busy || !nameOk || loading} onClick={() => { pendingDeploy.current = true; review(true); }}>{dirty ? (isNew ? "Create and deploy" : "Save and deploy") : "Deploy"}</button>
      </>}>
      {isNew && (
        <div className="field">
          <label htmlFor="dk-stack-name">Stack name <span className="req">*</span></label>
          <input id="dk-stack-name" type="text" value={name} onChange={(e) => setName(e.target.value.toLowerCase())} autoComplete="off" spellCheck={false} autoCapitalize="off" />
          <div className={nameOk || !name ? "help" : "err"}>Lower-case letters, digits, - and _. Stored on the host as <span className="mono">~{host.user}/stacks/{name || "<name>"}/</span></div>
        </div>
      )}
      <div className="seg dk-tabs" role="tablist" aria-label="File">
        <button role="tab" aria-pressed={tab === "compose"} aria-selected={tab === "compose"} onClick={() => setTab("compose")}>compose.yaml</button>
        <button role="tab" aria-pressed={tab === "env"} aria-selected={tab === "env"} onClick={() => setTab("env")}>.env</button>
      </div>
      {loading ? <p className="sub">Loading the stack…</p>
        : tab === "compose"
          ? <Editor key={"c" + gen} value={compose} onChange={setCompose} label="compose.yaml" isYaml />
          : <Editor key={"e" + gen} value={env} onChange={setEnv} label=".env" isYaml={false} />}
      <div className="help dk-help">{tab === "env"
        ? "KEY=value lines, read by compose for ${KEY} in compose.yaml. Kept only on the host (mode 600), never in the hub's history."
        : "Validated by docker compose on the host before it replaces the current file. Ctrl+S saves."}</div>
      {error && <div className="banner bad" role="alert"><div>{error.message}</div>{error.detail && <pre className="dk-out">{error.detail}</pre>}</div>}

      {dialog?.kind === "diff" && (
        <Modal title="Review your changes" onClose={() => setDialog(null)} wide actions={<>
          <button className="btn" onClick={() => setDialog(null)}>Back</button>
          <button className="btn pri" disabled={busy} onClick={() => save(pendingDeploy.current)}>{busy ? "Saving…" : pendingDeploy.current ? "Save and deploy" : "Save"}</button>
        </>}>
          {hasChanges(dialog.lines) ? <DiffView lines={dialog.lines} /> : <p className="sub">compose.yaml is unchanged.</p>}
          {server && env !== server.env && <p className="sub">.env changed too (not shown).</p>}
        </Modal>
      )}
      {dialog?.kind === "versions" && (
        <Modal title={`History of ${name}`} onClose={() => setDialog(null)} wide actions={<button className="btn" onClick={() => setDialog(null)}>Close</button>}>
          {dialog.list.length === 0 ? <p className="sub">No earlier versions yet. Each save keeps the previous compose.yaml here (last 20).</p> : (
            <ul className="dk-versions">
              {dialog.list.map((v) => (
                <li key={v.id}><span className="mono">{v.ts.replace("T", " ").replace("Z", " UTC")}</span>
                  <button className="btn sm" onClick={() => setDialog({ kind: "restore", v })}>Compare and restore</button></li>
              ))}
            </ul>
          )}
        </Modal>
      )}
      {dialog?.kind === "restore" && (
        <Modal title="Restore this version?" onClose={() => setDialog(null)} wide actions={<>
          <button className="btn" onClick={() => setDialog(null)}>Back</button>
          <button className="btn pri" onClick={() => { setCompose(dialog.v.content); setGen((g) => g + 1); setTab("compose"); setDialog(null); toast("Restored into the editor. Save to apply."); }}>Put it in the editor</button>
        </>}>
          <p className="sub">Changes from the editor to the version of {dialog.v.ts.replace("T", " ").replace("Z", " UTC")}:</p>
          <DiffView lines={diffLines(compose, dialog.v.content)} />
        </Modal>
      )}
    </Modal>
  );
}
