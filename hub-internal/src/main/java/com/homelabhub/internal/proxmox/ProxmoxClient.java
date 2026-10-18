package com.homelabhub.internal.proxmox;

/** The Proxmox operations the hub needs. Exceptions are always {@link ProxmoxException} or IllegalArgumentException. */
public interface ProxmoxClient {

    GuestStatus guestStatus(String node, GuestType type, int id);

    /** @param action "start", "stop" (hard) or "shutdown" (graceful). @return the task id (UPID) or "" when absent */
    String guestAction(String node, GuestType type, int id, String action);

    /** @param command "shutdown" or "reboot" */
    void nodeCommand(String node, String command);

    record GuestInfo(int vmid, String name, GuestType type, GuestStatus status) {}

    /** LXC containers and VMs on the node, so a confirmation can say exactly what a shutdown would take down. */
    java.util.List<GuestInfo> listGuests(String node);

    record TaskStatus(boolean running, String exitStatus) {}

    /** Status of a Proxmox task started by guestAction (UPID as returned there). */
    TaskStatus taskStatus(String node, String upid);
}
