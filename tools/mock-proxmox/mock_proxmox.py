#!/usr/bin/env python3
"""A small stand-in for the Proxmox API, for local end-to-end tests only. Never point real credentials at it.

Serves the subset homelab-hub uses, with responses shaped like the recorded real ones (contract/proxmox-fixtures):
  GET  /api2/json/nodes/{n}/status | lxc | qemu | {lxc|qemu}/{id}/status/current | tasks/{upid}/status
  POST /api2/json/nodes/{n}/{lxc|qemu}/{id}/status/{start|stop|shutdown}   (starts a fake task that flips the state)
  POST /api2/json/nodes/{n}/status          command=shutdown|reboot        (recorded, nothing else happens)
Test hooks: GET /__mock/log (what was requested), POST /__mock/reset.
Requests without an "Authorization: PVEAPIToken=..." header get 401, like the real API.
"""
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "..", "..", "hub-internal", "src", "test", "resources", "fixtures", "proxmox")
TASK_SECONDS = float(os.environ.get("MOCK_TASK_SECONDS", "1.5"))

LOCK = threading.Lock()
LOG = []
TASKS = {}


def load(name):
    with open(os.path.join(FIX, name), encoding="utf-8") as f:
        return json.load(f)


def fresh_guests():
    return {g["vmid"]: dict(g) for g in load("lxc-list.json")["data"]}


GUESTS = fresh_guests()
NODE_STATUS = load("node-status.json")["data"]


def upid(node, vmid, kind):
    return f"UPID:{node}:0001A2B3:00C0FFEE:{int(time.time()):08X}:{kind}:{vmid}:hub@pve!local:"


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authed(self):
        if self.headers.get("Authorization", "").startswith("PVEAPIToken="):
            return True
        self.send(401, {"data": None})
        return False

    def do_GET(self):
        path = unquote(self.path.split("?")[0])
        if path == "/__mock/log":
            with LOCK:
                return self.send(200, LOG)
        if not path.startswith("/api2/json/"):
            return self.send(404, {"message": "not found"})
        if not self.authed():
            return
        p = path[len("/api2/json/"):].split("/")
        with LOCK:
            if p == ["version"]:
                return self.send(200, {"data": {"version": "8.2.4", "release": "8.2", "repoid": "mock"}})
            if p[0] == "nodes" and len(p) == 3 and p[2] == "status":
                return self.send(200, {"data": NODE_STATUS})
            if p[0] == "nodes" and len(p) == 3 and p[2] == "lxc":
                return self.send(200, {"data": sorted(GUESTS.values(), key=lambda g: g["vmid"])})
            if p[0] == "nodes" and len(p) == 3 and p[2] == "qemu":
                return self.send(200, {"data": []})
            if p[0] == "nodes" and len(p) == 6 and p[2] in ("lxc", "qemu") and p[4:] == ["status", "current"] or (len(p) == 6 and p[5] == "current"):
                vmid = int(p[3])
                if p[2] == "lxc" and vmid in GUESTS:
                    return self.send(200, {"data": {"status": GUESTS[vmid]["status"], "vmid": vmid}})
                return self.send(500, {"data": None, "message": f"Configuration file 'nodes/{p[1]}/{p[2]}/{vmid}.conf' does not exist\n"})
            if p[0] == "nodes" and len(p) == 5 and p[2] == "tasks" and p[4] == "status":
                t = TASKS.get(p[3])
                if not t:
                    return self.send(500, {"data": None, "message": "no such task"})
                done = time.time() >= t["until"]
                if done and not t["applied"]:
                    GUESTS[t["vmid"]]["status"] = t["result"]
                    t["applied"] = True
                return self.send(200, {"data": {"status": "stopped" if done else "running", **({"exitstatus": "OK"} if done else {})}})
        return self.send(404, {"message": "not found"})

    def do_POST(self):
        path = unquote(self.path.split("?")[0])
        length = int(self.headers.get("Content-Length", "0") or 0)
        raw = self.rfile.read(length).decode() if length else ""
        if path == "/__mock/reset":
            with LOCK:
                GUESTS.clear()
                GUESTS.update(fresh_guests())
                LOG.clear()
                TASKS.clear()
            return self.send(200, {"ok": True})
        if not path.startswith("/api2/json/"):
            return self.send(404, {"message": "not found"})
        if not self.authed():
            return
        p = path[len("/api2/json/"):].split("/")
        with LOCK:
            LOG.append({"method": "POST", "path": path, "body": raw})
            if p[0] == "nodes" and len(p) == 3 and p[2] == "status":
                cmd = parse_qs(raw).get("command", [""])[0]
                if cmd not in ("shutdown", "reboot"):
                    return self.send(400, {"message": "unknown command"})
                return self.send(200, {"data": None})
            if p[0] == "nodes" and len(p) == 6 and p[2] == "lxc" and p[4] == "status" and p[5] in ("start", "stop", "shutdown"):
                vmid = int(p[3])
                if vmid not in GUESTS:
                    return self.send(500, {"data": None, "message": f"Configuration file 'nodes/{p[1]}/lxc/{vmid}.conf' does not exist\n"})
                cur = GUESTS[vmid]["status"]
                want = "running" if p[5] == "start" else "stopped"
                if cur == want:
                    return self.send(500, {"data": None, "message": f"CT {vmid} already {cur}\n"})
                u = upid(p[1], vmid, "vz" + p[5])
                TASKS[u] = {"vmid": vmid, "until": time.time() + TASK_SECONDS, "result": want, "applied": False}
                return self.send(200, {"data": u})
        return self.send(404, {"message": "not found"})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8907
    srv = ThreadingHTTPServer(("127.0.0.1", port), H)
    print(f"mock proxmox on http://127.0.0.1:{port}/api2/json", flush=True)
    srv.serve_forever()
