import { useEffect, useState } from "react";
import { api, ApiError } from "../../shared/api";
import { Modal } from "../components/Modal";
import { formatBytes, parseSize } from "./format";
import type { CleanupPreview, CleanupSelection, DockerHost } from "./types";

const shortId = (id: string) => id.replace(/^sha256:/, "").slice(0, 12);

/**
 * "Clean up this host": lists the images and networks nothing uses (a dry run on the host), lets the owner pick some or
 * all of them, and hands the selection to the page's usual prepare / confirm / job flow.
 */
export function CleanupDialog({ host, onClose, onRemove, onUnauthorized }: {
  host: DockerHost; onClose: () => void; onRemove: (selection: CleanupSelection) => void; onUnauthorized: () => void;
}) {
  const [preview, setPreview] = useState<CleanupPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [images, setImages] = useState<Set<string>>(new Set());
  const [networks, setNetworks] = useState<Set<string>>(new Set());

  useEffect(() => {
    api<CleanupPreview>(`/api/admin/docker/hosts/${encodeURIComponent(host.id)}/cleanup/preview`).then(setPreview)
      .catch((e: ApiError) => { if (e.status === 401) onUnauthorized(); setError(e.message); });
  }, [host.id, onUnauthorized]);

  const toggle = (set: Set<string>, update: (s: Set<string>) => void, key: string) => {
    const next = new Set(set);
    if (next.has(key)) next.delete(key); else next.add(key);
    update(next);
  };
  const count = images.size + networks.size;
  const bytes = (preview?.images ?? []).filter((i) => images.has(i.id)).reduce((sum, i) => sum + (parseSize(i.size) ?? 0), 0);
  const total = (preview?.images ?? []).reduce((sum, i) => sum + (parseSize(i.size) ?? 0), 0);
  const empty = preview != null && preview.images.length === 0 && preview.networks.length === 0;

  return (
    <Modal title={`Clean up ${host.label}`} onClose={onClose} wide actions={<>
      <span className="dk-clean-total grow" role="status">{count > 0 ? `${count} selected · ≈ ${formatBytes(bytes)} reclaimable` : ""}</span>
      <button className="btn" onClick={onClose}>Close</button>
      {!empty && <button className="btn danger" disabled={count === 0} onClick={() => onRemove({ images: [...images], networks: [...networks] })}>Remove selected…</button>}
    </>}>
      {error ? <div className="banner bad" role="alert">{error}</div>
        : !preview ? <p className="sub">Asking {host.label} what nothing uses…</p>
        : empty ? <p className="sub">Nothing to clean up: every image and network on {host.label} is in use by a container.</p>
        : <>
          <p className="sub">Only images and networks no container uses (running or stopped) are listed. Nothing is removed until you confirm.</p>
          {preview.images.length > 0 && (
            <fieldset className="dk-clean-group">
              <legend>Images <span className="dk-muted">({preview.images.length}, ≈ {formatBytes(total)})</span>
                <label className="dk-check"><input type="checkbox" checked={images.size === preview.images.length}
                  onChange={(e) => setImages(e.target.checked ? new Set(preview.images.map((i) => i.id)) : new Set())} /> Select all</label>
              </legend>
              {preview.images.map((i) => (
                <label key={i.id} className="dk-clean-item">
                  <input type="checkbox" checked={images.has(i.id)} onChange={() => toggle(images, setImages, i.id)} />
                  <span className="mono grow">{i.tags.length ? i.tags.join(", ") : <>&lt;none&gt; <span className="dk-muted">{shortId(i.id)}</span></>}</span>
                  <span className="dk-muted">{i.created}</span>
                  <span className="mono">{i.size}</span>
                </label>
              ))}
            </fieldset>
          )}
          {preview.networks.length > 0 && (
            <fieldset className="dk-clean-group">
              <legend>Networks <span className="dk-muted">({preview.networks.length})</span>
                <label className="dk-check"><input type="checkbox" checked={networks.size === preview.networks.length}
                  onChange={(e) => setNetworks(e.target.checked ? new Set(preview.networks.map((n) => n.name)) : new Set())} /> Select all</label>
              </legend>
              {preview.networks.map((n) => (
                <label key={n.name} className="dk-clean-item">
                  <input type="checkbox" checked={networks.has(n.name)} onChange={() => toggle(networks, setNetworks, n.name)} />
                  <span className="mono grow">{n.name}</span>
                  <span className="dk-muted">{n.driver}</span>
                </label>
              ))}
            </fieldset>
          )}
        </>}
    </Modal>
  );
}
