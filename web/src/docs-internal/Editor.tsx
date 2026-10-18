import { Details, DetailsContent, DetailsSummary } from "@tiptap/extension-details";
import { CodeBlockLowlight } from "@tiptap/extension-code-block-lowlight";
import { TaskItem, TaskList } from "@tiptap/extension-list";
import { Table, TableCell, TableHeader, TableRow } from "@tiptap/extension-table";
import { Placeholder } from "@tiptap/extensions";
import { EditorContent, useEditor, useEditorState, type Editor as TipTap } from "@tiptap/react";
import { StarterKit } from "@tiptap/starter-kit";
import { common, createLowlight } from "lowlight";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError, upload } from "../shared/api";
import { useToast } from "../shared/components/Toast";
import { safeHref } from "../shared/docs/safe";
import { useCurrentTheme } from "../shared/docs/theme";
import type { PMNode } from "../shared/docs/types";
import { Sheet } from "./Dialogs";
import { A, ago, go, loadPage, type DocsConfig, type PageDetail } from "./docsApi";
import { Attachment, Callout, EditorEnv, Image, Mermaid, PageLink, WebappEmbed } from "./extensions";
import { mermaidError, renderMermaid } from "./mermaidRender";
import { filterSlash, type SlashId, type SlashItem } from "./slash";
import type { TreeItem } from "./treeModel";

const lowlight = createLowlight(common);
const LOCAL_KEY = (id: number) => `docs-draft-${id}`;

interface LocalDraft { version: number; title: string; body: PMNode; at: string }

function readLocal(id: number): LocalDraft | null {
  try { return JSON.parse(localStorage.getItem(LOCAL_KEY(id)) ?? "null"); } catch { return null; }
}
function writeLocal(id: number, d: LocalDraft | null) {
  try { if (d) localStorage.setItem(LOCAL_KEY(id), JSON.stringify(d)); else localStorage.removeItem(LOCAL_KEY(id)); } catch { /* storage full or blocked */ }
}

/**
 * Before saving: every Mermaid diagram whose SVGs are missing (new or edited) is rendered in light and dark and stored as
 * two files, so the public page shows images and never loads Mermaid (D13).
 */
async function withDiagrams(body: PMNode): Promise<PMNode> {
  let n = 0;
  const walk = async (node: PMNode): Promise<PMNode> => {
    if (node.type === "mermaid") {
      n++;
      const a = node.attrs ?? {};
      if (a.lightSvg && a.darkSvg) return node;
      const source = String(a.source ?? "").trim();
      if (!source) return { ...node, attrs: { ...a, lightSvg: null, darkSvg: null } };
      try {
        const [light, dark] = [await renderMermaid(source, "light"), await renderMermaid(source, "dark")];
        const up = async (svg: string, which: string) =>
          (await upload<{ sha256: string }>(`${A}/assets`, new Blob([svg], { type: "image/svg+xml" }), `diagram-${n}-${which}.svg`)).sha256;
        return { ...node, attrs: { ...a, lightSvg: await up(light, "light"), darkSvg: await up(dark, "dark") } };
      } catch (e) {
        if (e instanceof ApiError) throw e;
        throw new Error(`Diagram ${n} has an error: ${mermaidError(e)}`);
      }
    }
    if (!node.content) return node;
    const content: PMNode[] = [];
    for (const c of node.content) content.push(await walk(c));
    return { ...node, content };
  };
  return walk(body);
}

/** Where the "/" menu is: the text range to replace and where to draw it. */
interface SlashState { query: string; from: number; to: number; left: number; top: number }

/** The WYSIWYG editor (mockups tab 2). Save stores a draft version and never changes the public page. */
export default function Editor({ id, items, config, onChanged, fail }: {
  id: number; items: TreeItem[]; config: DocsConfig | null; onChanged: () => void; fail: (e: any) => void;
}) {
  const [page, setPage] = useState<PageDetail | null>(null);
  const [title, setTitle] = useState("");
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [local, setLocal] = useState<LocalDraft | null>(null);
  const [conflict, setConflict] = useState(false);
  const [picker, setPicker] = useState<null | "pagelink" | "webapp">(null);
  const [slash, setSlash] = useState<SlashState | null>(null);
  const [slashSel, setSlashSel] = useState(0);
  const [dropping, setDropping] = useState(false);
  const toast = useToast();
  const theme = useCurrentTheme();
  const imageInput = useRef<HTMLInputElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const slashRef = useRef<{ s: SlashState | null; items: SlashItem[]; sel: number }>({ s: null, items: [], sel: 0 });
  const runSlashRef = useRef<(id: SlashId) => void>(() => {});
  const dirtyRef = useRef(false);
  dirtyRef.current = dirty;

  const uploadFiles = useCallback(async (files: File[], ed: TipTap | null, pos?: number) => {
    for (const f of files) {
      try {
        const r = await upload<{ sha256: string; mime: string; size: number; name: string }>(`${A}/assets`, f, f.name);
        const node = r.mime.startsWith("image/") ? { type: "image", attrs: { sha: r.sha256, alt: f.name.replace(/\.[^.]+$/, ""), caption: "" } }
          : { type: "attachment", attrs: { sha: r.sha256, name: r.name, size: r.size } };
        if (pos !== undefined) ed?.chain().focus().insertContentAt(pos, node).run(); else ed?.chain().focus().insertContent([node, { type: "paragraph" }]).run();
      } catch (e: any) {
        toast(`${f.name}: ${e?.message ?? e}`);
      }
    }
  }, [toast]);

  const editor = useEditor({
    extensions: [
      StarterKit.configure({ heading: { levels: [1, 2, 3, 4] }, codeBlock: false, link: { openOnClick: false, autolink: true, protocols: ["mailto"], isAllowedUri: (u) => safeHref(u) !== null } }),
      CodeBlockLowlight.configure({ lowlight, defaultLanguage: null }),
      Table.configure({ resizable: false }), TableRow, TableHeader, TableCell,
      TaskList, TaskItem.configure({ nested: true }),
      Details.configure({ persist: true }), DetailsSummary, DetailsContent,
      Placeholder.configure({ placeholder: "Type / for blocks, or just start writing…" }),
      Callout, Image, Attachment, Mermaid, PageLink, WebappEmbed,
    ],
    content: null,
    editorProps: {
      attributes: { class: "doc dx-prose", "aria-label": "Page content" },
      handleKeyDown: (_view, e) => {
        const st = slashRef.current;
        if (!st.s || !st.items.length) return false;
        if (e.key === "ArrowDown") { setSlashSel((s) => Math.min(s + 1, st.items.length - 1)); return true; }
        if (e.key === "ArrowUp") { setSlashSel((s) => Math.max(s - 1, 0)); return true; }
        if (e.key === "Enter" || e.key === "Tab") { runSlashRef.current(st.items[st.sel].id); return true; }
        if (e.key === "Escape") { setSlash(null); return true; }
        return false;
      },
      handlePaste: (_view, e) => {
        const files = [...(e.clipboardData?.files ?? [])];
        if (!files.length) return false;
        uploadFiles(files, editorRef.current);
        return true;
      },
      handleDrop: (view, e) => {
        const files = [...(e.dataTransfer?.files ?? [])];
        if (!files.length) return false;
        const pos = view.posAtCoords({ left: e.clientX, top: e.clientY })?.pos;
        uploadFiles(files, editorRef.current, pos);
        setDropping(false);
        return true;
      },
    },
    onUpdate: () => setDirty(true),
  });
  const editorRef = useRef<TipTap | null>(null);
  editorRef.current = editor;

  // Load the page, and offer a newer local copy if one was left behind (closed tab, lost connection).
  useEffect(() => {
    loadPage(id).then((p) => {
      if (p.kind !== "rich") { go(`#/p/${id}`); return; }
      setPage(p);
      setTitle(p.title);
      editor?.commands.setContent(p.body as any, { emitUpdate: false });
      const l = readLocal(id);
      if (l && (JSON.stringify(l.body) !== JSON.stringify(editor?.getJSON()) || l.title !== p.title)) setLocal(l); else writeLocal(id, null);
    }).catch(fail);
  }, [id, editor]); // eslint-disable-line react-hooks/exhaustive-deps

  // Local autosave every few seconds while there are unsaved changes.
  useEffect(() => {
    if (!dirty || !page || !editor) return;
    const t = setTimeout(() => writeLocal(id, { version: page.version, title, body: editor.getJSON() as PMNode, at: new Date().toISOString() }), 2500);
    return () => clearTimeout(t);
  }, [dirty, title, page, editor, id]);

  useEffect(() => {
    const h = (e: BeforeUnloadEvent) => { if (dirtyRef.current) { e.preventDefault(); e.returnValue = ""; } };
    window.addEventListener("beforeunload", h);
    return () => window.removeEventListener("beforeunload", h);
  }, []);

  // The "/" menu: open while the text before the cursor ends with "/word" at the start of a word.
  useEffect(() => {
    if (!editor) return;
    const check = () => {
      const { state, view } = editor;
      const { $from, empty } = state.selection;
      if (!empty || !$from.parent.isTextblock || $from.parent.type.name === "codeBlock") { setSlash(null); return; }
      const before = $from.parent.textBetween(0, $from.parentOffset, "\0", "\0");
      const m = /(?:^|\s)\/([^\s/]{0,24})$/.exec(before);
      if (!m) { setSlash(null); return; }
      const c = view.coordsAtPos($from.pos);
      setSlash((old) => { if (!old || old.query !== m[1]) setSlashSel(0); return { query: m[1], from: $from.pos - m[1].length - 1, to: $from.pos, left: c.left, top: c.bottom }; });
    };
    editor.on("transaction", check);
    return () => { editor.off("transaction", check); };
  }, [editor]);
  const slashItems = useMemo(() => (slash ? filterSlash(slash.query) : []), [slash]);
  slashRef.current = { s: slash, items: slashItems, sel: Math.min(slashSel, Math.max(0, slashItems.length - 1)) };

  const runSlash = (sid: SlashId) => {
    if (!editor) return;
    const s = slashRef.current.s;
    let c = editor.chain().focus();
    if (s) c = c.deleteRange({ from: s.from, to: s.to });
    setSlash(null);
    switch (sid) {
      case "h2": case "h3": case "h4": c.setNode("heading", { level: Number(sid[1]) }).run(); break;
      case "bullet": c.toggleBulletList().run(); break;
      case "ordered": c.toggleOrderedList().run(); break;
      case "task": c.toggleTaskList().run(); break;
      case "quote": c.toggleBlockquote().run(); break;
      case "rule": c.setHorizontalRule().run(); break;
      case "table": c.insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(); break;
      case "code": c.toggleCodeBlock().run(); break;
      case "callout": c.insertContent({ type: "callout", attrs: { type: "info" }, content: [{ type: "paragraph" }] }).run(); break;
      // Block atoms are followed by an empty paragraph: otherwise the new node stays selected and the next insert replaces it.
      case "mermaid": c.insertContent([{ type: "mermaid", attrs: { source: "flowchart TD\n  A[Request] --> B{Host known?}\n  B -- yes --> C[Serve page]\n  B -- no --> D[404]" } }, { type: "paragraph" }]).run(); break;
      case "details": c.setDetails().run(); break;
      case "image": c.run(); imageInput.current?.click(); break;
      case "attachment": c.run(); fileInput.current?.click(); break;
      case "webapp": c.run(); setPicker("webapp"); break;
      case "pagelink": c.run(); setPicker("pagelink"); break;
    }
  };
  runSlashRef.current = runSlash;

  const setLink = () => {
    if (!editor) return;
    const prev = editor.getAttributes("link").href ?? "https://";
    const href = window.prompt("Link address (https://…, http://… or mailto:…). Leave empty to remove the link.", prev);
    if (href === null) return;
    if (!href.trim()) { editor.chain().focus().extendMarkRange("link").unsetLink().run(); return; }
    const ok = safeHref(href);
    if (!ok) { toast("Links must start with https://, http:// or mailto:."); return; }
    editor.chain().focus().extendMarkRange("link").setLink({ href: ok }).run();
  };

  const save = async (opts: { force?: boolean; then?: () => void } = {}) => {
    if (!editor || !page || saving) return;
    setSaving(true);
    try {
      const body = await withDiagrams(editor.getJSON() as PMNode);
      const version = opts.force ? (await loadPage(id)).version : page.version;
      const r = await api(`${A}/pages/${id}`, { method: "PUT", body: { title: title.trim(), body, version } });
      editor.commands.setContent(body as any, { emitUpdate: false });
      setPage({ ...page, version: r.version, title: title.trim(), body });
      setDirty(false);
      setSavedAt(new Date().toISOString());
      writeLocal(id, null);
      setLocal(null);
      onChanged();
      opts.then?.();
    } catch (e: any) {
      if (e?.status === 409) setConflict(true); else fail(e);
    } finally {
      setSaving(false);
    }
  };

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") { e.preventDefault(); save(); } };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  });

  const cancel = () => {
    if (dirty && !window.confirm("Discard the unsaved changes? (A local copy is kept until you save.)")) return;
    setDirty(false);
    go(`#/p/${id}`);
  };

  const active = useEditorState({
    editor,
    selector: ({ editor: e }) => e ? {
      bold: e.isActive("bold"), italic: e.isActive("italic"), underline: e.isActive("underline"), strike: e.isActive("strike"), code: e.isActive("code"),
      link: e.isActive("link"), bullet: e.isActive("bulletList"), ordered: e.isActive("orderedList"), task: e.isActive("taskList"), table: e.isActive("table"),
      block: e.isActive("heading", { level: 1 }) ? "h1" : e.isActive("heading", { level: 2 }) ? "h2" : e.isActive("heading", { level: 3 }) ? "h3"
        : e.isActive("heading", { level: 4 }) ? "h4" : e.isActive("codeBlock") ? "code" : "p",
      canUndo: e.can().undo(), canRedo: e.can().redo(),
    } : null,
  });

  if (!page || !editor) return <p className="hint">Loading the editor…</p>;
  const tb = (label: string, on: boolean | undefined, run: () => void, title?: string) =>
    <button type="button" className={`tb${on ? " on" : ""}`} onMouseDown={(e) => e.preventDefault()} onClick={run} title={title ?? label} aria-pressed={on}>{label}</button>;
  const chain = () => editor.chain().focus();

  return (
    <EditorEnv.Provider value={{ items, theme }}>
      <div className="dx-page dx-editing">
        <div className="dx-editbar">
          <span className="hint">{saving ? "Saving…" : dirty ? "Editing · unsaved changes" : savedAt ? `Saved ${ago(savedAt)}` : "Editing"}</span>
          <span className="grow" />
          <button className="btn sm" onClick={cancel}>{dirty ? "Cancel" : "Done"}</button>
          <button className="btn sm" onClick={() => save()} disabled={saving}>Save draft</button>
          <button className="btn sm pri" onClick={() => save({ then: () => go(`#/p/${id}/publish`) })} disabled={saving}>Publish…</button>
        </div>
        {local && (
          <div className="dx-banner">A local copy from {ago(local.at)} has changes that were never saved.{" "}
            <button className="linkbtn" onClick={() => { editor.commands.setContent(local.body as any); setTitle(local.title); setDirty(true); setLocal(null); }}>Restore it</button> ·{" "}
            <button className="linkbtn" onClick={() => { writeLocal(id, null); setLocal(null); }}>Discard it</button>
          </div>
        )}
        <div className="dx-toolbar" role="toolbar" aria-label="Formatting">
          <select className="tb" value={active?.block ?? "p"} aria-label="Block type" onChange={(e) => {
            const v = e.target.value;
            if (v === "p") chain().setParagraph().run(); else if (v === "code") chain().toggleCodeBlock().run();
            else chain().setNode("heading", { level: Number(v[1]) }).run();
          }}>
            <option value="p">Paragraph</option><option value="h1">Heading 1</option><option value="h2">Heading 2</option><option value="h3">Heading 3</option><option value="h4">Heading 4</option><option value="code">Code</option>
          </select>
          <span className="sep" />
          {tb("B", active?.bold, () => chain().toggleBold().run(), "Bold (Ctrl B)")}
          {tb("I", active?.italic, () => chain().toggleItalic().run(), "Italic (Ctrl I)")}
          {tb("U", active?.underline, () => chain().toggleUnderline().run(), "Underline (Ctrl U)")}
          {tb("S", active?.strike, () => chain().toggleStrike().run(), "Strikethrough")}
          {tb("</>", active?.code, () => chain().toggleCode().run(), "Inline code")}
          {tb("🔗", active?.link, setLink, "Link")}
          <span className="sep" />
          {tb("• List", active?.bullet, () => chain().toggleBulletList().run())}
          {tb("1. List", active?.ordered, () => chain().toggleOrderedList().run())}
          {tb("☑", active?.task, () => chain().toggleTaskList().run(), "Checklist")}
          <span className="sep" />
          {tb("▦ Table", false, () => chain().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run())}
          {tb("🖼 Image", false, () => imageInput.current?.click())}
          {tb("{ } Code", false, () => chain().toggleCodeBlock().run())}
          {tb("ⓘ Callout", false, () => runSlash("callout"))}
          {tb("◇ Diagram", false, () => runSlash("mermaid"))}
          {tb("▸ Toggle", false, () => chain().setDetails().run())}
          {tb("📎 File", false, () => fileInput.current?.click())}
          {tb("⧉ Webapp", false, () => setPicker("webapp"))}
          {tb("↗ Page", false, () => setPicker("pagelink"), "Link to a page")}
          <span className="grow" />
          {tb("↶", false, () => chain().undo().run(), "Undo (Ctrl Z)")}
          {tb("↷", false, () => chain().redo().run(), "Redo (Ctrl Shift Z)")}
        </div>
        {active?.table && (
          <div className="dx-tabletools" role="toolbar" aria-label="Table">
            <button onMouseDown={(e) => e.preventDefault()} onClick={() => chain().addRowAfter().run()}>＋ row</button>
            <button onMouseDown={(e) => e.preventDefault()} onClick={() => chain().addColumnAfter().run()}>＋ col</button>
            <button onMouseDown={(e) => e.preventDefault()} onClick={() => chain().deleteRow().run()}>− row</button>
            <button onMouseDown={(e) => e.preventDefault()} onClick={() => chain().deleteColumn().run()}>− col</button>
            <button onMouseDown={(e) => e.preventDefault()} onClick={() => chain().toggleHeaderRow().run()}>header</button>
            <button onMouseDown={(e) => e.preventDefault()} onClick={() => chain().mergeOrSplit().run()}>merge / split</button>
            <button onMouseDown={(e) => e.preventDefault()} onClick={() => chain().deleteTable().run()} aria-label="Delete table">🗑</button>
          </div>
        )}
        <input className="dx-titlein" value={title} maxLength={200} onChange={(e) => { setTitle(e.target.value); setDirty(true); }} aria-label="Title" placeholder="Title" />
        <div className={`dx-editor${dropping ? " dropping" : ""}`} onDragEnter={(e) => { if (e.dataTransfer.types.includes("Files")) setDropping(true); }}
          onDragLeave={(e) => { if (e.currentTarget === e.target) setDropping(false); }}>
          {dropping && <div className="dx-dropzone">Drop to upload · images up to {config?.maxAssetMb ?? 20} MB · stored once, even if used on several pages</div>}
          <EditorContent editor={editor} />
        </div>
        {slash && slashItems.length > 0 && (
          <div className="dx-slash" role="listbox" aria-label="Insert a block" style={{ left: Math.min(slash.left, window.innerWidth - 320), top: Math.min(slash.top + 4, window.innerHeight - 340) }}>
            {slashItems.map((it, i) => (
              <div key={it.id}>
                {(i === 0 || slashItems[i - 1].group !== it.group) && <div className="q">{it.group}</div>}
                <button role="option" aria-selected={i === slashRef.current.sel} className={`it${i === slashRef.current.sel ? " on" : ""}`}
                  onMouseDown={(e) => e.preventDefault()} onMouseMove={() => setSlashSel(i)} onClick={() => runSlash(it.id)}>
                  <span className="ico">{it.icon}</span><span>{it.label}{it.hint && <small>{it.hint}</small>}</span>
                </button>
              </div>
            ))}
          </div>
        )}
        <div className="dx-savebar">
          <span className="hint">{local ? "Local copy kept" : dirty ? "Unsaved changes are kept in this browser until you save" : "No unsaved changes"} · v{page.latestVersion}</span>
          <span className="grow" />
          <button className="btn" onClick={cancel}>Cancel</button>
          <button className="btn" onClick={() => save()} disabled={saving}>Save draft</button>
          <button className="btn pri" onClick={() => save({ then: () => go(`#/p/${id}/publish`) })} disabled={saving}>Publish…</button>
        </div>
        <input ref={imageInput} type="file" accept="image/png,image/jpeg,image/webp,image/gif,image/svg+xml" hidden multiple
          onChange={(e) => { uploadFiles([...(e.target.files ?? [])], editor); e.target.value = ""; }} />
        <input ref={fileInput} type="file" hidden multiple onChange={(e) => { uploadFiles([...(e.target.files ?? [])], editor); e.target.value = ""; }} />
        {picker && <PagePicker items={items} currentId={id} webappsOnly={picker === "webapp"} onClose={() => setPicker(null)} onPick={(t) => {
          setPicker(null);
          if (picker === "webapp") editor.chain().focus().insertContent([{ type: "webappEmbed", attrs: { pageId: t.id, height: null } }, { type: "paragraph" }]).run();
          else editor.chain().focus().insertContent([{ type: "pageLink", attrs: { pageId: t.id, label: t.title } }, { type: "text", text: " " }]).run();
        }} />}
        {conflict && (
          <Sheet title="Changed elsewhere" onClose={() => setConflict(false)}>
            <p>This page was saved from another tab or device after you opened it.</p>
            <div className="mrow dx-acts">
              <button className="btn" onClick={() => setConflict(false)}>Cancel</button>
              <button className="btn" onClick={() => { setConflict(false); setDirty(false); loadPage(id).then((p) => { setPage(p); setTitle(p.title); editor.commands.setContent(p.body as any, { emitUpdate: false }); }); }}>Load theirs (discard mine)</button>
              <button className="btn pri" onClick={() => { setConflict(false); save({ force: true }); }}>Keep mine (save over it)</button>
            </div>
            <p className="hint">Either way the other version stays in the page history.</p>
          </Sheet>
        )}
      </div>
    </EditorEnv.Provider>
  );
}

function PagePicker({ items, currentId, webappsOnly, onClose, onPick }: {
  items: TreeItem[]; currentId: number; webappsOnly: boolean; onClose: () => void; onPick: (t: TreeItem) => void;
}) {
  const [q, setQ] = useState("");
  const shown = items.filter((i) => i.id !== currentId && (!webappsOnly || i.kind === "webapp"))
    .filter((i) => q.toLowerCase().split(/\s+/).filter(Boolean).every((w) => i.title.toLowerCase().includes(w))).slice(0, 50);
  return (
    <Sheet title={webappsOnly ? "Embed a webapp page" : "Link to a page"} onClose={onClose}>
      <div className="field"><input type="text" autoFocus placeholder="Search page titles" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search pages"
        onKeyDown={(e) => { if (e.key === "Enter" && shown[0]) onPick(shown[0]); }} /></div>
      <div className="dx-picklist">
        {shown.length === 0 && <p className="hint">{webappsOnly ? "No webapp pages yet. Create one from the tree (⋯ → Add webapp page)." : "No matching page."}</p>}
        {shown.map((t) => (
          <button key={t.id} onClick={() => onPick(t)}>
            <i className={`dx-dot${t.state === "public" ? " pub" : t.state === "public-changed" ? " draft" : ""}`} /> {t.title}
            <span className="hint">{t.state !== "internal" && t.publicHost ? t.publicHost : "internal: plain text for visitors"}</span>
          </button>
        ))}
      </div>
    </Sheet>
  );
}
