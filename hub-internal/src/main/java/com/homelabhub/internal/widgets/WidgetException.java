package com.homelabhub.internal.widgets;

/** A widget could not be fetched. The message is safe to show: it never contains upstream bodies or secret values. */
public class WidgetException extends RuntimeException {

    public enum Code { NOT_CONFIGURED, SECRET_MISSING, UNREACHABLE, TIMEOUT, UNAUTHORIZED, CERT_MISMATCH, BAD_RESPONSE }

    private final Code code;

    public WidgetException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public WidgetException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
