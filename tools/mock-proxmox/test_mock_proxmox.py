import json
import os
import subprocess
import sys
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 18907
BASE = f"http://127.0.0.1:{PORT}"
AUTH = {"Authorization": "PVEAPIToken=hub@pve!local=x"}


def call(path, method="GET", data=None, headers=AUTH):
    req = urllib.request.Request(BASE + path, method=method, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


class MockTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        env = dict(os.environ, MOCK_TASK_SECONDS="0.3")
        cls.p = subprocess.Popen([sys.executable, os.path.join(HERE, "mock_proxmox.py"), str(PORT)], env=env, stdout=subprocess.DEVNULL)
        for _ in range(50):
            try:
                call("/api2/json/version")
                return
            except Exception:
                time.sleep(0.1)
        raise RuntimeError("mock did not start")

    @classmethod
    def tearDownClass(cls):
        cls.p.terminate()

    def setUp(self):
        call("/__mock/reset", "POST", b"", {})

    def test_requires_a_token(self):
        self.assertEqual(call("/api2/json/version", headers={})[0], 401)

    def test_lists_the_six_real_guests(self):
        code, body = call("/api2/json/nodes/pve/lxc")
        self.assertEqual(code, 200)
        self.assertEqual(sorted(g["vmid"] for g in body["data"]), [100, 101, 102, 103, 210, 999])
        self.assertEqual(sum(g["status"] == "running" for g in body["data"]), 3)

    def test_stop_runs_a_task_then_flips_the_state(self):
        code, body = call("/api2/json/nodes/pve/lxc/100/status/stop", "POST", b"")
        self.assertEqual(code, 200)
        up = body["data"]
        self.assertTrue(up.startswith("UPID:pve:"))
        self.assertEqual(call("/api2/json/nodes/pve/lxc/100/status/current")[1]["data"]["status"], "running")
        self.assertEqual(call(f"/api2/json/nodes/pve/tasks/{up}/status")[1]["data"]["status"], "running")
        time.sleep(0.5)
        done = call(f"/api2/json/nodes/pve/tasks/{up}/status")[1]["data"]
        self.assertEqual((done["status"], done["exitstatus"]), ("stopped", "OK"))
        self.assertEqual(call("/api2/json/nodes/pve/lxc/100/status/current")[1]["data"]["status"], "stopped")

    def test_unknown_guest_and_double_start_fail_like_the_real_api(self):
        code, body = call("/api2/json/nodes/pve/lxc/99999/status/current")
        self.assertEqual(code, 500)
        self.assertIn("does not exist", body["message"])
        self.assertEqual(call("/api2/json/nodes/pve/lxc/100/status/start", "POST", b"")[0], 500)

    def test_node_commands_are_only_recorded(self):
        self.assertEqual(call("/api2/json/nodes/pve/status", "POST", b"command=shutdown")[0], 200)
        self.assertEqual(call("/api2/json/nodes/pve/status", "POST", b"command=wipe")[0], 400)
        log = call("/__mock/log", headers={})[1]
        self.assertEqual([e["body"] for e in log if e["path"].endswith("/status")], ["command=shutdown", "command=wipe"])
        self.assertEqual(call("/api2/json/nodes/pve/lxc/100/status/current")[1]["data"]["status"], "running")


if __name__ == "__main__":
    unittest.main()
