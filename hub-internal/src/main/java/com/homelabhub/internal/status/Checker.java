package com.homelabhub.internal.status;

import com.homelabhub.internal.config.DashboardConfig.StatusCheck;

/** Runs one status check. Implementations must respect their own timeouts and never throw. */
public interface Checker {
    CheckResult run(StatusCheck check);
}
