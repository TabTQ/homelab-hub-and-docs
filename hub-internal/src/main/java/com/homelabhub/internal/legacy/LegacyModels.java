package com.homelabhub.internal.legacy;

import com.homelabhub.internal.proxmox.GuestStatus;
import com.homelabhub.internal.proxmox.GuestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;

/**
 * JSON shapes of the legacy wol-server API. Field ORDER and the presence of explicit nulls match the responses
 * recorded from the live service (contract/legacy-fixtures), because callers may depend on them.
 */
final class LegacyModels {

    private LegacyModels() {}

    record WolResponse(boolean success, String message) {}

    record ToggleResponse(boolean success, String message, String node, GuestType type, Integer id, String action,
                          GuestStatus previousStatus, GuestStatus newStatus, String error, String errorDetails) {

        static ToggleResponse ok(String message, String node, GuestType type, int id, String action,
                                 GuestStatus previous, GuestStatus next) {
            return new ToggleResponse(true, message, node, type, id, action, previous, next, null, null);
        }

        static ToggleResponse failure(String error, String message, String details) {
            return new ToggleResponse(false, message, null, null, null, null, null, null, error, details);
        }
    }

    record NodeResponse(boolean success, String message, String node, String error, String errorDetails) {}

    record ErrorResponse(String error, String message, Map<String, String> details) {}

    /** POST /toggle body. Bean validation messages are the legacy ones. */
    record ToggleRequest(@NotBlank(message = "Node name is required") String node,
                         @NotNull(message = "Container type is required") GuestType type,
                         @NotNull(message = "Container/VM ID is required") @Positive(message = "ID must be a positive number") Integer id) {}

    /** POST /wake body. */
    record WakeRequest(String macAddress, String target) {
        WakeRequest {
            target = target == null ? "device" : target;
        }
    }
}
