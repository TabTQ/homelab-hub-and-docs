package com.homelabhub.internal.docker;

import org.springframework.http.HttpStatus;

/** A failure with an HTTP status, a code the browser can act on, and a message that is safe to show. */
public class DockerException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public DockerException(HttpStatus status, String code, String message) {
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

    static DockerException bad(String message) {
        return new DockerException(HttpStatus.BAD_REQUEST, "invalid", message);
    }
}
