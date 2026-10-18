package com.homelabhub.internal.proxmox;

public enum GuestStatus {
    RUNNING("running"),
    STOPPED("stopped"),
    STARTING("starting"),
    STOPPING("stopping"),
    MIGRATING("migrating"),
    SUSPENDED("suspended"),
    PAUSED("paused"),
    UNKNOWN("unknown");

    private final String apiStatus;

    GuestStatus(String apiStatus) {
        this.apiStatus = apiStatus;
    }

    public String apiStatus() {
        return apiStatus;
    }

    public static GuestStatus fromString(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        for (GuestStatus s : values()) {
            if (s.apiStatus.equalsIgnoreCase(value)) {
                return s;
            }
        }
        return UNKNOWN;
    }

    public boolean canToggle() {
        return this == RUNNING || this == STOPPED;
    }

    /** "stop" when running, "start" when stopped. Stop is Proxmox's hard stop, as in wol-server (decision D13). */
    public String toggleAction() {
        return switch (this) {
            case RUNNING -> "stop";
            case STOPPED -> "start";
            default -> throw new IllegalStateException("Cannot toggle from status: " + this);
        };
    }
}
