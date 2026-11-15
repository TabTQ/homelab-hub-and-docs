import { useState } from "react";
import { deviceMatches, isActive, matches } from "../../filter";
import { deviceLinks } from "../../util";
import type { Device, NetEntity } from "../../types";
import { usePortfolio } from "../context";
import { FALLBACK_ICON, VhIcon, resolveNameIcon } from "../icons";
import { AddBtn, EditBtn, ExtLinks, FilterBar, ScreenTitle, SkillChips } from "./parts";
import "./screens.css";

const human = (s: string) => s.replace(/[_-]+/g, " ").replace(/^./, (c) => c.toUpperCase());
const NET_TITLES: Record<string, string> = { isp: "Uplinks", vlan: "VLANs", switch: "Switches" };
const NET_KEYS: Record<string, string> = { spec: "Spec", subnet: "Subnet", role: "Role", type: "Type" };

/** Device icon: from the category first (proxmox, raspberry pi, network), else from the name. */
function deviceIcon(d: Device): string {
  const byCat = resolveNameIcon(d.cat);
  return byCat !== FALLBACK_ICON ? byCat : resolveNameIcon(d.name);
}

/** One expandable row: a gem, a title, an optional tag, and detail fields shown when opened. */
function Item({ gem, title, tag, dim, fields, skills, editor }: { gem: string; title: string; tag?: string; dim: boolean; fields: [string, string][]; skills: string[]; editor?: { label: string; resource: string; id: number } }) {
  const { edit } = usePortfolio();
  const [open, setOpen] = useState(false);
  const more = fields.length > 0 || skills.length > 0;
  const head = (
    <>
      <span className={"gem " + gem} aria-hidden="true" />
      <span className="t"><b>{title}</b></span>
      {tag && <span className="tag">{tag}</span>}
    </>
  );
  const row = more ? <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>{head}</button> : <span className="row">{head}</span>;
  return (
    <li className={dim ? "sc-dim" : undefined}>
      {edit && editor ? <div className="ed-line">{row}<EditBtn label={editor.label} resource={editor.resource} id={editor.id} /></div> : row}
      {more && open && (
        <div className="sc-more">
          {fields.length > 0 && <dl className="stats one">{fields.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>}
          <SkillChips skills={skills} />
        </div>
      )}
    </li>
  );
}

const netFields = (n: NetEntity): [string, string][] =>
  Object.entries(n.spec).filter(([, v]) => v !== null && v !== undefined && String(v) !== "").map(([k, v]) => [NET_KEYS[k] ?? human(k), String(v)]);

function DevicePanel({ d }: { d: Device }) {
  const { filter, edit } = usePortfolio();
  const active = isActive(filter);
  const dim = active && !deviceMatches(filter, d);
  const d2 = (x: { skills: string[]; t: string }) => active && !matches(filter, x);
  const netTypes = [...new Set(d.network.map((n) => n.type))];
  return (
    <section id={"dev-" + d.id} tabIndex={-1} className={"panel sc-dev" + (dim ? " sc-dim" : "")} aria-label={d.name}>
      <header><h2 className="sc-h2"><VhIcon name={deviceIcon(d)} size={22} className="sc-hic" />{d.name}</h2>{d.ip && <span className="mono sc-ip">{d.ip}</span>}</header>
      <div className="eyebrow sc-eb">{human(d.cat)}</div>
      <div className="ed-row">
        <EditBtn label={`device: ${d.name}`} resource="homelab-devices" id={d.dbId} text="Edit device" />
        <EditBtn label={`hardware: ${d.name}`} aria={`Edit hardware rows of ${d.name}`} resource="homelab-devices" id={d.dbId} text="Edit hardware" />
      </div>
      {d.summary && <p className="sc-text">{d.summary}</p>}
      <ExtLinks links={deviceLinks(d)} />
      {d.hw.length > 0 && <dl className="stats one sc-hw">{d.hw.map((h, i) => <div key={i}><dt>{h.component}</dt><dd>{h.spec}</dd></div>)}</dl>}
      <SkillChips skills={d.skills} />
      {(d.containers.length > 0 || edit) && (
        <>
          <div className="eyebrow sc-sub">Containers</div>
          <ul className="rows">
            {d.containers.map((c) => (
              <Item key={c.id} gem={c.status === "Active" ? "up" : "warn"} title={c.name} tag={c.status} dim={d2(c)} skills={c.skills} editor={{ label: `container: ${c.name}`, resource: "homelab-containers", id: c.dbId }}
                fields={[["ID", c.id.replace("lxc-", "")], ...(c.purpose ? [["Purpose", c.purpose] as [string, string]] : []), ...(c.res ? [["Resources", c.res] as [string, string]] : [])]} />
            ))}
          </ul>
          <div className="ed-row"><AddBtn label="container" resource="homelab-containers" parent={{ api: "deviceId", id: d.dbId }} /></div>
        </>
      )}
      {(d.services.length > 0 || edit) && (
        <>
          <div className="eyebrow sc-sub">Services</div>
          <ul className="rows">
            {d.services.map((s) => (
              <Item key={s.id} gem="up" title={s.name} dim={d2(s)} skills={s.skills} editor={{ label: `service: ${s.name}`, resource: "homelab-services", id: s.dbId }}
                fields={[...(s.fn ? [["Function", s.fn] as [string, string]] : []), ...(s.ct ? [["Container", s.ct] as [string, string]] : []), ...(s.port ? [["Port", s.port] as [string, string]] : [])]} />
            ))}
          </ul>
          <div className="ed-row"><AddBtn label="service" resource="homelab-services" parent={{ api: "deviceId", id: d.dbId }} /></div>
        </>
      )}
      {netTypes.map((t) => (
        <div key={t}>
          <div className="eyebrow sc-sub">{NET_TITLES[t] ?? human(t)}</div>
          <ul className="rows">
            {d.network.filter((n) => n.type === t).map((n) => <Item key={n.id} gem="up" title={n.name} dim={d2(n)} skills={n.skills} fields={netFields(n)} editor={{ label: `network entry: ${n.name}`, resource: "homelab-network", id: n.dbId }} />)}
          </ul>
        </div>
      ))}
      {edit && <div className="ed-row"><AddBtn label="network entry" resource="homelab-network" parent={{ api: "deviceId", id: d.dbId }} /></div>}
    </section>
  );
}

export function WorldScreen() {
  const { data, edit } = usePortfolio();
  const total = data.homelab.reduce((n, d) => n + d.services.length + d.containers.length, 0);
  const jump = (id: string) => {
    const el = document.getElementById("dev-" + id);
    el?.scrollIntoView?.({ behavior: "smooth", block: "start" });
    (el as HTMLElement | null)?.focus?.({ preventScroll: true });
  };
  return (
    <div className="sc">
      <ScreenTitle eyebrow="Homelab" title="Settlement" sub="The machines and services I run at home. Select a banner to jump to a machine."
        side={total > 0 ? <div className="power"><div className="eyebrow">Services and containers</div><div className="num">{total}</div></div> : undefined} />
      <FilterBar />
      {data.homelab.length === 0 ? <p className="sub">No devices listed yet.</p> : (
        <>
          <div className="sc-banners" role="group" aria-label="Devices">
            {data.homelab.map((d) => (
              <button key={d.id} type="button" className="banner hang sc-banner" onClick={() => jump(d.id)}><VhIcon name={deviceIcon(d)} size={34} />{d.name}</button>
            ))}
          </div>
          <div className="grid cols-2 sc-devs">{data.homelab.map((d) => <DevicePanel key={d.id} d={d} />)}</div>
        </>
      )}
      {edit && <div className="ed-row"><AddBtn label="device" resource="homelab-devices" /></div>}
    </div>
  );
}
