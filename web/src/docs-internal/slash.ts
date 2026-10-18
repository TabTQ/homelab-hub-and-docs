// The "/" block menu (mockups tab 2). The list and its filtering are plain data; Editor.tsx runs the chosen command.

export type SlashId = "h2" | "h3" | "h4" | "bullet" | "ordered" | "task" | "quote" | "rule" | "table" | "code" | "image"
  | "callout" | "mermaid" | "details" | "attachment" | "webapp" | "pagelink";

export interface SlashItem { id: SlashId; group: "Basic" | "Rich"; label: string; hint?: string; icon: string; keywords: string }

export const SLASH_ITEMS: SlashItem[] = [
  { id: "h2", group: "Basic", label: "Heading", hint: "Section title", icon: "H2", keywords: "h2 title section heading" },
  { id: "h3", group: "Basic", label: "Subheading", hint: "Smaller title", icon: "H3", keywords: "h3 subtitle heading" },
  { id: "h4", group: "Basic", label: "Small heading", icon: "H4", keywords: "h4 heading" },
  { id: "bullet", group: "Basic", label: "Bulleted list", icon: "•", keywords: "ul list bullet" },
  { id: "ordered", group: "Basic", label: "Numbered list", icon: "1.", keywords: "ol list number ordered" },
  { id: "task", group: "Basic", label: "Checklist", icon: "☑", keywords: "todo task check list" },
  { id: "quote", group: "Basic", label: "Quote", icon: "❝", keywords: "blockquote quote" },
  { id: "rule", group: "Basic", label: "Divider", icon: "—", keywords: "hr rule divider line" },
  { id: "table", group: "Basic", label: "Table", hint: "3 × 3, header row", icon: "▦", keywords: "table grid" },
  { id: "code", group: "Basic", label: "Code block", hint: "With language and copy button", icon: "{ }", keywords: "code snippet pre" },
  { id: "image", group: "Basic", label: "Image", hint: "Upload, paste or drag", icon: "🖼", keywords: "image picture photo png jpg" },
  { id: "callout", group: "Rich", label: "Callout", hint: "Info, tip, warning, danger", icon: "ⓘ", keywords: "callout note info tip warning danger" },
  { id: "mermaid", group: "Rich", label: "Mermaid diagram", hint: "Flowchart, sequence…", icon: "◇", keywords: "mermaid diagram flowchart sequence chart" },
  { id: "details", group: "Rich", label: "Collapsible section", icon: "▸", keywords: "details toggle collapse expand" },
  { id: "attachment", group: "Rich", label: "Attachment", hint: "PDF, config, zip", icon: "📎", keywords: "file attachment pdf upload" },
  { id: "webapp", group: "Rich", label: "Embed webapp page", hint: "Choose one of your webapp pages", icon: "⧉", keywords: "webapp embed app iframe" },
  { id: "pagelink", group: "Rich", label: "Link to page", hint: "Search the tree", icon: "↗", keywords: "link page reference" },
];

/** Items matching every word of the query (label, hint or keywords). Prefix matches on the label come first. */
export function filterSlash(query: string): SlashItem[] {
  const q = query.toLowerCase().trim();
  if (!q) return SLASH_ITEMS;
  const words = q.split(/\s+/);
  const hits = SLASH_ITEMS.filter((i) => words.every((w) => `${i.label} ${i.hint ?? ""} ${i.keywords}`.toLowerCase().includes(w)));
  return hits.sort((a, b) => Number(!a.label.toLowerCase().startsWith(q)) - Number(!b.label.toLowerCase().startsWith(q)));
}
