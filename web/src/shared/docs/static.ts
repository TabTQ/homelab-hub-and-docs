// Static mode for the two public apps (GitHub Pages copy). The exporter adds <meta name="static-data" content="page.json">
// to the shell. When that tag is there, the app reads that file next to the page instead of calling the API.

/** The data file named by the static-data tag, or null when the tag is missing (normal mode: use the API). */
export function staticDataFile(doc: Pick<Document, "querySelector"> = document): string | null {
  const content = doc.querySelector('meta[name="static-data"]')?.getAttribute("content");
  if (content === null || content === undefined) return null;
  // Must be a plain relative path: no scheme, no leading slash, no parent folder.
  const c = content.trim();
  if (!c || c.startsWith("/") || c.includes(":") || c.includes("\\") || c.split("/").includes("..")) {
    throw new Error("This page has an invalid static-data setting.");
  }
  return c;
}

export interface StaticAsset { file?: string }

/** Where an asset of the page is loaded from: its listed file in static mode, /a/<sha> otherwise. */
export function assetHref(sha: string, assets: Record<string, StaticAsset | undefined>, isStatic: boolean): string {
  const file = assets[sha]?.file;
  return isStatic && file ? file : `/a/${sha}`;
}

/** The base folder of a webapp bundle: app/<sha>/ relative to the page in static mode, /app/<sha>/ otherwise. */
export function bundleBase(sha: string, isStatic: boolean): string {
  return isStatic ? `app/${sha}/` : `/app/${sha}/`;
}
