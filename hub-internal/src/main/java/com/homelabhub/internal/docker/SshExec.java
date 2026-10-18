package com.homelabhub.internal.docker;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Runs one command on an exec channel of an open SSH session: optional stdin, bounded output, a hard deadline. */
final class SshExec {

    private SshExec() {}

    record Result(int exit, String out, String err, boolean truncated) {
        boolean ok() {
            return exit == 0;
        }

        /** stdout and stderr together, for messages (many commands already send 2>&1). */
        String all() {
            return err.isEmpty() ? out : out.isEmpty() ? err : out + "\n" + err;
        }
    }

    static final class Timeout extends Exception {
        Timeout() {
            super("timed out");
        }
    }

    /**
     * @param onOutput receives stdout chunks as they arrive (for job progress); may be null
     * @param cancelled polled while waiting; when it returns true the channel is closed and the call ends as a timeout
     */
    static Result run(Session session, String command, byte[] stdin, long timeoutMillis, int maxBytes, Consumer<String> onOutput,
                      java.util.function.BooleanSupplier cancelled) throws JSchException, IOException, Timeout {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(command);
        ch.setAgentForwarding(false);
        ch.setPty(false);
        InputStream out = ch.getInputStream();
        InputStream err = ch.getErrStream();
        OutputStream in = ch.getOutputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        boolean truncated = false;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try {
            ch.connect((int) Math.min(timeoutMillis, 15_000));
            if (stdin != null) {
                in.write(stdin);
                in.flush();
            }
            in.close();
            byte[] buf = new byte[8192];
            while (true) {
                boolean read = false;
                while (out.available() > 0) {
                    int n = out.read(buf);
                    if (n < 0) {
                        break;
                    }
                    read = true;
                    if (o.size() + n > maxBytes) {
                        truncated = true;
                        o.write(buf, 0, Math.max(0, maxBytes - o.size()));
                    } else {
                        o.write(buf, 0, n);
                    }
                    if (onOutput != null) {
                        onOutput.accept(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
                while (err.available() > 0) {
                    int n = err.read(buf);
                    if (n < 0) {
                        break;
                    }
                    read = true;
                    if (e.size() + n <= 64 * 1024) {
                        e.write(buf, 0, n);
                    }
                    if (onOutput != null) {
                        onOutput.accept(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
                if (ch.isClosed() && out.available() == 0 && err.available() == 0) {
                    break;
                }
                if (System.currentTimeMillis() > deadline || (cancelled != null && cancelled.getAsBoolean())) {
                    throw new Timeout();
                }
                if (!read) {
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Timeout();
                    }
                }
            }
            return new Result(ch.getExitStatus(), o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8), truncated);
        } finally {
            ch.disconnect();
        }
    }
}
