package com.homelabhub.internal.terminal;

/** A failure with a code the browser can act on and a message that is safe to show (no paths, no stack traces). */
public class TerminalException extends Exception {

    public enum Code { unreachable, key_missing, key_invalid, host_key_mismatch, auth_rejected, dropped, error }

    private final Code code;

    public TerminalException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
