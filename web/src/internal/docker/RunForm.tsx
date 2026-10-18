import { useEffect, useState } from "react";
import { api } from "../../shared/api";
import { Modal } from "../components/Modal";
import { runToCompose, runToEnv, splitArgs } from "./format";
import type { DockerHost, RunSpec } from "./types";

type Vol = RunSpec["volumes"][number];
type Env = RunSpec["env"][number];

/**
 * A one-off `docker run -d`: image, name, ports, volumes, environment, restart policy, network and command. The server
 * checks every value again and shows the exact command before anything runs. "Save as stack" turns it into compose.yaml.
 */
export function RunForm({ host, onClose, onRun, onSaveAsStack }: {
  host: DockerHost; onClose: () => void; onRun: (spec: RunSpec) => void; onSaveAsStack: (name: string, compose: string, env: string) => void;
}) {
  const [image, setImage] = useState("");
  const [name, setName] = useState("");
  const [ports, setPorts] = useState<string[]>([""]);
  const [volumes, setVolumes] = useState<Vol[]>([{ source: "", target: "", readOnly: false }]);
  const [env, setEnv] = useState<Env[]>([{ key: "", value: "" }]);
  const [restart, setRestart] = useState("unless-stopped");
  const [network, setNetwork] = useState("");
  const [networks, setNetworks] = useState<string[]>([]);
  const [command, setCommand] = useState("");

  useEffect(() => {
    api<{ networks: string[] }>(`/api/admin/docker/hosts/${encodeURIComponent(host.id)}/networks`).then((r) => setNetworks(r.networks)).catch(() => setNetworks([]));
  }, [host.id]);

  const spec = (): RunSpec => ({
    image: image.trim(), name: name.trim(), ports: ports.map((p) => p.trim()).filter(Boolean),
    volumes: volumes.filter((v) => v.source.trim() || v.target.trim()), env: env.filter((e) => e.key.trim() || e.value),
    restart, network, command: splitArgs(command),
  });
  const ok = image.trim().length > 0;
  const stackName = (name.trim() || image.trim().split("/").pop()!.split(":")[0]).toLowerCase().replace(/[^a-z0-9_-]/g, "-").replace(/^[-_]+/, "").slice(0, 40);

  return (
    <Modal title={`Run a container on ${host.label}`} onClose={onClose} wide actions={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn" disabled={!ok} onClick={() => onSaveAsStack(stackName || "app", runToCompose(spec()), runToEnv(spec()))}
        title="Recommended for anything you keep: editable, versioned, easy to update">Save as stack instead</button>
      <button className="btn pri" disabled={!ok} onClick={() => onRun(spec())}>Review and run</button>
    </>}>
      <p className="sub">For a quick, one-off container. For something you will keep, <b>Save as stack</b> gives you an editable compose file with history and one-click updates.</p>
      <div className="dk-form">
        <div className="field"><label htmlFor="dk-image">Image <span className="req">*</span></label>
          <input id="dk-image" type="text" placeholder="nginx:alpine or ghcr.io/owner/app:tag" value={image} onChange={(e) => setImage(e.target.value)} autoComplete="off" spellCheck={false} autoCapitalize="off" /></div>
        <div className="field"><label htmlFor="dk-name">Name</label>
          <input id="dk-name" type="text" placeholder="optional, e.g. whoami" value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" spellCheck={false} autoCapitalize="off" /></div>
        <div className="field"><label htmlFor="dk-restart">Restart</label>
          <select id="dk-restart" value={restart} onChange={(e) => setRestart(e.target.value)}>
            {["unless-stopped", "always", "on-failure", "no"].map((r) => <option key={r} value={r}>{r}</option>)}
          </select></div>
        <div className="field"><label htmlFor="dk-network">Network</label>
          <select id="dk-network" value={network} onChange={(e) => setNetwork(e.target.value)}>
            <option value="">default (bridge)</option>
            {networks.filter((n) => n !== "bridge" && n !== "none").map((n) => <option key={n} value={n}>{n}</option>)}
          </select></div>
      </div>

      <Rows label="Ports" help="host:container, e.g. 8080:80, 53:53/udp or 127.0.0.1:5432:5432" items={ports} blank=""
        onChange={setPorts} render={(p, set) => <input type="text" aria-label="Port mapping" placeholder="8080:80" value={p} onChange={(e) => set(e.target.value)} spellCheck={false} />} />
      <Rows label="Volumes" help="An absolute host path or a named volume, mounted at a path inside the container" items={volumes} blank={{ source: "", target: "", readOnly: false }}
        onChange={setVolumes} render={(v, set) => <>
          <input type="text" aria-label="Host path or volume" placeholder="/srv/app or appdata" value={v.source} onChange={(e) => set({ ...v, source: e.target.value })} spellCheck={false} />
          <span className="dk-arrow">→</span>
          <input type="text" aria-label="Container path" placeholder="/data" value={v.target} onChange={(e) => set({ ...v, target: e.target.value })} spellCheck={false} />
          <label className="dk-check"><input type="checkbox" checked={v.readOnly} onChange={(e) => set({ ...v, readOnly: e.target.checked })} /> ro</label>
        </>} />
      <Rows label="Environment" help="Values are sent to the host privately (never on a command line)" items={env} blank={{ key: "", value: "" }}
        onChange={setEnv} render={(v, set) => <>
          <input type="text" aria-label="Variable name" placeholder="TZ" value={v.key} onChange={(e) => set({ ...v, key: e.target.value })} spellCheck={false} autoCapitalize="off" />
          <span className="dk-arrow">=</span>
          <input type="text" aria-label="Value" placeholder="Europe/Berlin" value={v.value} onChange={(e) => set({ ...v, value: e.target.value })} spellCheck={false} autoCapitalize="off" />
        </>} />
      <div className="field"><label htmlFor="dk-cmd">Command</label>
        <input id="dk-cmd" type="text" placeholder="optional; arguments after the image, quotes group words" value={command} onChange={(e) => setCommand(e.target.value)} spellCheck={false} autoCapitalize="off" />
        <div className="help">Not run through a shell: <span className="mono">$VAR</span> and <span className="mono">;</span> are passed literally. Privileged mode, capabilities and devices need a stack.</div></div>
    </Modal>
  );
}

function Rows<T>({ label, help, items, blank, onChange, render }: {
  label: string; help: string; items: T[]; blank: T; onChange: (v: T[]) => void; render: (item: T, set: (v: T) => void) => React.ReactNode;
}) {
  return (
    <fieldset className="dk-rows">
      <legend>{label}</legend>
      {items.map((it, i) => (
        <div className="dk-row" key={i}>
          {render(it, (v) => onChange(items.map((x, k) => (k === i ? v : x))))}
          <button className="btn sm" aria-label={`Remove ${label.toLowerCase()} row`} onClick={() => onChange(items.length > 1 ? items.filter((_, k) => k !== i) : [blank])}>✕</button>
        </div>
      ))}
      <button className="btn sm" onClick={() => onChange([...items, blank])}>+ Add</button>
      <div className="help">{help}</div>
    </fieldset>
  );
}
