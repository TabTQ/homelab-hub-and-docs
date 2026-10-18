// Syntax highlighting (lowlight, the highlight.js grammars) as its own chunk, loaded when a page has a code block.
import { common, createLowlight } from "lowlight";

const low = createLowlight(common);
const ALIASES: Record<string, string> = { sh: "bash", shell: "bash", yml: "yaml", js: "javascript", ts: "typescript", py: "python", dockerfile: "dockerfile" };

export function highlight(code: string, language: string): unknown[] | null {
  const lang = ALIASES[language] ?? language;
  if (!low.registered(lang)) return null;
  return low.highlight(lang, code).children as unknown[];
}

export function languages(): string[] {
  return low.listLanguages().sort();
}
