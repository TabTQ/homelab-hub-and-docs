import os
import subprocess
import sys
import tempfile
import unittest

import yaml

sys.path.insert(0, os.path.dirname(__file__))
import convert  # noqa: E402

SERVICES = """
- Proxmox:
    - Proxmox VE (Tailnet):
        icon: proxmox.svg
        href: https://pve.example.ts.net
        widget:
          type: proxmox
          url: https://192.168.10.102:8006
          username: hub@pve!homepage
          password: SUPERSECRET-ONE
          node: pve
    - Raspberry Pi:
        - Grafana:
            icon: grafana.png
            href: http://192.168.10.101:3000/
        - Weird:
            href: javascript:alert(1)
            icon: "<img src=x>"
- Media:
    - qBittorrent:
        icon: qbittorrent.svg
        href: http://192.168.10.131:8090/
        widget:
          type: qbittorrent
          url: http://192.168.10.131:8090
          username: admin
          password: SUPERSECRET-TWO
          fields: [leech]
    - Plex:
        href: http://x
        widget: {type: plex, url: "http://x", key: SUPERSECRET-THREE}
- Power Management:
    - WOL Proxmox:
        icon: /images/power-system.png
        href: http://192.168.10.101:3002/wake/mac/AA:BB:CC:DD:EE:01?target=Proxmox%20Server
        ping: 192.168.10.102
    - Shutdown Proxmox:
        href: http://192.168.10.101:3002/node/pve/shutdown
    - Container Toggles:
        - Toggle Apps LXC:
            href: http://192.168.10.101:3002/toggle/pve/lxc/100
            ping: 192.168.10.130
"""


class ConvertTest(unittest.TestCase):
    def run_convert(self, services=SERVICES, widgets=None, settings=None):
        with tempfile.TemporaryDirectory() as d:
            paths = {}
            for name, content in (("s", services), ("w", widgets), ("t", settings)):
                if content is not None:
                    p = os.path.join(d, name + ".yaml")
                    open(p, "w").write(content)
                    paths[name] = p
            out, sec = os.path.join(d, "dashboard.yaml"), os.path.join(d, "secrets.env.template")
            args = ["--services", paths["s"], "--out", out, "--secrets-template", sec]
            if "w" in paths:
                args += ["--widgets", paths["w"]]
            if "t" in paths:
                args += ["--settings", paths["t"]]
            convert.main(args)
            return open(out).read(), open(sec).read()

    def test_output_is_valid_yaml_with_the_expected_structure(self):
        out, _ = self.run_convert()
        doc = yaml.safe_load(out)
        self.assertEqual(doc["version"], 1)
        self.assertEqual([g["name"] for g in doc["groups"]], ["Proxmox", "Media", "Power Management"])
        pve = doc["groups"][0]["items"][0]
        self.assertEqual(pve["widget"]["type"], "proxmox")
        self.assertEqual(pve["widget"]["password"], "${secret:PVE_PASSWORD}")
        pi = doc["groups"][0]["items"][1]
        self.assertEqual(pi["name"], "Raspberry Pi")
        self.assertEqual(pi["items"][0]["href"], "http://192.168.10.101:3000/")

    def test_no_credential_ever_reaches_the_output_or_the_template(self):
        out, sec = self.run_convert()
        for secret in ("SUPERSECRET-ONE", "SUPERSECRET-TWO", "SUPERSECRET-THREE"):
            self.assertNotIn(secret, out)
            self.assertNotIn(secret, sec)
        self.assertIn("PVE_PASSWORD=\n", sec)
        self.assertIn("QBITTORRENT_PASSWORD=\n", sec)

    def test_dangerous_links_and_icons_are_dropped(self):
        out, _ = self.run_convert()
        self.assertNotIn("javascript", out)
        self.assertNotIn("<img", out)

    def test_wol_links_become_actions_and_the_item_stays_as_a_button(self):
        out, _ = self.run_convert()
        doc = yaml.safe_load(out)
        actions = {a["id"]: a for a in doc["actions"]}
        self.assertEqual(actions["wol-proxmox"]["type"], "wol")
        self.assertEqual(actions["wol-proxmox"]["mac"], "AA:BB:CC:DD:EE:01")
        self.assertEqual(actions["wol-proxmox"]["status"], {"icmp": "192.168.10.102"})
        self.assertEqual(actions["shutdown-proxmox"], {"id": "shutdown-proxmox", "label": "Shutdown Proxmox", "type": "proxmox-node", "node": "pve", "command": "shutdown"})
        toggle = actions["toggle-apps-lxc"]
        self.assertEqual((toggle["type"], toggle["kind"], toggle["vmid"]), ("proxmox-guest", "lxc", 100))
        power = doc["groups"][2]["items"]
        self.assertEqual(power[0]["action"], "wol-proxmox")
        self.assertNotIn("href", power[0])
        self.assertEqual(power[2]["items"][0]["action"], "toggle-apps-lxc")

    def test_unsupported_widgets_are_reported_and_left_out(self):
        out, _ = self.run_convert()
        doc = yaml.safe_load(out)
        plex = doc["groups"][1]["items"][1]
        self.assertEqual(plex["name"], "Plex")
        self.assertNotIn("widget", plex)

    def test_settings_title_and_info_widgets(self):
        out, _ = self.run_convert(widgets="- resources:\n    cpu: true\n    memory: true\n    disk: /\n    cputemp: true\n    units: metric\n    refresh: 3000\n- search:\n    provider: google\n    target: _blank\n- weather: {}\n",
                                  settings="title: Tabish's Homelab Dashboard\n")
        doc = yaml.safe_load(out)
        self.assertEqual(doc["title"], "Tabish's Homelab Dashboard")
        self.assertEqual(doc["widgets"][0]["resources"]["disk"], "/")
        self.assertEqual(doc["widgets"][1]["search"]["provider"], "google")
        self.assertEqual(len(doc["widgets"]), 2)

    def test_names_that_yaml_would_misread_survive(self):
        out, _ = self.run_convert(services="- Group:\n    - 'No': {href: 'http://x'}\n    - 'on': {href: 'http://y'}\n    - '007': {}\n")
        doc = yaml.safe_load(out)
        self.assertEqual([i["name"] for i in doc["groups"][0]["items"]], ["No", "on", "007"])

    def test_duplicate_action_names_get_unique_ids(self):
        svc = "- G:\n    - A:\n        href: http://h/node/pve/reboot\n    - A:\n        href: http://h/node/pve/reboot\n"
        out, _ = self.run_convert(services=svc)
        ids = [a["id"] for a in yaml.safe_load(out)["actions"]]
        self.assertEqual(len(ids), len(set(ids)))

    def test_empty_and_odd_inputs_do_not_crash(self):
        for svc in ("", "[]", "- just a string\n", "- G: nope\n", "- {A: 1, B: 2}\n"):
            out, _ = self.run_convert(services=svc)
            yaml.safe_load(out)

    def test_the_second_password_gets_its_own_secret_name(self):
        svc = ("- G:\n    - A:\n        widget: {type: qbittorrent, url: 'http://a', username: u, password: x}\n"
               "    - B:\n        widget: {type: qbittorrent, url: 'http://b', username: u, password: y}\n")
        _, sec = self.run_convert(services=svc)
        names = [l.split("=")[0] for l in sec.splitlines() if "=" in l]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(len(names), 2)


if __name__ == "__main__":
    unittest.main()
