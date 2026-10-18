package com.homelabhub.internal.actions;

import org.springframework.http.HttpStatus;

public class ActionException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ActionException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
