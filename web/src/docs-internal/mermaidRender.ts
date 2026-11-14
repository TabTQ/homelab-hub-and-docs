// Mermaid → SVG in the editor (D13). Mermaid (~1 MB) is its own chunk, loaded the first time a diagram is shown, and never
// part of the public bundle: on save the editor stores a light and a dark SVG, and pages show those as <img>.

// Colours follow the Stone and Parchment tokens in shared/valhalla.css. The SVGs are stored, so they cannot read CSS variables.
const THEME_VARS = {
  dark: {
    background: "#0a181c", primaryColor: "#10262b", primaryTextColor: "#dfe7e4", primaryBorderColor: "#40c1ac",
    secondaryColor: "#0a181c", tertiaryColor: "#07272d", lineColor: "#8b9b98", textColor: "#dfe7e4",
    mainBkg: "#10262b", nodeBorder: "#40c1ac", clusterBkg: "#0a181c", clusterBorder: "#1c383e", edgeLabelBackground: "#0a181c",
    noteBkgColor: "#10262b", noteTextColor: "#dfe7e4", noteBorderColor: "#c9a45c",
  },
  light: {
    background: "#f2eee5", primaryColor: "#e0d9cb", primaryTextColor: "#191c1b", primaryBorderColor: "#067f72",
    secondaryColor: "#f2eee5", tertiaryColor: "#e7e1d5", lineColor: "#5a605d", textColor: "#191c1b",
    mainBkg: "#e0d9cb", nodeBorder: "#067f72", clusterBkg: "#f2eee5", clusterBorder: "#c2baa9", edgeLabelBackground: "#f2eee5",
    noteBkgColor: "#e0d9cb", noteTextColor: "#191c1b", noteBorderColor: "#8a6a2a",
  },
} as const;

let chain: Promise<unknown> = Promise.resolve();
let seq = 0;

/** Renders one diagram. Calls are queued: mermaid keeps global state, so two renders must not overlap. */
export function renderMermaid(source: string, theme: "light" | "dark"): Promise<string> {
  const run = async () => {
    const mermaid = (await import("mermaid")).default;
    mermaid.initialize({
      startOnLoad: false, securityLevel: "strict", theme: "base", themeVariables: THEME_VARS[theme],
      // Plain SVG text instead of HTML labels, so the picture is the same when shown through <img>.
      flowchart: { htmlLabels: false }, fontFamily: "Barlow, Helvetica Neue, Arial, sans-serif",
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
