package com.homelabhub.internal.terminal;

import com.homelabhub.internal.config.DashboardConfig.TerminalSpec;

/** Opens a {@link TerminalConnection}. The real one is {@link SshConnector}; tests substitute their own. */
public interface TerminalConnector {

    TerminalConnection open(TerminalSpec host, int cols, int rows) throws TerminalException;
}
