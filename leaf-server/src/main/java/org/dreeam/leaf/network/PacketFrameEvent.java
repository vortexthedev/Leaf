package org.dreeam.leaf.network;

import net.minecraft.network.ConnectionProtocol;

/**
 * Immutable native decoder buffer measurements; no buffer reference is retained or exposed.
 */
public final class PacketFrameEvent {
    private final PacketUser user;
    private final ConnectionProtocol protocol;
    private final int capacity, readableBytes;
    boolean readOnly;
    private boolean cancelled;

    PacketFrameEvent(PacketUser user, ConnectionProtocol protocol, int capacity, int readableBytes) {
        this.user = user;
        this.protocol = protocol;
        this.capacity = capacity;
        this.readableBytes = readableBytes;
    }

    public PacketUser getUser() {
        return user;
    }

    public ConnectionProtocol getProtocol() {
        return protocol;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getReadableBytes() {
        return readableBytes;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        if (readOnly) throw new IllegalStateException("MONITOR frame handlers are read-only");
        this.cancelled = cancelled;
    }
}
