package com.homelabhub.internal.terminal;

import com.homelabhub.internal.config.DashboardConfig.TerminalSpec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Opens a shell over SSH with JSch (plan 5.4). The server's host key is checked against the pinned fingerprint BEFORE any
 * authentication happens; only public-key authentication is offered; no agent, port or X11 forwarding is requested.
 */
@Component
public class SshConnector implements TerminalConnector {

    static final String TERM = "xterm-256color";

    private final KeyStore keys;
    private final int timeoutMillis;

    @Autowired
    public SshConnector(KeyStore keys, TerminalProperties props) {
        this(keys, props.connectTimeoutSeconds() * 1000);
    }

    SshConnector(KeyStore keys, int timeoutMillis) {
        this.keys = keys;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public TerminalConnection open(TerminalSpec host, int cols, int rows) throws TerminalException {
        Session session = connect(host);
        try {
            ChannelShell shell = (ChannelShell) session.openChannel("shell");
            shell.setPtyType(TERM, cols, rows, 0, 0);
            shell.setAgentForwarding(false);
            InputStream out = shell.getInputStream();
            OutputStream in = shell.getOutputStream();
            shell.connect(timeoutMillis);
            return new SshConnection(session, shell, out, in);
        } catch (JSchException | IOException e) {
            session.disconnect();
            throw new TerminalException(TerminalException.Code.error, "Could not open the shell on " + host.label() + ".");
        }
    }

    /**
     * An authenticated SSH session to {@code host} (pinned host key checked before authenticating, public key only). Also
     * used by the Docker page, which runs commands over exec channels of the same kind of session.
     */
    public Session connect(TerminalSpec host) throws TerminalException {
        byte[] key = keys.read(host.key());
        PinnedHostKey pinned = new PinnedHostKey(host.hostKey());
        Session session = null;
        try {
            JSch jsch = new JSch();
            try {
                jsch.addIdentity(host.key(), key, null, null);
            } catch (JSchException e) {
                throw new TerminalException(TerminalException.Code.key_invalid,
                        "The key file '" + host.key() + "' is not a usable private key (passphrase-protected keys are not supported).");
            } finally {
                Arrays.fill(key, (byte) 0);
            }
            jsch.setHostKeyRepository(pinned);
            session = jsch.getSession(host.user(), host.host(), host.port());
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "publickey");
            session.setServerAliveInterval(30_000);
            session.setServerAliveCountMax(3);
            session.connect(timeoutMillis);
            return session;
        } catch (JSchException e) {
            if (session != null) {
                session.disconnect();
            }
            throw translate(e, host, pinned);
        }
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    static TerminalException translate(JSchException e, TerminalSpec host, PinnedHostKey pinned) {
        String where = host.host() + ":" + host.port();
        if (pinned.offered != null && !pinned.accepted) {
            return new TerminalException(TerminalException.Code.host_key_mismatch,
                    "Host key mismatch for " + where + ": dashboard.yaml pins " + host.hostKey() + " but the server offered "
                            + pinned.offered + ". Nothing was sent. If the server was reinstalled, check the new key with "
                            + "terminal-hostkey and ssh-keygen -lf before updating dashboard.yaml.");
        }
        Throwable c = e.getCause();
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (c instanceof ConnectException || c instanceof NoRouteToHostException || c instanceof UnknownHostException) {
            return new TerminalException(TerminalException.Code.unreachable, "Cannot reach SSH on " + where + " (is sshd running?).");
        }
        if (c instanceof SocketTimeoutException || m.toLowerCase().contains("timeout") || m.toLowerCase().contains("timed out")) {
            return new TerminalException(TerminalException.Code.unreachable, "SSH on " + where + " did not answer in time.");
        }
        if (m.startsWith("Auth fail") || m.startsWith("Auth cancel") || m.contains("USERAUTH fail")) {
            return new TerminalException(TerminalException.Code.auth_rejected,
                    "The server refused the key for user '" + host.user() + "'. Check the user's authorized_keys.");
        }
        if (m.contains("Connection refused")) {
            return new TerminalException(TerminalException.Code.unreachable, "Cannot reach SSH on " + where + " (is sshd running?).");
        }
        return new TerminalException(TerminalException.Code.error, "Could not open an SSH session to " + where + ".");
    }

    /** Accepts exactly one host key: the pinned one. Remembers what was offered for the error message. */
    static final class PinnedHostKey implements HostKeyRepository {
        private final String pin;
        volatile String offered;
        volatile byte[] offeredKey;
        volatile boolean accepted;

        PinnedHostKey(String pin) {
            this.pin = pin;
        }

        @Override
        public int check(String host, byte[] key) {
            offeredKey = key.clone();
            offered = HostKeyFingerprint.of(key);
            accepted = pin != null && HostKeyFingerprint.matches(pin, offered);
            return accepted ? OK : NOT_INCLUDED;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
            // never learn keys
        }

        @Override
        public void remove(String host, String type) {}

        @Override
        public void remove(String host, String type, byte[] key) {}

        @Override
        public String getKnownHostsRepositoryID() {
            return "hub-pinned";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    static final class SshConnection implements TerminalConnection {
        private final Session session;
        private final ChannelShell shell;
        private final InputStream out;
        private final OutputStream in;

        SshConnection(Session session, ChannelShell shell, InputStream out, OutputStream in) {
            this.session = session;
            this.shell = shell;
            this.out = out;
            this.in = in;
        }

        @Override
        public InputStream output() {
            return out;
        }

        @Override
        public void write(byte[] data) throws IOException {
            in.write(data);
            in.flush();
        }

        @Override
        public void resize(int cols, int rows) {
            shell.setPtySize(cols, rows, 0, 0);
        }

        @Override
        public Integer exitStatus() {
            int s = shell.getExitStatus();
            return s < 0 ? null : s;
        }

        @Override
        public void close() {
            shell.disconnect();
            session.disconnect();
        }
    }
}
