#!/usr/bin/env python3
"""Convert a gethomepage config (services.yaml [+ widgets.yaml, settings.yaml]) into homelab-hub's dashboard.yaml.

    python convert.py --services services.yaml [--widgets widgets.yaml] [--settings settings.yaml] \
                      --out dashboard.yaml --secrets-template secrets.env.template

Credentials are NEVER copied. Every widget password becomes ${secret:NAME}; the template lists the NAMEs with empty
values. Links that call wol-server (/wake/mac/.., /toggle/.., /node/../shutdown|reboot) become actions, and the item
stays in its group as a button (`action: id`). Anything that could not be converted is listed in the report on stderr.
"""
import argparse
import re
import sys
from urllib.parse import parse_qs, unquote, urlparse

import yaml

WIDGET_TYPES = {"proxmox", "proxmoxbackupserver", "filebrowser", "qbittorrent"}
SECRET_PREFIX = {"proxmox": "PVE", "proxmoxbackupserver": "PBS", "filebrowser": "FILEBROWSER", "qbittorrent": "QBITTORRENT"}
WOL = re.compile(r"^/wake/mac/([0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5})$")
TOGGLE = re.compile(r"^/toggle/([A-Za-z0-9.-]+)/(lxc|qemu)/(\d+)$", re.I)
NODE = re.compile(r"^/node/([A-Za-z0-9.-]+)/(shutdown|reboot)$")
ICON_OK = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]{0,119}$|^/[A-Za-z0-9._/-]{1,119}$")
HOST_OK = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,252}$")


def slug(text, sep="-"):
    s = re.sub(r"[^a-z0-9]+", sep, text.lower()).strip(sep)
    return s or "x"


def q(value):
    """A YAML double-quoted scalar. Always quoting keeps 'No', 'on' and '007' as text."""
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'


class Converter:
    def __init__(self):
        self.actions = []
        self.action_ids = set()
        self.secrets = []          # secret names in order
        self.report = []           # human readable notes
        self.item_count = 0

    # ---------------------------------------------------------------- helpers
    def note(self, msg):
        self.report.append(msg)

    def unique_action_id(self, base):
        base = slug(base)[:60]
        cand, n = base, 2
        while cand in self.action_ids:
            cand = f"{base}-{n}"
            n += 1
        self.action_ids.add(cand)
        return cand

    def secret_name(self, wtype, item_name):
        base = f"{SECRET_PREFIX[wtype]}_PASSWORD"
        if base not in self.secrets:
            self.secrets.append(base)
            return base
        cand = f"{SECRET_PREFIX[wtype]}_{slug(item_name, '_').upper()}_PASSWORD"
        n = 2
        name = cand
        while name in self.secrets:
            name = f"{cand}_{n}"
            n += 1
        self.secrets.append(name)
        return name

    # ---------------------------------------------------------------- services
    def convert_services(self, doc):
        groups = []
        for entry in doc or []:
            if not isinstance(entry, dict) or len(entry) != 1:
                self.note(f"Skipped an unrecognised top-level entry: {str(entry)[:60]}")
                continue
            (gname, body), = entry.items()
            groups.append((str(gname), self.entries(body if isinstance(body, list) else [], str(gname))))
        return groups

    def entries(self, body, path):
        out = []
        for e in body:
            if not isinstance(e, dict) or len(e) != 1:
                self.note(f"{path}: skipped an unrecognised entry")
                continue
            (name, val), = e.items()
            name = str(name)
            if isinstance(val, list):
                out.append(("group", name, self.entries(val, f"{path} / {name}")))
            elif isinstance(val, dict):
                out.append(("item", name, self.item(name, val, path)))
            else:
                out.append(("item", name, {}))
        return out

    def item(self, name, v, path):
        self.item_count += 1
        it = {}
        href = v.get("href")
        action = None
        if isinstance(href, str):
            action = self.as_action(name, href, v)
        if action:
            it["action"] = action
        elif isinstance(href, str):
            if re.match(r"^https?://", href) or (href.startswith("/") and not href.startswith("//")):
                it["href"] = href
            else:
                self.note(f"{path} / {name}: dropped href '{href[:40]}' (only http(s) links and /paths are allowed)")
        icon = v.get("icon")
        if isinstance(icon, str):
            if ICON_OK.match(icon) or re.match(r"^https?://", icon):
                it["icon"] = icon
            else:
                self.note(f"{path} / {name}: dropped icon '{icon[:40]}'")
        desc = v.get("description")
        if isinstance(desc, str) and desc.strip():
            it["description"] = desc.strip()[:200]
        status = self.status(v, name, path)
        if status and not action:
            it["status"] = status
        elif status and action:
            it["_action_status"] = status
        w = v.get("widget")
        if isinstance(w, dict):
            widget = self.widget(name, w, path)
            if widget:
                it["widget"] = widget
        return it

    def status(self, v, name, path):
        ping = v.get("ping")
        if isinstance(ping, str):
            host = re.sub(r"^https?://", "", ping).split("/")[0].split(":")[0] if "://" in ping else ping
            if HOST_OK.match(host):
                return {"icmp": host}
            self.note(f"{path} / {name}: dropped ping '{ping[:40]}'")
        mon = v.get("siteMonitor")
        if isinstance(mon, str) and re.match(r"^https?://", mon):
            return {"http": mon}
        return None

    def as_action(self, name, href, v):
        p = urlparse(href)
        path = p.path
        m = WOL.match(path)
        if m:
            target = parse_qs(p.query).get("target", [name])[0]
            aid = self.unique_action_id(name)
            mac = m.group(1).replace("-", ":").upper()
            self.actions.append({"id": aid, "label": name, "type": "wol", "mac": mac, "_target": unquote(target), "_status": self.status(v, name, "")})
            return aid
        m = NODE.match(path)
        if m:
            aid = self.unique_action_id(name)
            self.actions.append({"id": aid, "label": name, "type": "proxmox-node", "node": m.group(1), "command": m.group(2), "_status": self.status(v, name, "")})
            return aid
        m = TOGGLE.match(path)
        if m:
            aid = self.unique_action_id(name)
            self.actions.append({"id": aid, "label": name, "type": "proxmox-guest", "node": m.group(1), "kind": m.group(2).lower(),
                                 "vmid": int(m.group(3)), "_status": self.status(v, name, "")})
            return aid
        return None

    def widget(self, name, w, path):
        wtype = str(w.get("type", "")).lower()
        if wtype not in WIDGET_TYPES:
            self.note(f"{path} / {name}: widget type '{wtype}' is not supported yet and was left out")
            return None
        url = w.get("url")
        if not isinstance(url, str) or not re.match(r"^https?://", url):
            self.note(f"{path} / {name}: widget has no usable url and was left out")
            return None
        out = {"type": wtype, "url": url}
        if wtype == "proxmox":
            out["node"] = str(w.get("node", "pve"))
        if url.startswith("https://"):
            out["tls"] = "insecure"
            self.note(f"{path} / {name}: widget uses https, set to tls: insecure like homepage did. Pin its certificate with tls: pinned + fingerprint when you can")
        user = w.get("username")
        out["username"] = str(user) if user is not None else "CHANGE_ME"
        if user is None:
            self.note(f"{path} / {name}: widget had no username, put the right one in")
        out["password"] = "${secret:%s}" % self.secret_name(wtype, name)
        for k in w:
            if k not in {"type", "url", "username", "password", "node"}:
                self.note(f"{path} / {name}: widget option '{k}' was not carried over")
        return out

    # ---------------------------------------------------------------- output
    def render(self, title, groups, widgets_doc):
        L = ["# Converted from a gethomepage config. Review it, then edit it in the browser.",
             "# Credentials are ${secret:NAME} references; put NAME=value in secrets.env (see secrets.env.template).",
             "version: 1", f"title: {q(title)}", "defaults:", "  status: none", "  interval: 30", ""]
        L += self.render_widgets(widgets_doc)
        L.append("groups:")
        for gname, ents in groups:
            L.append(f"  - name: {q(gname)}")
            L.append("    items:")
            L += self.render_entries(ents, 6)
        if not groups:
            L[-1] = "groups: []"
        L.append("")
        L.append("actions:" if self.actions else "actions: []")
        for a in self.actions:
            L += self.render_action(a)
        return "\n".join(L) + "\n"

    def render_widgets(self, doc):
        out = []
        if not isinstance(doc, list):
            return out
        rows = []
        for w in doc:
            if not isinstance(w, dict) or len(w) != 1:
                continue
            (t, o), = w.items()
            o = o if isinstance(o, dict) else {}
            if t == "resources":
                parts = []
                for k in ("cpu", "memory", "cputemp", "uptime", "network"):
                    if k in o:
                        parts.append(f"{k}: {'true' if o[k] else 'false'}")
                disk = o.get("disk")
                if isinstance(disk, str) and disk.startswith("/"):
                    parts.append(f"disk: {disk}")
                if o.get("units") in ("metric", "imperial"):
                    parts.append(f"units: {o['units']}")
                rows.append("  - resources: {" + ", ".join(parts) + "}")
            elif t == "search":
                prov = o.get("provider", "google")
                prov = prov if prov in ("google", "duckduckgo", "bing") else "google"
                tgt = o.get("target", "_blank")
                tgt = tgt if tgt in ("_blank", "_self") else "_blank"
                rows.append("  - search: {" + f"provider: {prov}, target: {tgt}" + "}")
            else:
                self.note(f"widgets.yaml: info widget '{t}' is not supported and was left out")
        if rows:
            out.append("widgets:")
            out += rows
            out.append("")
        return out

    def render_entries(self, ents, indent):
        pad = " " * indent
        L = []
        for kind, name, val in ents:
            if kind == "group":
                L.append(f"{pad}- name: {q(name)}")
                L.append(f"{pad}  items:")
                L += self.render_entries(val, indent + 4)
                continue
            L.append(f"{pad}- name: {q(name)}")
            for key in ("href", "action", "icon", "description"):
                if key in val:
                    L.append(f"{pad}  {key}: {q(val[key])}")
            if "status" in val:
                L.append(f"{pad}  status: {self.flow(val['status'])}")
            if "widget" in val:
                L.append(f"{pad}  widget: {self.flow(val['widget'])}")
        return L

    @staticmethod
    def flow(d):
        return "{" + ", ".join(f"{k}: {q(v)}" if not isinstance(v, int) else f"{k}: {v}" for k, v in d.items()) + "}"

    def render_action(self, a):
        L = [f"  - id: {a['id']}", f"    label: {q(a['label'])}", f"    type: {a['type']}"]
        for k in ("mac", "node", "command", "kind"):
            if k in a:
                L.append(f"    {k}: {q(a[k])}")
        if "vmid" in a:
            L.append(f"    vmid: {a['vmid']}")
        if a.get("_status"):
            L.append(f"    status: {self.flow(a['_status'])}")
        return L


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--services", required=True, help="path or - for stdin")
    ap.add_argument("--widgets")
    ap.add_argument("--settings")
    ap.add_argument("--out", required=True)
    ap.add_argument("--secrets-template", required=True)
    args = ap.parse_args(argv)

    def load(path):
        if path is None:
            return None
        text = sys.stdin.read() if path == "-" else open(path, encoding="utf-8").read()
        return yaml.safe_load(text)

    c = Converter()
    services = load(args.services)
    widgets = load(args.widgets)
    settings = load(args.settings) or {}
    title = str(settings.get("title", "Homelab")) if isinstance(settings, dict) else "Homelab"
    groups = c.convert_services(services)
    text = c.render(title, groups, widgets)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(text)
    with open(args.secrets_template, "w", encoding="utf-8") as f:
        f.write("# Fill in the values and save this file as secrets.env (chmod 600). Never commit it.\n")
        for name in c.secrets:
            f.write(f"{name}=\n")
    print(f"Converted {c.item_count} items, {len(c.actions)} actions, {len(c.secrets)} secret names.", file=sys.stderr)
    for n in c.report:
        print(" - " + n, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
