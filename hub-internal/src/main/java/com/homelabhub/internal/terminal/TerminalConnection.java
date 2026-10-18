package com.homelabhub.internal.terminal;

import java.io.IOException;
import java.io.InputStream;

/** One open shell. The WebSocket side only sees this, so it can be tested with a fake. */
public interface TerminalConnection extends AutoCloseable {

    /** Raw terminal output. Returns -1 from read() when the remote side is done. */
    InputStream output();

    void write(byte[] data) throws IOException;

    void resize(int cols, int rows);

    /** The shell's exit code once it has ended, else null. */
    Integer exitStatus();

    @Override
    void close();
}
