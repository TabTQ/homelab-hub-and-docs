import { fmtBytes } from "../shared/util";
import { useEffect, useRef, useState } from "react";
import { api, upload } from "../shared/api";
import { textOf } from "../shared/docs/safe";
import type { PMNode, WebappSettings } from "../shared/docs/types";
import { A, when, type BundleView, type DocsConfig, type PageDetail } from "./docsApi";
import { EmbedPreview } from "./internalRender";

/** Plain text (one paragraph per line) ↔ the intro document shown above a framed app. */
export function introToText(doc: PMNode | null | undefined): string {
  return (doc?.content ?? []).map((p) => textOf(p)).join("\n");
}

export function textToIntro(text: string): PMNode | null {
  const lines = text.split("\n").map((l) => l.trimEnd());
  if (!lines.some((l) => l.trim())) return null;
  return { type: "doc", content: lines.map((l) => (l ? { type: "paragraph", content: [{ type: "text", text: l }] } : { type: "paragraph" })) };
}

/** A webapp page (mockups tab 4): bundles, display settings, network access and a live sandboxed preview. */
export function WebappPage({ page, config, onChanged, fail, theme }: {
  page: PageDetail; config: DocsConfig | null; onChanged: () => void; fail: (e: any) => void; theme: "light" | "dark";
}) {
  const [settings, setSettings] = useState<WebappSettings>(page.body as WebappSettings);
  const [intro, setIntro] = useState(introToText((page.body as WebappSettings).intro));
  const [bundles, setBundles] = useState<BundleView[] | null>(null);
  const [dirty, setDirty] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [pvTheme, setPvTheme] = useState<"light" | "dark">(theme);
  const [phone, setPhone] = useState(false);
  const [pvKey, setPvKey] = useState(0);
  const file = useRef<HTMLInputElement>(null);

  const loadBundles = () => api<BundleView[]>(`${A}/pages/${page.id}/bundles`).then(setBundles).catch(fail);
  useEffect(() => { loadBundles(); }, [page.id, page.version]); // eslint-disable-line react-hooks/exhaustive-deps

  const set = (s: Partial<WebappSettings>) => { setSettings({ ...settings, ...s }); setDirty(true); };
  const save = () => {
    const body = { ...settings, intro: settings.display === "framed" ? textToIntro(intro) : null };
    api(`${A}/pages/${page.id}`, { method: "PUT", body: { title: page.title, body, version: page.version } })
      .then(() => { setDirty(false); setPvKey((k) => k + 1); onChanged(); }).catch(fail);
  };
  const send = (f: File) => {
    setUploading(true);
    upload(`${A}/pages/${page.id}/bundles`, f, f.name, { version: String(page.version) })
      .then(() => { setPvKey((k) => k + 1); onChanged(); }).catch(fail).finally(() => setUploading(false));
  };
  const use = (seq: number) => api(`${A}/pages/${page.id}/bundles/${seq}/use`, { method: "POST", body: { version: page.version } })
    .then(() => { setPvKey((k) => k + 1); onChanged(); }).catch(fail);
  const openFull = () => api(`${A}/pages/${page.id}/preview-token`, { method: "POST", body: {} })
    .then((r) => window.open(`${r.url}?theme=${pvTheme}`, "_blank", "noopener,noreferrer")).catch(fail);

  const draft = bundles?.find((b) => b.draft);
  const maxMb = config?.maxBundleMb ?? 25;
  return (
    <div className="dx-cols2">
      <div>
        <section className="dx-card"><h4>Bundle</h4>
          <div className={`dx-upload${dragging ? " over" : ""}`} onDragOver={(e) => { e.preventDefault(); setDragging(true); }} onDragLeave={() => setDragging(false)}
            onDrop={(e) => { e.preventDefault(); setDragging(false); const f = e.dataTransfer.files[0]; if (f) send(f); }}>
            {uploading ? "Uploading…" : <>Drop <b>index.html</b> or a <b>.zip</b> here, or <button className="linkbtn" onClick={() => file.current?.click()}>choose a file</button></>}
            <br /><span className="hint">zip ≤ {maxMb} MB · ≤ 500 files · ≤ 60 MB unpacked · html, css, js, json, images, fonts, svg, wasm, csv, txt</span>
            <input ref={file} type="file" accept=".html,.htm,.zip" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) send(f); e.target.value = ""; }} />
          </div>
          {bundles && bundles.length > 0 && (
            <table className="dx-bundles">
              <thead><tr><th>Bundle</th><th>Uploaded</th><th>Size</th><th /></tr></thead>
              <tbody>{bundles.map((b) => (
                <tr key={b.seq}>
                  <td><b>b{b.seq}</b> {b.draft && <span className="dx-pill draft">draft</span>} {b.live && <span className="dx-pill pub">live</span>}</td>
                  <td>{when(b.createdAt)}</td><td>{fmtBytes(b.size)}</td>
                  <td>{!b.draft && <button className="btn sm" onClick={() => use(b.seq)}>Use</button>}</td>
                </tr>
              ))}</tbody>
            </table>
          )}
          {draft && <details className="dx-files"><summary>Files in b{draft.seq} ({draft.files.length})</summary>
            <div className="mono">{draft.files.map((f) => <div key={f.path}>{f.path} · {fmtBytes(f.size)}</div>)}</div></details>}
          <p className="hint">Optional helper for theme and auto height: <span className="mono">&lt;script src="_hub/hub-embed.js"&gt;&lt;/script&gt;</span></p>
        </section>
        <section className="dx-card"><h4>Display</h4>
          <div className="field"><label>Mode</label>
            <div className="dx-seg">
              <button aria-pressed={settings.display === "full"} onClick={() => set({ display: "full" })}>Full page</button>
              <button aria-pressed={settings.display === "framed"} onClick={() => set({ display: "framed" })}>Framed</button>
            </div>
            <div className="help">{settings.display === "full" ? "The app fills the whole browser window. Visitors see only your app." : "The page title and your text above, the app in a frame below."}</div>
          </div>
          {settings.display === "framed" && <div className="field"><label htmlFor="dx-intro">Text above the app (optional)</label>
            <textarea id="dx-intro" rows={3} value={intro} onChange={(e) => { setIntro(e.target.value); setDirty(true); }} /></div>}
          <div className="field"><label>Height</label>
            <div className="dx-seg">
              {(["fixed", "auto", "viewport"] as const).map((m) => (
                <button key={m} aria-pressed={settings.height.mode === m} onClick={() => set({ height: { ...settings.height, mode: m } })}>{m[0].toUpperCase() + m.slice(1)}</button>
              ))}
            </div>
            {settings.height.mode === "fixed" && <input type="number" min={150} max={4000} value={settings.height.px} aria-label="Height in px"
              onChange={(e) => set({ height: { ...settings.height, px: Number(e.target.value) } })} style={{ marginTop: 6, maxWidth: 120 }} />}
            <div className="help">{settings.height.mode === "auto" ? <>The app sends <span className="mono">{"{type:\"hub:height\", px}"}</span> with postMessage. Falls back to 600 px.</>
              : settings.height.mode === "viewport" ? "As tall as the browser window." : "Always this many pixels."}</div>
          </div>
          <div className="field"><label className="dx-cb"><input type="checkbox" checked={settings.theme} onChange={(e) => set({ theme: e.target.checked })} />
            Pass the light/dark theme to the app (<span className="mono">?theme=dark</span> + postMessage)</label></div>
        </section>
        <section className="dx-card"><h4>Network access</h4>
          <label className="dx-cb"><input type="checkbox" checked disabled /> Files from this bundle</label>
          {Object.entries(config?.cdns ?? {}).map(([k, origin]) => (
            <label key={k} className="dx-cb"><input type="checkbox" checked={settings.cdns.includes(k)}
              onChange={(e) => set({ cdns: e.target.checked ? [...settings.cdns, k] : settings.cdns.filter((c) => c !== k) })} /> {origin.replace("https://", "")}</label>
          ))}
          <div className="help">Scripts, styles, fonts and images only. Fetching data (<span className="mono">connect-src</span>) is always limited to the bundle.</div>
        </section>
        <div className="dx-savebar"><span className="hint">{dirty ? "Unsaved settings" : "Settings saved"}</span><span className="grow" />
          <button className="btn pri" disabled={!dirty} onClick={save}>Save settings</button></div>
      </div>
      <div>
        <div className="dx-previewbox">
          <div className="ph"><b>Preview</b>{draft && <span className="dx-pill draft">b{draft.seq} draft</span>}<span className="grow" />
            <div className="dx-seg">
              <button aria-pressed={pvTheme === "light"} onClick={() => setPvTheme("light")}>Light</button>
              <button aria-pressed={pvTheme === "dark"} onClick={() => setPvTheme("dark")}>Dark</button>
            </div>
            <div className="dx-seg">
              <button aria-pressed={!phone} onClick={() => setPhone(false)}>Desktop</button>
              <button aria-pressed={phone} onClick={() => setPhone(true)}>Phone</button>
            </div>
            {draft && <button className="btn sm" onClick={openFull}>Open full screen ↗</button>}
          </div>
          <div className={phone ? "dx-phoneframe" : undefined}>
            {draft ? <EmbedPreview key={pvKey} pageId={page.id} title={`${page.title} preview`} theme={pvTheme} height={settings.height.mode === "fixed" ? settings.height.px : 470} />
              : <div className="hint dx-pad">Upload the app to see it here.</div>}
          </div>
        </div>
        <p className="hint">Sandboxed iframe: <span className="mono">sandbox="allow-scripts"</span>, no same-origin. Files are served with <span className="mono">Content-Security-Policy: sandbox allow-scripts</span>, so this holds even when a file is opened directly.</p>
      </div>
    </div>
  );
}
