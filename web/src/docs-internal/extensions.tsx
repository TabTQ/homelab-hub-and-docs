import { fmtBytes } from "../shared/util";
import { mergeAttributes, Node } from "@tiptap/core";
import { NodeViewContent, NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from "@tiptap/react";
import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { assetUrl } from "./docsApi";
import { EmbedPreview } from "./internalRender";
import { mermaidError, renderMermaid } from "./mermaidRender";
import type { TreeItem } from "./treeModel";

/** What the node views need from the editor page: the tree (titles of linked pages) and the current theme. */
export const EditorEnv = createContext<{ items: TreeItem[]; theme: "light" | "dark" }>({ items: [], theme: "light" });

// The custom nodes below match docs-internal's DocSchema exactly: same names, same attributes.

function CalloutView({ node, updateAttributes }: NodeViewProps) {
  return (
    <NodeViewWrapper className={`dx-callout ${node.attrs.type}`}>
      <select contentEditable={false} className="dx-callout-type" value={node.attrs.type} aria-label="Callout type"
        onChange={(e) => updateAttributes({ type: e.target.value })}>
        <option value="info">Note</option><option value="tip">Tip</option><option value="warning">Warning</option><option value="danger">Danger</option>
      </select>
      <NodeViewContent />
    </NodeViewWrapper>
  );
}

export const Callout = Node.create({
  name: "callout",
  group: "block",
  content: "block+",
  defining: true,
  addAttributes() { return { type: { default: "info" } }; },
  parseHTML() { return [{ tag: "div[data-callout]" }]; },
  renderHTML({ HTMLAttributes }) { return ["div", mergeAttributes({ "data-callout": "" }, HTMLAttributes), 0]; },
  addNodeView() { return ReactNodeViewRenderer(CalloutView); },
});

function ImageView({ node, updateAttributes, selected }: NodeViewProps) {
  const a = node.attrs;
  return (
    <NodeViewWrapper className={`dx-figure dx-edit-block${selected ? " sel" : ""}`} data-drag-handle>
      <div className="dx-img"><img src={assetUrl(a.sha)} alt={a.alt ?? ""} style={a.width ? { width: `${a.width}%` } : undefined} /></div>
      <div className="dx-imgmeta" contentEditable={false}>
        <input type="text" placeholder="Caption" value={a.caption ?? ""} onChange={(e) => updateAttributes({ caption: e.target.value })} aria-label="Caption" />
        <input type="text" placeholder="Alt text" value={a.alt ?? ""} onChange={(e) => updateAttributes({ alt: e.target.value })} aria-label="Alt text" />
        <select value={a.width ?? ""} onChange={(e) => updateAttributes({ width: e.target.value ? Number(e.target.value) : null })} aria-label="Width">
          <option value="">Full width</option><option value="75">75%</option><option value="50">50%</option><option value="33">33%</option>
        </select>
      </div>
    </NodeViewWrapper>
  );
}

export const Image = Node.create({
  name: "image",
  group: "block",
  atom: true,
  draggable: true,
  addAttributes() { return { sha: { default: null }, alt: { default: "" }, caption: { default: "" }, width: { default: null } }; },
  parseHTML() { return [{ tag: "figure[data-image]", getAttrs: (el) => ({ sha: (el as HTMLElement).dataset.image }) }]; },
  renderHTML({ node }) { return ["figure", { "data-image": node.attrs.sha }]; },
  addNodeView() { return ReactNodeViewRenderer(ImageView); },
});

function AttachmentView({ node, selected }: NodeViewProps) {
  const a = node.attrs;
  const ext = String(a.name ?? "").split(".").pop()?.slice(0, 4).toUpperCase() || "FILE";
  return (
    <NodeViewWrapper className={`dx-attach dx-edit-block${selected ? " sel" : ""}`} data-drag-handle>
      <span className="ic">{ext}</span><span><b>{a.name}</b><br /><span className="hint">{fmtBytes(a.size ?? 0)}</span></span>
    </NodeViewWrapper>
  );
}

export const Attachment = Node.create({
  name: "attachment",
  group: "block",
  atom: true,
  draggable: true,
  addAttributes() { return { sha: { default: null }, name: { default: "file" }, size: { default: 0 } }; },
  parseHTML() { return [{ tag: "div[data-attachment]" }]; },
  renderHTML({ node }) { return ["div", { "data-attachment": node.attrs.sha }]; },
  addNodeView() { return ReactNodeViewRenderer(AttachmentView); },
});

function MermaidView({ node, updateAttributes, selected }: NodeViewProps) {
  const { theme } = useContext(EditorEnv);
  const [svg, setSvg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const source: string = node.attrs.source ?? "";
  useEffect(() => {
    let live = true;
    const t = setTimeout(() => {
      renderMermaid(source, theme).then((s) => { if (live) { setSvg(s); setErr(null); } }).catch((e) => { if (live) setErr(mermaidError(e)); });
    }, 400);
    return () => { live = false; clearTimeout(t); };
  }, [source, theme]);
  // The preview is shown as an image of the SVG, exactly as the saved page will show it (never inlined: D13).
  const url = useMemo(() => (svg ? URL.createObjectURL(new Blob([svg], { type: "image/svg+xml" })) : null), [svg]);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);
  return (
    <NodeViewWrapper className={`dx-mermaid-edit dx-edit-block${selected ? " sel" : ""}`}>
      <div className="dx-caption" contentEditable={false}>Mermaid diagram</div>
      <div className="dx-mermaid-grid" contentEditable={false}>
        <textarea spellCheck={false} value={source} aria-label="Mermaid source"
          onChange={(e) => updateAttributes({ source: e.target.value, lightSvg: null, darkSvg: null })} />
        <div className="pv">{err ? <pre className="dx-err">{err}</pre> : url ? <img src={url} alt="Diagram preview" /> : <span className="hint">Rendering…</span>}</div>
      </div>
    </NodeViewWrapper>
  );
}

export const Mermaid = Node.create({
  name: "mermaid",
  group: "block",
  atom: true,
  draggable: true,
  addAttributes() { return { source: { default: "" }, lightSvg: { default: null }, darkSvg: { default: null } }; },
  parseHTML() { return [{ tag: "div[data-mermaid]" }]; },
  renderHTML() { return ["div", { "data-mermaid": "" }]; },
  addNodeView() { return ReactNodeViewRenderer(MermaidView); },
});

function PageLinkView({ node }: NodeViewProps) {
  const { items } = useContext(EditorEnv);
  const t = items.find((i) => i.id === Number(node.attrs.pageId));
  const title = !t ? "This page no longer exists: visitors see plain text." : t.state !== "internal" && t.publicHost
    ? `Public: visitors go to https://${t.publicHost}/` : "Internal page: visitors see plain text.";
  return <NodeViewWrapper as="span" className={t ? "dx-xlink" : "dx-xtext"} title={title}>{node.attrs.label}</NodeViewWrapper>;
}

export const PageLink = Node.create({
  name: "pageLink",
  group: "inline",
  inline: true,
  atom: true,
  addAttributes() { return { pageId: { default: null }, label: { default: "" } }; },
  parseHTML() { return [{ tag: "span[data-page-link]" }]; },
  renderHTML({ node }) { return ["span", { "data-page-link": node.attrs.pageId }, node.attrs.label]; },
  renderText({ node }) { return node.attrs.label; },
  addNodeView() { return ReactNodeViewRenderer(PageLinkView); },
});

function WebappEmbedView({ node, updateAttributes, selected }: NodeViewProps) {
  const { items, theme } = useContext(EditorEnv);
  const t = items.find((i) => i.id === Number(node.attrs.pageId));
  return (
    <NodeViewWrapper className={`dx-embed dx-edit-block${selected ? " sel" : ""}`} data-drag-handle>
      <div className="eh" contentEditable={false}><span className="dx-pill kapp">app</span><span className="grow">{t ? t.title : "Missing webapp page"}</span>
        <label className="hint">Height <input type="number" min={150} max={4000} placeholder="page setting" value={node.attrs.height ?? ""}
          onChange={(e) => updateAttributes({ height: e.target.value ? Number(e.target.value) : null })} style={{ width: 110 }} /></label>
      </div>
      <div contentEditable={false}>{t ? <EmbedPreview pageId={t.id} title={t.title} height={node.attrs.height} theme={theme} /> : <div className="hint dx-pad">Choose another webapp page.</div>}</div>
    </NodeViewWrapper>
  );
}

export const WebappEmbed = Node.create({
  name: "webappEmbed",
  group: "block",
  atom: true,
  draggable: true,
  addAttributes() { return { pageId: { default: null }, height: { default: null } }; },
  parseHTML() { return [{ tag: "div[data-webapp-embed]" }]; },
  renderHTML({ node }) { return ["div", { "data-webapp-embed": node.attrs.pageId }]; },
  addNodeView() { return ReactNodeViewRenderer(WebappEmbedView); },
});
