package com.homelabhub.internal.status;

public record CheckResult(State state, Integer latencyMs, String detail) {
    public static CheckResult up(long millis) {
        return new CheckResult(State.UP, (int) millis, null);
    }

    public static CheckResult down(String detail) {
        return new CheckResult(State.DOWN, null, detail);
    }

    public static CheckResult degraded(long millis, String detail) {
        return new CheckResult(State.DEGRADED, (int) millis, detail);
    }

    public static CheckResult unknown(String detail) {
        return new CheckResult(State.UNKNOWN, null, detail);
    }
}
