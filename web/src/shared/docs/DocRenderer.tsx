import { fmtBytes } from "../util";
import { createElement, Fragment, useEffect, useState, type ReactNode } from "react";
import { safeHref, slugger, textOf } from "./safe";
import type { PMMark, PMNode } from "./types";

/**
 * JSON → React for the whitelisted node types (D8). Used by the editor's reading view and by the public page, so both
 * look the same. There is no HTML anywhere: every node becomes a React element, unknown nodes are dropped, and links
 * keep only http, https and mailto targets. (Never inject raw HTML here: DocRenderer.test checks the Docs sources for it.)
 */
export interface RenderContext {
  /** URL of a stored file (image, attachment, diagram). */
  assetUrl: (sha: string) => string;
  dark: boolean;
  /** A link to another page. Internal: a hash route. Public: the resolved hostname, or null for plain text (D6). */
  pageLink: (attrs: Record<string, any>) => { href: string | null; title?: string; external?: boolean };
  /** An embedded webapp. */
  embed: (attrs: Record<string, any>, key: string) => ReactNode;
}

const CALLOUT_LABEL: Record<string, string> = { info: "Note", tip: "Tip", warning: "Warning", danger: "Danger" };

export function DocRenderer({ doc, ctx }: { doc: PMNode | null | undefined; ctx: RenderContext }) {
  if (!doc || doc.type !== "doc") return null;
  const slug = slugger();
  return <>{(doc.content ?? []).map((n, i) => renderNode(n, ctx, `n${i}`, slug))}</>;
}

/** The headings of a document, for "On this page". Ids match the ones DocRenderer puts on the headings. */
export function headings(doc: PMNode | null | undefined): { id: string; text: string; level: number }[] {
  const slug = slugger();
  const out: { id: string; text: string; level: number }[] = [];
  const walk = (n: PMNode) => {
    if (n.type === "heading") {
      const text = textOf(n);
      out.push({ id: slug(text), text, level: Number(n.attrs?.level ?? 2) });
      return;
    }
    (n.content ?? []).forEach(walk);
  };
  if (doc) (doc.content ?? []).forEach(walk);
  return out;
}

function kids(n: PMNode, ctx: RenderContext, key: string, slug: (t: string) => string): ReactNode[] {
  return (n.content ?? []).map((c, i) => renderNode(c, ctx, `${key}.${i}`, slug));
}

function renderText(n: PMNode, key: string): ReactNode {
  let el: ReactNode = n.text ?? "";
  const marks: PMMark[] = Array.isArray(n.marks) ? n.marks : [];
  for (const m of marks) {
    switch (m.type) {
      case "bold": el = <strong>{el}</strong>; break;
      case "italic": el = <em>{el}</em>; break;
      case "underline": el = <u>{el}</u>; break;
      case "strike": el = <s>{el}</s>; break;
      case "code": el = <code>{el}</code>; break;
      case "link": {
        const href = safeHref(m.attrs?.href);
        if (href) el = <a href={href} target="_blank" rel="noopener noreferrer nofollow">{el}</a>;
        break;
      }
      default: break; // unknown marks are ignored, the text stays
    }
  }
  return <Fragment key={key}>{el}</Fragment>;
}

function renderNode(n: PMNode, ctx: RenderContext, key: string, slug: (t: string) => string): ReactNode {
  if (!n || typeof n !== "object") return null;
  const a = n.attrs ?? {};
  switch (n.type) {
    case "text": return renderText(n, key);
    case "paragraph": return <p key={key}>{kids(n, ctx, key, slug)}</p>;
    case "heading": {
      const level = Math.min(4, Math.max(1, Number(a.level) || 2));
      return createElement(`h${level}`, { key, id: slug(textOf(n)) }, ...kids(n, ctx, key, slug));
    }
    case "bulletList": return <ul key={key}>{kids(n, ctx, key, slug)}</ul>;
    case "orderedList": return <ol key={key} start={Number.isInteger(a.start) ? a.start : undefined}>{kids(n, ctx, key, slug)}</ol>;
    case "listItem": return <li key={key}>{kids(n, ctx, key, slug)}</li>;
    case "taskList": return <ul key={key} className="dx-tasks">{kids(n, ctx, key, slug)}</ul>;
    case "taskItem": return (
      <li key={key} className={a.checked ? "done" : undefined}>
        <input type="checkbox" checked={!!a.checked} disabled readOnly aria-label={a.checked ? "done" : "not done"} />
        <div>{kids(n, ctx, key, slug)}</div>
      </li>
    );
    case "blockquote": return <blockquote key={key}>{kids(n, ctx, key, slug)}</blockquote>;
    case "horizontalRule": return <hr key={key} />;
    case "hardBreak": return <br key={key} />;
    case "codeBlock": return <CodeBlock key={key} code={textOf(n)} language={typeof a.language === "string" ? a.language : ""} />;
    case "table": return (
      <div key={key} className="dx-tablewrap"><table><tbody>{kids(n, ctx, key, slug)}</tbody></table></div>
    );
    case "tableRow": return <tr key={key}>{kids(n, ctx, key, slug)}</tr>;
    case "tableHeader":
    case "tableCell": {
      const props = { key, colSpan: a.colspan > 1 ? a.colspan : undefined, rowSpan: a.rowspan > 1 ? a.rowspan : undefined };
      return n.type === "tableHeader" ? <th {...props}>{kids(n, ctx, key, slug)}</th> : <td {...props}>{kids(n, ctx, key, slug)}</td>;
    }
    case "image": {
      if (typeof a.sha !== "string") return null;
      const width = Number.isInteger(a.width) ? `${a.width}%` : undefined;
      return (
        <figure key={key} className="dx-figure">
          <div className="dx-img"><img src={ctx.assetUrl(a.sha)} alt={a.alt ?? ""} style={width ? { width } : undefined} loading="lazy" /></div>
          {a.caption ? <figcaption>{a.caption}</figcaption> : null}
        </figure>
      );
    }
    case "callout": {
      const type = ["info", "tip", "warning", "danger"].includes(a.type) ? a.type : "info";
      return (
        <div key={key} className={`dx-callout ${type}`} role="note">
          <span className="dx-callout-label">{CALLOUT_LABEL[type]}</span>
          {kids(n, ctx, key, slug)}
        </div>
      );
    }
    case "details": {
      const summary = (n.content ?? []).find((c) => c.type === "detailsSummary");
      const body = (n.content ?? []).find((c) => c.type === "detailsContent");
      return (
        <details key={key} className="dx-details" open={!!a.open}>
          <summary>{summary ? kids(summary, ctx, key + "s", slug) : "Details"}</summary>
          <div>{body ? kids(body, ctx, key + "c", slug) : null}</div>
        </details>
      );
    }
    case "attachment": {
      if (typeof a.sha !== "string") return null;
      const name = String(a.name ?? "file");
      const ext = (name.split(".").pop() ?? "").slice(0, 4).toUpperCase();
      return (
        <a key={key} className="dx-attach" href={ctx.assetUrl(a.sha)} download={name}>
          <span className="ic" aria-hidden="true">{ext || "FILE"}</span>
          <span><b>{name}</b><br /><span className="hint">{fmtBytes(Number(a.size) || 0)} · download</span></span>
        </a>
      );
    }
    case "mermaid": {
      const sha = ctx.dark ? a.darkSvg ?? a.lightSvg : a.lightSvg ?? a.darkSvg;
      if (typeof sha === "string") {
        // SVG through <img> only: scripts in an SVG never run there (D13).
        return <figure key={key} className="dx-figure dx-mermaid"><img src={ctx.assetUrl(sha)} alt="Diagram" loading="lazy" /></figure>;
      }
      return <CodeBlock key={key} code={String(a.source ?? "")} language="mermaid" />;
    }
    case "pageLink": {
      const r = ctx.pageLink(a);
      const label = String(a.label ?? "page");
      // The context decides the target; the scheme is still checked here (internal routes are "#/…").
      const href = r.href && (r.href.startsWith("#/") ? r.href : safeHref(r.href));
      return href ? <a key={key} className="dx-xlink" href={href} title={r.title}>{label}</a>
        : <span key={key} className="dx-xtext" title={r.title}>{label}</span>;
    }
    case "webappEmbed": return ctx.embed(a, key);
    default: return null;
  }
}

type Hast = { type: string; value?: string; tagName?: string; properties?: { className?: string[] }; children?: Hast[] };

function hastToReact(nodes: Hast[], key: string): ReactNode[] {
  return nodes.map((h, i) => {
    if (h.type === "text") return h.value ?? "";
    if (h.type === "element") {
      const cls = h.properties?.className?.filter((c) => /^hljs-[a-z_-]+$/.test(c)).join(" ");
      return <span key={`${key}.${i}`} className={cls || undefined}>{hastToReact(h.children ?? [], `${key}.${i}`)}</span>;
    }
    return null;
  });
}

/** Code with a language label and a copy button. Highlighting is a lazy chunk, so the first paint never waits for it. */
export function CodeBlock({ code, language }: { code: string; language: string }) {
  const [hl, setHl] = useState<ReactNode[] | null>(null);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    let live = true;
    if (!language || language === "plain" || language === "text" || code.length > 100_000) return;
    import("./highlight").then((m) => {
      const tree = m.highlight(code, language);
      if (live && tree) setHl(hastToReact(tree as Hast[], "h"));
    }).catch(() => { /* no highlighting: plain text is fine */ });
    return () => { live = false; };
  }, [code, language]);
  const copy = () => {
    navigator.clipboard?.writeText(code).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); }).catch(() => {});
  };
  return (
    <div className="dx-code">
      <div className="ch"><span>{language || "code"}</span><button type="button" onClick={copy}>{copied ? "Copied" : "Copy"}</button></div>
      <pre><code>{hl ?? code}</code></pre>
    </div>
  );
}
