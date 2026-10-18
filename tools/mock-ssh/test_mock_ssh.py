import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

try:
    import paramiko
except ImportError:  # pragma: no cover
    paramiko = None

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 18922


@unittest.skipIf(paramiko is None, "paramiko is not installed")
class MockSshTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = tempfile.mkdtemp(prefix="mock-ssh-test-")
        cls.p = subprocess.Popen([sys.executable, os.path.join(HERE, "mock_ssh.py"), str(PORT), cls.data], stdout=subprocess.DEVNULL)
        fp = os.path.join(cls.data, "ssh-mock", "fingerprint")
        for _ in range(100):
            if os.path.exists(fp):
                break
            time.sleep(0.05)
        with open(fp) as f:
            cls.fingerprint = f.read().strip()

    @classmethod
    def tearDownClass(cls):
        cls.p.terminate()
        cls.p.wait(5)
        shutil.rmtree(cls.data, ignore_errors=True)

    def connect(self, key_path=None, user="hubshell"):
        c = paramiko.SSHClient()
        c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        pkey = paramiko.Ed25519Key(filename=key_path or os.path.join(self.data, "ssh", "hub_terminal_ed25519"))
        c.connect("127.0.0.1", PORT, username=user, pkey=pkey, look_for_keys=False, allow_agent=False, timeout=5)
        return c

    def read_until(self, chan, text, seconds=5):
        seen = b""
        end = time.time() + seconds
        while time.time() < end:
            if chan.recv_ready():
                seen += chan.recv(65536)
                if text.encode() in seen:
                    return seen.decode(errors="replace")
            time.sleep(0.02)
        self.fail(f"did not see {text!r} in {seen!r}")

    def test_writes_a_private_key_only_the_owner_can_read_and_a_matching_fingerprint(self):
        key = os.path.join(self.data, "ssh", "hub_terminal_ed25519")
        self.assertEqual(os.stat(key).st_mode & 0o777, 0o600)
        with open(key) as f:
            self.assertTrue(f.read().startswith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        self.assertRegex(self.fingerprint, r"^SHA256:[A-Za-z0-9+/]{43}$")
        c = self.connect()
        offered = c.get_transport().get_remote_server_key()
        import base64, hashlib
        self.assertEqual(self.fingerprint, "SHA256:" + base64.b64encode(hashlib.sha256(offered.asbytes()).digest()).decode().rstrip("="))
        c.close()

    def test_a_pty_shell_runs_commands_resizes_and_reports_the_exit_code(self):
        c = self.connect()
        chan = c.invoke_shell(term="xterm-256color", width=91, height=27)
        self.read_until(chan, "mock$ ")
        chan.send("echo hello-$((6*7)); echo $TERM; stty size\n")
        out = self.read_until(chan, "27 91")
        self.assertIn("hello-42", out)
        self.assertIn("xterm-256color", out)
        chan.resize_pty(width=100, height=30)
        time.sleep(0.2)
        chan.send("stty size\n")
        self.read_until(chan, "30 100")
        chan.send("exit 5\n")
        self.assertEqual(chan.recv_exit_status(), 5)
        c.close()

    def run_exec(self, transport, cmd, stdin=None):
        chan = transport.open_session()
        chan.exec_command(cmd)
        if stdin is not None:
            chan.sendall(stdin.encode())
        chan.shutdown_write()
        out = b""
        while True:
            data = chan.recv(65536)
            if not data:
                break
            out += data
        return chan.recv_exit_status(), out.decode()

    def test_the_docker_user_runs_the_fake_docker_over_several_channels(self):
        c = self.connect(key_path=os.path.join(self.data, "ssh", "hub_docker_ed25519"), user="hubdocker")
        t = c.get_transport()
        code, out = self.run_exec(t, "docker ps -a --format '{{json .}}'")
        self.assertEqual(code, 0)
        self.assertIn('"name": "jellyfin"', out)
        code, out = self.run_exec(t, "cat > note.txt && cat note.txt", stdin="it's private\n")
        self.assertEqual((code, out), (0, "it's private\n"))
        code, out = self.run_exec(t, "docker nonsense")
        self.assertEqual(code, 1)
        jelly = [l for l in self.run_exec(t, "docker ps")[1].splitlines() if '"jellyfin"' in l][0]
        cid = jelly.split('"id": "')[1].split('"')[0]
        self.assertEqual(self.run_exec(t, f"docker stop '{cid}'")[0], 0)
        self.assertIn('"state": "exited"', [l for l in self.run_exec(t, "docker ps")[1].splitlines() if '"jellyfin"' in l][0])
        self.assertEqual(self.run_exec(t, f"docker start '{cid}'")[0], 0)
        c.close()

    def test_the_shell_user_may_not_exec(self):
        c = self.connect()
        chan = c.get_transport().open_session()
        with self.assertRaises(paramiko.SSHException):
            chan.exec_command("id")
        c.close()

    def test_other_keys_and_users_are_refused(self):
        other = os.path.join(self.data, "other_key")
        subprocess.run([sys.executable, "-c", f"import sys; sys.path.insert(0, {HERE!r}); import mock_ssh; mock_ssh.write_ed25519({other!r})"], check=True)
        with self.assertRaises(paramiko.AuthenticationException):
            self.connect(key_path=other)
        with self.assertRaises(paramiko.AuthenticationException):
            self.connect(user="root")


if __name__ == "__main__":
    unittest.main()
