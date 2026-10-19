package org.dreeam.leaf.network;

/**
 * Order in which packet handlers run, from {@link #LOWEST} to {@link #MONITOR}.
 * Handlers of the same priority run in registration order.
 */
public enum PacketPriority {
    LOWEST,
    LOW,
    NORMAL,
    HIGH,
    HIGHEST,
    /**
     * Runs last and only observes: the packet can not be replaced or cancelled.
     */
    MONITOR
}
