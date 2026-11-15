import { fmtBytes } from "../shared/util";
import { useEffect, useMemo, useState } from "react";
import { api } from "../shared/api";
import { useToast } from "../shared/components/Toast";
import type { PMNode } from "../shared/docs/types";
import { blockDiff, summarize } from "./docDiff";
import { A, go, when, type DocsConfig, type PageDetail, type PublishCheck } from "./docsApi";
import { PlacementFields, Sheet } from "./Dialogs";
import { cleanSlugInput, dialogRules, githubGate, githubPageUrl, githubToast, slugFromTitle, slugProblem, targetsBody } from "./githubUi";
import { siblings, type TreeItem } from "./treeModel";

/** Splits a stored hostname into the part the owner types and the domain chosen from DOCS_DOMAINS. */
export function splitHost(host: string | null, domains: string[]): { sub: string; domain: string } {
  if (!host) return { sub: "", domain: domains[0] ?? "" };
  const d = [...domains].sort((a, b) => b.length - a.length).find((x) => host.endsWith("." + x));
  return d ? { sub: host.slice(0, -(d.length + 1)), domain: d } : { sub: host, domain: "" };
}

export function joinHost(sub: string, domain: string): string {
  const s = sub.trim().replace(/\.+$/, "");
  return domain ? (s ? `${s}.${domain}` : "") : s;
}

/**
 * Page settings and Publish in one dialog (mockups tab 3). Public = a hostname and a published version. Publish copies the
 * current draft to the public side; Unpublish takes the hostname offline at once but keeps it reserved for this page.
 */
export function PublishDialog({ page, items, config, onClose, onChanged, fail }: {
  page: PageDetail; items: TreeItem[]; config: DocsConfig | null; onClose: () => void; onChanged: () => void; fail: (e: any) => void;
}) {
  const toast = useToast();
  const [check, setCheck] = useState<PublishCheck | null>(null);
  const domains = config?.domains ?? [];
  const initial = splitHost(page.publicHost, domains);
  const ghAvailable = githubGate(config);
  const [isPublic, setIsPublic] = useState(page.state !== "internal" ? !!page.publicHost : !page.publicHost);
  const [ghOn, setGhOn] = useState(ghAvailable && page.state !== "internal" && !!page.githubSlug);
  const [slug, setSlug] = useState(page.githubSlug ?? slugFromTitle(page.title));
  const [slugMsg, setSlugMsg] = useState<{ ok: boolean; message: string } | null>(null);
  const [sub, setSub] = useState(initial.sub);
  const [domain, setDomain] = useState(initial.domain);
  const [hostMsg, setHostMsg] = useState<{ ok: boolean; message: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const before = siblings(items, page.parentId);
  const at = before.findIndex((i) => i.id === page.id);
  const [placement, setPlacement] = useState({ parentId: page.parentId, afterId: at > 0 ? before[at - 1].id : null });

  useEffect(() => { api<PublishCheck>(`${A}/pages/${page.id}/publish-check`).then(setCheck).catch(fail); }, [page.id, page.version, fail]);

  const host = joinHost(sub, domain);
  const live = page.state !== "internal";
  useEffect(() => {
    if (!isPublic || !host) { setHostMsg(null); return; }
    const t = setTimeout(() => {
      api(`${A}/hosts/check?host=${encodeURIComponent(host)}&pageId=${page.id}`)
        .then((r) => setHostMsg({ ok: r.ok, message: r.ok ? `✓ ${r.message} With the wildcard tunnel set up, it works as soon as you publish.` : r.message }))
        .catch(() => setHostMsg(null));
    }, 250);
    return () => clearTimeout(t);
  }, [host, isPublic, page.id]);

  useEffect(() => {
    const bad = ghAvailable && ghOn ? slugProblem(slug) : null;
    if (!ghAvailable || !ghOn || !slug) { setSlugMsg(null); return; }
    if (bad) { setSlugMsg({ ok: false, message: bad }); return; }
    const t = setTimeout(() => {
      api(`${A}/github/check?slug=${encodeURIComponent(slug)}&pageId=${page.id}`)
        .then((r) => setSlugMsg({ ok: r.ok, message: r.ok ? `✓ ${r.message}` : r.message }))
        .catch(() => setSlugMsg(null));
    }, 250);
    return () => clearTimeout(t);
  }, [slug, ghOn, ghAvailable, page.id]);

  const diffSummary = useMemo(() => {
    if (!check || page.kind !== "rich") return null;
    if (!check.liveBody) return "First publish: the whole page goes live.";
    const s = summarize(blockDiff(check.liveBody as PMNode, page.body as PMNode));
    return s === "no changes" && check.liveTitle === page.title ? "No changes since the live version." : `${check.liveTitle !== page.title ? "Title changed. " : ""}${s}.`;
  }, [check, page]);

  // Every action reloads the page's version first: the dialog may have been open while something else saved.
  const withVersion = async () => (await api<PageDetail>(`${A}/pages/${page.id}`)).version;
  const placementChanged = placement.parentId !== page.parentId || placement.afterId !== (at > 0 ? before[at - 1].id : null);
  const run = async (what: "publish" | "unpublish" | "save") => {
    setBusy(true);
    try {
      if (placementChanged) {
        await api(`${A}/pages/${page.id}/move`, { method: "POST", body: { ...placement, version: await withVersion() } });
      }
      if (what === "publish") {
        const r = await api(`${A}/pages/${page.id}/publish`, { method: "POST", body: targetsBody({ localOn: isPublic, host, githubAvailable: ghAvailable, githubOn: ghOn, slug, version: await withVersion() }) });
        const local = !isPublic ? ""
          : !r.live ? "Published, but the public site could not be updated yet. It retries every 30 s."
          : r.route?.state === "error" ? `Published, but the address is not reachable yet: ${r.route.message} Use Retry in the top bar.`
          : r.route?.state === "pending" ? `Published: https://${r.publicHost}/ (connecting the address, this takes a few seconds)`
          : `Published: https://${r.publicHost}/`;
        toast([local, ghOn ? githubToast(r) : ""].filter(Boolean).join(" ") || "Published.");
      } else if (what === "unpublish") {
        await api(`${A}/pages/${page.id}/unpublish`, { method: "POST", body: { version: await withVersion() } });
        toast(ghAvailable && page.githubSlug ? "Unpublished everywhere." : "Unpublished: the address now gives 404.");
      } else if (!ghAvailable) {
        const wanted = isPublic ? host : "";
        if (wanted !== (page.publicHost ?? "")) {
          await api(`${A}/pages/${page.id}/host`, { method: "PUT", body: { publicHost: wanted || null, version: await withVersion() } });
        }
        toast("Settings saved.");
      } else if (!live) {
        // Not published yet: only the local address can be reserved.
        const wanted = isPublic ? host : "";
        if (wanted !== (page.publicHost ?? "")) {
          await api(`${A}/pages/${page.id}/host`, { method: "PUT", body: { publicHost: wanted || null, version: await withVersion() } });
        }
        toast("Settings saved.");
      } else {
        const body = targetsBody({ localOn: isPublic, host, githubAvailable: true, githubOn: ghOn, slug, version: await withVersion() });
        const changed = body.publicHost !== (page.publicHost ?? null) || body.githubSlug !== (page.githubSlug ?? null);
        if (changed) await api(`${A}/pages/${page.id}/targets`, { method: "PUT", body });
        toast(!body.publicHost && !body.githubSlug ? "Unpublished everywhere." : "Settings saved.");
      }
      onChanged();
      onClose();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const rules = dialogRules({
    live, githubAvailable: ghAvailable, localOn: isPublic, host, hostOk: hostMsg ? hostMsg.ok : null,
    githubOn: ghOn, slug, slugOk: slugMsg ? slugMsg.ok : null, problems: check?.problems.length ?? 0, busy,
    currentHost: page.publicHost, currentSlug: page.githubSlug ?? null,
  });
  const siteUrl = config?.github?.siteUrl ?? "";
  return (
    <Sheet title={`Publish “${page.title}”`} onClose={onClose} wide>
      <p className="hint">Draft v{check?.draftVersion ?? page.latestVersion}{live && check?.publishedVersion ? ` · public now shows v${check.publishedVersion} (published ${when(check.publishedAt)})` : " · not public"}</p>
      <div className="caption">Where to publish</div>
      <div className={`dx-target${isPublic ? " on" : ""}`}>
        <div className="dx-thead">
          <button type="button" className="dx-switch" role="switch" aria-checked={isPublic} onClick={() => setIsPublic(!isPublic)}><i /><b>Local address</b></button>
          <span className="dx-tstate">{live && page.publicHost ? "Live now" : "Not live yet"}</span>
        </div>
        {ghAvailable && <p>Served from the Docs container through the tunnel. Live at once. Down when the homelab is down.</p>}
        {!ghAvailable && <p className="hint">{isPublic ? "Anyone with the link can view this one page. It is never listed or indexed." : "Only you, here in Docs, can see this page."}</p>}
        {isPublic && (
          <>
            <label htmlFor="dx-host" className="dx-sr">Public address</label>
            <div className="dx-hostin">
              <span className="mono muted">https://</span>
              <input id="dx-host" type="text" value={sub} autoComplete="off" spellCheck={false} placeholder={domains.length ? "page1" : "page1.domain.com"}
                onChange={(e) => setSub(e.target.value.toLowerCase())} />
              {domains.length > 0 && (
                <select value={domain} onChange={(e) => setDomain(e.target.value)} aria-label="Domain">
                  {domains.map((d) => <option key={d} value={d}>.{d}</option>)}
                </select>
              )}
            </div>
            {hostMsg && <div className="hint" style={{ color: hostMsg.ok ? "var(--ok)" : "var(--bad)" }} role="status">{hostMsg.message}</div>}
          </>
        )}
        {rules.takesLocalOffline && <div className="hint" style={{ color: "var(--warn)" }}>
          {isPublic ? `Changing the address takes ${page.publicHost} offline.` : (ghAvailable && ghOn ? `Turning this off takes ${page.publicHost} offline at once and frees the address.` : `Turning this off takes ${page.publicHost} offline at once. The address stays reserved for this page.`)}</div>}
      </div>
      {ghAvailable && (
        <div className={`dx-target${ghOn ? " on" : ""}`}>
          <div className="dx-thead">
            <button type="button" className="dx-switch" role="switch" aria-checked={ghOn} onClick={() => setGhOn(!ghOn)}><i /><b>GitHub Pages</b></button>
            <span className="dx-tstate">{live && page.githubSlug ? "Live now" : "Not live yet"}</span>
          </div>
          <p>A static copy in the GitHub site. Takes about a minute. Stays up when the homelab is down.</p>
          {ghOn && (
            <>
              <label htmlFor="dx-slug" className="dx-sr">Folder name</label>
              <div className="dx-hostin">
                <span className="mono muted">{siteUrl || "https://…/"}</span>
                <input id="dx-slug" type="text" value={slug} autoComplete="off" spellCheck={false} placeholder="my-page"
                  onChange={(e) => setSlug(cleanSlugInput(e.target.value))} />
                <span className="mono muted dx-tail">/</span>
              </div>
              {slugMsg && <div className="hint" style={{ color: slugMsg.ok ? "var(--ok)" : "var(--bad)" }} role="status">{slugMsg.message}</div>}
            </>
          )}
          {rules.takesGithubOffline && <div className="hint" style={{ color: "var(--warn)" }}>
            {ghOn ? `Changing the folder takes ${githubPageUrl(siteUrl, page.githubSlug ?? "")} offline.` : "Turning this off removes the page from GitHub Pages in about a minute."}</div>}
        </div>
      )}
      <PlacementFields items={items} movingId={page.id} parentId={placement.parentId} afterId={placement.afterId} onChange={(p, a) => setPlacement({ parentId: p, afterId: a })} />
      <div className="caption">Pre-publish checks</div>
      {!check ? <p className="hint">Checking…</p> : (
        <div className="dx-checks">
          {check.problems.map((p) => <Check key={p} m="b">{p}</Check>)}
          {diffSummary && <Check m="ok">{diffSummary} {check.liveBody && <a href={`#/p/${page.id}/history`} onClick={() => go(`#/p/${page.id}/history`)}>Show diff</a>}</Check>}
          {check.links.map((l) => {
            const gh = ghAvailable && ghOn;
            if (gh && l.githubHref && l.githubLocalOnly) {
              return <Check key={l.pageId} m="w">Link to <b>{l.title}</b> is local only. The GitHub copy will point to its local address, which is down when the homelab is down.</Check>;
            }
            if (gh && l.githubHref) {
              return <Check key={l.pageId} m="ok">Link to <b>{l.title}</b> → <span className="mono">{l.githubHref.replace("https://", "").replace(/\/$/, "")}</span> on GitHub{l.public && isPublic ? " and local" : ""}.</Check>;
            }
            return l.public
              ? <Check key={l.pageId} m="ok">Link to <b>{l.title}</b> → <span className="mono">{l.href?.replace("https://", "").replace(/\/$/, "")}</span> (public)</Check>
              : <Check key={l.pageId} m="w">Link to <b>{l.title ?? "a deleted page"}</b> is {l.missing ? "broken" : "internal"}, so visitors see it as <b>plain text</b>.</Check>;
          })}
          {check.embeds.map((e) => e.source === "missing"
            ? <Check key={e.pageId} m="w">Embedded webapp <b>{e.title ?? "(deleted page)"}</b> has no app uploaded: it will be left out.</Check>
            : <Check key={e.pageId} m="ok">Embedded webapp <b>{e.title}</b> ({e.source === "published" ? "published" : "current draft"} bundle b{e.bundleSeq}, {fmtBytes(e.size ?? 0)}) will be included.</Check>)}
          {page.kind === "webapp" && check.bundle && <Check m="ok">App bundle b{check.bundle.seq} ({check.bundle.files.length} files, {fmtBytes(check.bundle.size)}) goes live.</Check>}
          <Check m="ok">{check.files.length} file{check.files.length === 1 ? "" : "s"} will be copied to the public side{check.fileBytes ? ` · ${fmtBytes(check.fileBytes)} in total` : ""}.</Check>
        </div>
      )}
      <div className="mrow dx-acts">
        {rules.showUnpublish && <button className="btn danger" disabled={busy} onClick={() => run("unpublish")}>{rules.unpublishLabel}</button>}
        <span className="grow" />
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={!rules.canSave} onClick={() => run("save")}>Save settings only</button>
        <button className="btn pri" disabled={!rules.canPublish} onClick={() => run("publish")}>Publish v{check?.draftVersion ?? page.latestVersion}</button>
      </div>
      {live && <p className="hint" style={{ display: "block" }}><b>Save settings only</b> moves the current published version{check?.publishedVersion ? ` (v${check.publishedVersion})` : ""}, with no new version.{rules.saveUnpublishes ? " With every place turned off it unpublishes the page." : ""}{" "}
        <b>Publish v{check?.draftVersion ?? page.latestVersion}</b> also sends the new draft.</p>}
    </Sheet>
  );
}

function Check({ m, children }: { m: "ok" | "w" | "b"; children: React.ReactNode }) {
  return <div className="dx-check"><span className={`m ${m}`} aria-hidden="true">{m === "ok" ? "✓" : m === "w" ? "!" : "✗"}</span><div>{children}</div></div>;
}
