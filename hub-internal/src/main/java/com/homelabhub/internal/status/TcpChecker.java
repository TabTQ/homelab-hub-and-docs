package com.homelabhub.internal.status;

import com.homelabhub.internal.config.DashboardConfig.StatusCheck;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public class TcpChecker implements Checker {

    private final int timeoutMillis;

    public TcpChecker(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public CheckResult run(StatusCheck check) {
        String target = check.target();
        int colon = target.lastIndexOf(':');
        if (colon <= 0) {
            return CheckResult.unknown("not a host:port target");
        }
        String host = target.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(target.substring(colon + 1));
        } catch (NumberFormatException e) {
            return CheckResult.unknown("bad port");
        }
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMillis);
            return CheckResult.up((System.nanoTime() - t0) / 1_000_000);
        } catch (SocketTimeoutException e) {
            return CheckResult.down("timed out");
        } catch (ConnectException e) {
            return CheckResult.down("connection refused");
        } catch (UnknownHostException e) {
            return CheckResult.down("unknown host");
        } catch (NoRouteToHostException e) {
            return CheckResult.down("no route to host");
        } catch (java.io.IOException | RuntimeException e) {
            return CheckResult.down(e.getClass().getSimpleName());
        }
    }
}
