package com.homelabhub.internal.proxmox;

import com.fasterxml.jackson.annotation.JsonCreator;

/** Proxmox guest kinds. Serialised (legacy API) by enum name, e.g. "LXC", as wol-server did. */
public enum GuestType {
    LXC("lxc"),
    QEMU("qemu");

    private final String apiName;

    GuestType(String apiName) {
        this.apiName = apiName;
    }

    public String apiName() {
        return apiName;
    }

    /** Accepts "lxc", "LXC", "qemu", "QEMU" (any case). Message text matches the legacy service. */
    @JsonCreator
    public static GuestType fromString(String value) {
        for (GuestType t : values()) {
            if (t.apiName.equalsIgnoreCase(value) || t.name().equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Invalid container type: " + value + ". Must be 'lxc' or 'qemu'");
    }
}
