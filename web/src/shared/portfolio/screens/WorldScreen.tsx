import { useState } from "react";
import { deviceMatches, isActive, matches } from "../../filter";
import { deviceLinks } from "../../util";
import type { Device, NetEntity } from "../../types";
import { usePortfolio } from "../context";
import { ExtLinks, FilterBar, ScreenTitle, SkillChips } from "./parts";
import "./screens.css";

const human = (s: string) => s.replace(/[_-]+/g, " ").replace(/^./, (c) => c.toUpperCase());
const NET_TITLES: Record<string, string> = { isp: "Uplinks", vlan: "VLANs", switch: "Switches" };
const NET_KEYS: Record<string, string> = { spec: "Spec", subnet: "Subnet", role: "Role", type: "Type" };

function Icon({ cat }: { cat: string }) {
  if (cat.includes("proxmox")) return <svg viewBox="0 0 32 32" fill="currentColor" aria-hidden="true"><path d="M5 5h22v8H5zm0 11h22v8H5zM9 8v2h2V8zm0 11v2h2v-2zM14 26h4v3h-4z" /></svg>;
  if (cat.includes("raspberry")) return <svg viewBox="0 0 32 32" fill="currentColor" aria-hidden="true"><path d="M9 9h14v14H9zM13 3h2v4h-2zm4 0h2v4h-2zm-4 22h2v4h-2zm4 0h2v4h-2zM3 13h4v2H3zm0 4h4v2H3zm22-4h4v2h-4zm0 4h4v2h-4z" /></svg>;
  if (cat.includes("network")) return <svg viewBox="0 0 32 32" fill="none" stroke="currentColor" strokeWidth="2.4" aria-hidden="true"><circle cx="16" cy="16" r="11" /><path d="M5 16h22M16 5c5 6 5 16 0 22M16 5c-5 6-5 16 0 22" /></svg>;
  return <svg viewBox="0 0 32 32" fill="currentColor" aria-hidden="true"><path d="M6 4h20v24H6zm4 4v6h12V8zm6 10a4 4 0 100 8 4 4 0 000-8z" /></svg>;
}

/** One expandable row: a gem, a title, an optional tag, and detail fields shown when opened. */
function Item({ gem, title, tag, dim, fields, skills }: { gem: string; title: string; tag?: string; dim: boolean; fields: [string, string][]; skills: string[] }) {
  const [open, setOpen] = useState(false);
  const more = fields.length > 0 || skills.length > 0;
  const head = (
    <>
      <span className={"gem " + gem} aria-hidden="true" />
      <span className="t"><b>{title}</b></span>
      {tag && <span className="tag">{tag}</span>}
    </>
  );
  return (
    <li className={dim ? "sc-dim" : undefined}>
      {more ? <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>{head}</button> : <span className="row">{head}</span>}
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
  const { filter } = usePortfolio();
  const active = isActive(filter);
  const dim = active && !deviceMatches(filter, d);
  const d2 = (x: { skills: string[]; t: string }) => active && !matches(filter, x);
  const netTypes = [...new Set(d.network.map((n) => n.type))];
  return (
    <section id={"dev-" + d.id} tabIndex={-1} className={"panel sc-dev" + (dim ? " sc-dim" : "")} aria-label={d.name}>
      <header><h2 className="sc-h2">{d.name}</h2>{d.ip && <span className="mono sc-ip">{d.ip}</span>}</header>
      <div className="eyebrow sc-eb">{human(d.cat)}</div>
      {d.summary && <p className="sc-text">{d.summary}</p>}
      <ExtLinks links={deviceLinks(d)} />
      {d.hw.length > 0 && <dl className="stats one sc-hw">{d.hw.map((h, i) => <div key={i}><dt>{h.component}</dt><dd>{h.spec}</dd></div>)}</dl>}
      <SkillChips skills={d.skills} />
      {d.containers.length > 0 && (
        <>
          <div className="eyebrow sc-sub">Containers</div>
          <ul className="rows">
            {d.containers.map((c) => (
              <Item key={c.id} gem={c.status === "Active" ? "up" : "warn"} title={c.name} tag={c.status} dim={d2(c)} skills={c.skills}
                fields={[["ID", c.id.replace("lxc-", "")], ...(c.purpose ? [["Purpose", c.purpose] as [string, string]] : []), ...(c.res ? [["Resources", c.res] as [string, string]] : [])]} />
            ))}
          </ul>
        </>
      )}
      {d.services.length > 0 && (
        <>
          <div className="eyebrow sc-sub">Services</div>
          <ul className="rows">
            {d.services.map((s) => (
              <Item key={s.id} gem="up" title={s.name} dim={d2(s)} skills={s.skills}
                fields={[...(s.fn ? [["Function", s.fn] as [string, string]] : []), ...(s.ct ? [["Container", s.ct] as [string, string]] : []), ...(s.port ? [["Port", s.port] as [string, string]] : [])]} />
            ))}
          </ul>
        </>
      )}
      {netTypes.map((t) => (
        <div key={t}>
          <div className="eyebrow sc-sub">{NET_TITLES[t] ?? human(t)}</div>
          <ul className="rows">
            {d.network.filter((n) => n.type === t).map((n) => <Item key={n.id} gem="up" title={n.name} dim={d2(n)} skills={n.skills} fields={netFields(n)} />)}
          </ul>
        </div>
      ))}
    </section>
  );
}

export function WorldScreen() {
  const { data } = usePortfolio();
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
              <button key={d.id} type="button" className="banner hang sc-banner" onClick={() => jump(d.id)}><Icon cat={d.cat} />{d.name}</button>
            ))}
          </div>
          <div className="grid cols-2 sc-devs">{data.homelab.map((d) => <DevicePanel key={d.id} d={d} />)}</div>
        </>
      )}
    </div>
  );
}
