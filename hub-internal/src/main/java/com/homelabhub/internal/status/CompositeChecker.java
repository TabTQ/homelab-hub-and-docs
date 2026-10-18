package com.homelabhub.internal.status;

import com.homelabhub.internal.config.DashboardConfig.StatusCheck;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class CompositeChecker implements Checker {

    private final TcpChecker tcp = new TcpChecker(3000);
    private final HttpChecker http = new HttpChecker(Duration.ofSeconds(3), Duration.ofSeconds(5));
    private final IcmpChecker icmp = new IcmpChecker(2);

    @Override
    public CheckResult run(StatusCheck check) {
        return switch (check.kind()) {
            case TCP -> tcp.run(check);
            case HTTP -> http.run(check);
            case ICMP -> icmp.run(check);
        };
    }
}
