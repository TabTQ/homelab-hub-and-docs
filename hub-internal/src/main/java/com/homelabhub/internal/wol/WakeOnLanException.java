package com.homelabhub.internal.wol;

public class WakeOnLanException extends RuntimeException {

    public WakeOnLanException(String message) {
        super(message);
    }

    public WakeOnLanException(String message, Throwable cause) {
        super(message, cause);
    }
}