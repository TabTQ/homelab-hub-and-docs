// Mermaid → SVG in the editor (D13). Mermaid (~1 MB) is its own chunk, loaded the first time a diagram is shown, and never
// part of the public bundle: on save the editor stores a light and a dark SVG, and pages show those as <img>.

let chain: Promise<unknown> = Promise.resolve();
let seq = 0;

/** Renders one diagram. Calls are queued: mermaid keeps global state, so two renders must not overlap. */
export function renderMermaid(source: string, theme: "light" | "dark"): Promise<string> {
  const run = async () => {
    const mermaid = (await import("mermaid")).default;
    mermaid.initialize({
      startOnLoad: false, securityLevel: "strict", theme: theme === "dark" ? "dark" : "default",
      // Plain SVG text instead of HTML labels, so the picture is the same when shown through <img>.
      flowchart: { htmlLabels: false }, fontFamily: "IBM Plex Sans, Helvetica Neue, Arial, sans-serif",
    });
    const id = `dx-mermaid-${++seq}`;
    try {
      const { svg } = await mermaid.render(id, source);
      return svg;
    } finally {
      document.getElementById(id)?.remove();
      document.getElementById("d" + id)?.remove();
    }
  };
  const p = chain.then(run, run);
  chain = p.catch(() => undefined);
  return p;
}

/** Mermaid's error text without its stack and parser noise. */
export function mermaidError(e: unknown): string {
  const m = e instanceof Error ? e.message : String(e);
  return m.split("\n").slice(0, 4).join("\n").slice(0, 400);
}
