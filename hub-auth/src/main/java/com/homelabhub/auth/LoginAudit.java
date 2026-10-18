package com.homelabhub.auth;

/** Where the login endpoint records attempts. Each app writes them to its own audit table; a failing write must never fail a login. */
@FunctionalInterface
public interface LoginAudit {

    enum Result { ok, failed, denied }

    void record(Result result, String detail, String clientIp, String userAgent);
}
