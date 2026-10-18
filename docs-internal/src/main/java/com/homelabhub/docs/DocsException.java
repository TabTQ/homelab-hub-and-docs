package com.homelabhub.docs;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error the editor shows as is: a status, a stable code for the UI, a sentence for the owner, and optional extras. */
public class DocsException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extra;

    public DocsException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public DocsException(HttpStatus status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    static DocsException notFound() {
        return new DocsException(HttpStatus.NOT_FOUND, "not_found", "That page does not exist (or is in the Trash).");
    }

    static DocsException bad(String message) {
        return new DocsException(HttpStatus.BAD_REQUEST, "invalid", message);
    }

    static DocsException conflict(long current) {
        return new DocsException(HttpStatus.CONFLICT, "version_conflict",
                "This page was changed elsewhere since you opened it.", Map.of("currentVersion", current));
    }
}
