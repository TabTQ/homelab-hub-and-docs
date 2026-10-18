package com.homelabhub.internal.config;

/** One problem found in dashboard.yaml. {@code line} is 1-based, or 0 when unknown. */
public record Issue(Level level, String path, int line, String message) {

    public enum Level { ERROR, WARNING }

    public static Issue error(String path, int line, String message) {
        return new Issue(Level.ERROR, path, line, message);
    }

    public static Issue warning(String path, int line, String message) {
        return new Issue(Level.WARNING, path, line, message);
    }
}
