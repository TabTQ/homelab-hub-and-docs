import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "../api";
import { useToast } from "../components/Toast";
import { when } from "../../docs-internal/docsApi";
import { Sheet } from "../../docs-internal/Dialogs";
import { isBusyState, portfolioText, type PortfolioGithub } from "../../docs-internal/githubUi";

const P = "/api/admin/portfolio";

/**
 * The GitHub Pages panel in the portfolio editor (mockups Docs 3). Local saving is unchanged. GitHub is a manual deploy.
 * An older backend has no such route: then nothing is drawn. When GitHub is off, only the download stays.
 */
export function PortfolioGithubPanel({ onUnauthorized }: { onUnauthorized: () => void }) {
  const toast = useToast();
  const [st, setSt] = useState<PortfolioGithub | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);

  const load = useCallback(() => api<PortfolioGithub>(`${P}/github/status`)
    .then((r) => setSt(r && typeof r.enabled === "boolean" ? r : null)).catch(() => setSt(null)), []);
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (!st?.enabled || !isBusyState(st.sync?.state as any)) return;
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [st, load]);

  if (!st) return null;
  const act = (what: "deploy" | "remove") => {
    setBusy(true);
    api<PortfolioGithub>(`${P}/github/${what}`, { method: "POST" })
      .then((r) => { setSt(r); toast(what === "deploy" ? "Sent to GitHub Pages. It goes live in about a minute." : "Removed from GitHub Pages. It goes offline in about a minute."); })
      .catch((e: ApiError) => { if (e.status === 401) onUnauthorized(); else toast(e.message); })
      .finally(() => { setBusy(false); setConfirm(false); });
  };
  const download = <a className="btn sm" href={`${P}/export.zip`} download="portfolio-site.zip">Download static site</a>;
  if (!st.enabled) {
    return (
      <div><div className="dx-strip">
        <div className="t"><b>Static site</b><span>A copy of the portfolio you can host anywhere.</span></div>{download}
      </div></div>
    );
  }
  const t = portfolioText(st, (iso) => when(iso));
  const failed = st.sync?.state === "error";
  return (
    <div>
      <div className={`dx-strip ${t.tone}`} role="status">
        <i className={`gem ${t.tone}`} />
        <div className="t"><b>{t.title}</b><span>{t.detail}{st.deployed && st.url && <> · <a href={st.url} target="_blank" rel="noreferrer">{st.url.replace(/^https:\/\//, "")}</a></>}</span></div>
        {download}
        {st.deployed && <button className="btn sm danger" disabled={busy} onClick={() => setConfirm(true)}>Remove</button>}
        <button className={`btn sm${!st.deployed || st.behind || failed ? " pri" : ""}`} disabled={busy || (st.deployed && !st.behind && !failed)} onClick={() => act("deploy")}>
          {failed ? "Retry" : "Deploy to GitHub"}
        </button>
      </div>
      {confirm && (
        <Sheet title="Remove from GitHub Pages?" onClose={() => setConfirm(false)}>
          <p className="hint">The portfolio goes offline on GitHub Pages in about a minute. The local site is not affected. You can deploy it again later.</p>
          <div className="mrow dx-acts"><span className="grow" /><button className="btn" onClick={() => setConfirm(false)}>Cancel</button>
            <button className="btn danger" disabled={busy} onClick={() => act("remove")}>Remove</button></div>
        </Sheet>
      )}
    </div>
  );
}
