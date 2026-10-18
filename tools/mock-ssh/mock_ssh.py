#!/usr/bin/env python3
"""A tiny SSH server for the Terminal page's browser tests (docs/design-history/terminal-feature-plan.md 5.9).

    python3 tools/mock-ssh/mock_ssh.py PORT DATA_DIR

Listens on 127.0.0.1 only. On start it generates, into DATA_DIR:
  ssh/hub_terminal_ed25519   a throwaway ed25519 CLIENT key in OpenSSH format (what the hub reads; same format as ssh-keygen)
  ssh-mock/host_key          a throwaway ed25519 HOST key
  ssh-mock/fingerprint       the host key's "SHA256:..." fingerprint (for the dashboard.yaml hostKey pin)
and accepts exactly that client key for the user "hubshell". A session gets a real PTY running `bash` (or `sh`) in a
throwaway home directory, with window-change support and the shell's exit status.

For the Docker page the same key (also written as ssh/hub_docker_ed25519) is accepted for the user "hubdocker", whose
exec requests run through /bin/sh in DATA_DIR/ssh-mock/docker-home with tools/mock-docker first on the PATH: a fake,
stateful docker CLI (state in DATA_DIR/ssh-mock/docker-state). Several channels per connection work, like a real sshd.

Dependency: paramiko (pip install paramiko), which brings `cryptography`. Chosen over asyncssh because it is the more
common package and was installable here. If it is missing the script exits with code 3 and the browser tests skip.
Test tool only: never point it at anything but the local e2e stack.
"""
import base64
import fcntl
import hashlib
import os
import pty
import select
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import termios
import threading

try:
    import paramiko
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ed25519
except ImportError:  # pragma: no cover - reported to the caller
    print("mock_ssh: paramiko is not installed (pip install paramiko)", file=sys.stderr)
    sys.exit(3)

USER = "hubshell"
DOCKER_USER = "hubdocker"
MOCK_DOCKER_BIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "mock-docker")
DOCKER_HOME = None   # set in main()
DOCKER_STATE = None


def write_ed25519(path):
    """Writes a new ed25519 private key in OpenSSH format (mode 600) and returns the public key blob."""
    key = ed25519.Ed25519PrivateKey.generate()
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.OpenSSH, serialization.NoEncryption())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    pub = key.public_key().public_bytes(serialization.Encoding.OpenSSH, serialization.PublicFormat.OpenSSH)
    return base64.b64decode(pub.split()[1])


def fingerprint(blob):
    return "SHA256:" + base64.b64encode(hashlib.sha256(blob).digest()).decode().rstrip("=")


def set_winsize(fd, cols, rows):
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", max(1, rows), max(1, cols), 0, 0))


class Server(paramiko.ServerInterface):
    def __init__(self, allowed_blob):
        self.allowed = allowed_blob
        self.pty = None  # (term, cols, rows)
        self.shell = threading.Event()
        self.master = None

    def get_allowed_auths(self, username):
        return "publickey"

    def check_auth_publickey(self, username, key):
        if username in (USER, DOCKER_USER) and key.asbytes() == self.allowed:
            return paramiko.AUTH_SUCCESSFUL
        return paramiko.AUTH_FAILED

    def check_auth_password(self, username, password):
        return paramiko.AUTH_FAILED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_pty_request(self, channel, term, width, height, pixelwidth, pixelheight, modes):
        self.pty = (term.decode() if isinstance(term, bytes) else term, width, height)
        return True

    def check_channel_window_change_request(self, channel, width, height, pixelwidth, pixelheight):
        if self.master is not None:
            set_winsize(self.master, width, height)  # the kernel sends SIGWINCH to the shell
        else:
            self.pty = (self.pty[0] if self.pty else "xterm", width, height)
        return True

    def check_channel_shell_request(self, channel):
        self.shell.set()
        return True

    def check_channel_exec_request(self, channel, command):
        if channel.get_transport().get_username() != DOCKER_USER:
            return False
        cmd = command.decode() if isinstance(command, bytes) else command
        threading.Thread(target=run_exec, args=(channel, cmd), daemon=True).start()
        return True

    def check_channel_env_request(self, channel, name, value):
        return False

    def check_port_forward_request(self, address, port):
        return False


def run_exec(chan, cmd):
    """One exec request of the docker user: /bin/sh -c CMD with the fake docker, stdin/stdout/stderr relayed."""
    env = {"HOME": DOCKER_HOME, "PATH": os.path.abspath(MOCK_DOCKER_BIN) + ":/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8",
           "USER": DOCKER_USER, "MOCK_DOCKER_STATE": DOCKER_STATE}
    proc = subprocess.Popen(["/bin/sh", "-c", cmd], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
                            cwd=DOCKER_HOME, start_new_session=True)

    def feed():
        try:
            while True:
                data = chan.recv(65536)
                if not data:
                    break
                proc.stdin.write(data)
                proc.stdin.flush()
        except (OSError, EOFError, ValueError):
            pass
        finally:
            try:
                proc.stdin.close()
            except OSError:
                pass

    def errs():
        for data in iter(lambda: proc.stderr.read1(65536), b""):
            try:
                chan.sendall_stderr(data)
            except (OSError, EOFError):
                break

    threading.Thread(target=feed, daemon=True).start()
    te = threading.Thread(target=errs, daemon=True)
    te.start()
    try:
        for data in iter(lambda: proc.stdout.read1(65536), b""):
            chan.sendall(data)
            if chan.closed:
                break
    except (OSError, EOFError, socket.error):
        pass
    if chan.closed and proc.poll() is None:
        os.killpg(proc.pid, signal.SIGTERM)
    code = proc.wait()
    te.join(2)
    try:
        chan.send_exit_status(code if code >= 0 else 128 - code)
        chan.close()
    except Exception:
        pass


def serve(conn, host_key, allowed_blob):
    t = paramiko.Transport(conn)
    t.add_server_key(host_key)
    server = Server(allowed_blob)
    try:
        t.start_server(server=server)
    except (paramiko.SSHException, EOFError, OSError):
        return
    chan = t.accept(20)
    if chan is not None and t.get_username() == DOCKER_USER:
        # Exec channels run in their own threads (check_channel_exec_request); keep accepting until the client leaves.
        # Accepted channels must stay referenced: paramiko closes a Channel when it is garbage collected.
        open_chans = [chan]
        while t.is_active():
            nxt = t.accept(1)
            open_chans = [c for c in open_chans if not c.closed] + ([nxt] if nxt is not None else [])
        return
    if chan is None or not server.shell.wait(10):
        t.close()
        return
    term, cols, rows = server.pty or ("dumb", 80, 24)
    home = tempfile.mkdtemp(prefix="mock-ssh-home-")
    master, slave = pty.openpty()
    set_winsize(master, cols, rows)
    server.master = master
    shell = shutil.which("bash") or "/bin/sh"
    args = [shell, "--norc", "--noprofile", "-i"] if shell.endswith("bash") else [shell, "-i"]
    env = {"TERM": term, "HOME": home, "PATH": "/usr/local/bin:/usr/bin:/bin", "PS1": "mock$ ", "LANG": "C.UTF-8", "USER": USER}

    def child_setup():
        # Started in the background from a script, this process inherits SIGINT/SIGQUIT as ignored; a real sshd gives
        # every session default signal handling, so Ctrl+C must work in the shell.
        for sig in (signal.SIGINT, signal.SIGQUIT, signal.SIGTSTP):
            signal.signal(sig, signal.SIG_DFL)
        os.setsid()
        fcntl.ioctl(0, termios.TIOCSCTTY, 0)

    proc = subprocess.Popen(args, stdin=slave, stdout=slave, stderr=slave, env=env, cwd=home, preexec_fn=child_setup, close_fds=True)
    os.close(slave)
    try:
        while True:
            r, _, _ = select.select([master, chan], [], [], 0.5)
            if master in r:
                try:
                    data = os.read(master, 65536)
                except OSError:
                    data = b""
                if not data:
                    break
                chan.sendall(data)
            if chan in r:
                data = chan.recv(65536)
                if not data:
                    break
                os.write(master, data)
            if proc.poll() is not None and not select.select([master], [], [], 0)[0]:
                break
            if chan.closed:
                break
    except (OSError, EOFError, socket.error):
        pass
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(3)
            except subprocess.TimeoutExpired:
                proc.kill()
        code = proc.wait()
        try:
            chan.send_exit_status(code if code >= 0 else 128 - code)
            chan.close()
        except Exception:
            pass
        os.close(master)
        shutil.rmtree(home, ignore_errors=True)
        t.close()


def main():
    if len(sys.argv) != 3:
        print("usage: mock_ssh.py PORT DATA_DIR", file=sys.stderr)
        sys.exit(2)
    port, data = int(sys.argv[1]), sys.argv[2]
    global DOCKER_HOME, DOCKER_STATE
    client_blob = write_ed25519(os.path.join(data, "ssh", "hub_terminal_ed25519"))
    shutil.copyfile(os.path.join(data, "ssh", "hub_terminal_ed25519"), os.path.join(data, "ssh", "hub_docker_ed25519"))
    os.chmod(os.path.join(data, "ssh", "hub_docker_ed25519"), 0o600)
    DOCKER_HOME = os.path.join(data, "ssh-mock", "docker-home")
    DOCKER_STATE = os.path.join(data, "ssh-mock", "docker-state")
    os.makedirs(DOCKER_HOME, exist_ok=True)
    host_path = os.path.join(data, "ssh-mock", "host_key")
    host_blob = write_ed25519(host_path)
    host_key = paramiko.Ed25519Key(filename=host_path)
    assert host_key.asbytes() == host_blob
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(16)
    # Written last, so its existence means "ready".
    with open(os.path.join(data, "ssh-mock", "fingerprint"), "w") as f:
        f.write(fingerprint(host_blob) + "\n")
    print(f"mock ssh on 127.0.0.1:{port}, host key {fingerprint(host_blob)}", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve, args=(conn, host_key, client_blob), daemon=True).start()


if __name__ == "__main__":
    main()
