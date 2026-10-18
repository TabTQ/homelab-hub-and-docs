package com.homelabhub.internal.status;

import com.homelabhub.internal.config.DashboardConfig.StatusCheck;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uses the system {@code ping} (Java cannot send ICMP without privileges, and its isReachable falls back to a TCP echo
 * probe that is unreliable). The host was validated by the config parser and is passed as an argument, never through
 * a shell; "--" stops it being read as an option even so.
 */
public class IcmpChecker implements Checker {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,252}");
    private static final Pattern TIME = Pattern.compile("time[=<]([0-9.]+)\\s*ms");
    private final int waitSeconds;

    public IcmpChecker(int waitSeconds) {
        this.waitSeconds = waitSeconds;
    }

    @Override
    public CheckResult run(StatusCheck check) {
        String host = check.target();
        // The config parser already enforces this; repeating it here means a bad value can never reach the process, and
        // stops glibc's lenient address parsing (it accepts "127.0.0.1 -c 5" as 127.0.0.1) from pinging something unintended.
        if (!HOST.matcher(host).matches()) {
            return CheckResult.unknown("invalid host");
        }
        Process p = null;
        try {
            p = new ProcessBuilder("ping", "-c", "1", "-W", String.valueOf(waitSeconds), "--", host).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readNBytes(4096));
            if (!p.waitFor(waitSeconds + 2L, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return CheckResult.unknown("ping did not finish");
            }
            int code = p.exitValue();
            if (code == 0) {
                Matcher m = TIME.matcher(out);
                return CheckResult.up(m.find() ? Math.round(Float.parseFloat(m.group(1))) : 0);
            }
            if (code == 1) {
                return CheckResult.down("no reply");
            }
            return CheckResult.unknown("ping error (exit " + code + ")");
        } catch (IOException e) {
            return CheckResult.unknown("ping is not installed here");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.unknown("interrupted");
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
