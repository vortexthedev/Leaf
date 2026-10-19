package org.dreeam.leaf.network;

import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jspecify.annotations.Nullable;

/**
 * A packet passing through a connection. The packet is the vanilla object itself: no bytes are encoded
 * or decoded for handlers.
 * <p>
 * Packets can be changed in place through their setters, or replaced with {@link #setPacket(Packet)}.
 * <p>
 * The server often sends one packet object to many players (entity data, movement, block updates...),
 * and each connection runs its handlers on its own thread. Changing such a packet in place changes it
 * for every player and races with the other connections: for a change meant for one player, build a new
 * packet and pass it to {@link #setPacket(Packet)}.
 * <p>
 * Packets that switch the connection protocol ({@link Packet#isTerminal()}) can not be cancelled.
 */
public final class PacketEvent<P extends Packet<?>> {

    private final PacketUser user;
    private final PacketType<?> type;
    private final ConnectionProtocol protocol;
    private final @Nullable List<Runnable> sentTasks;
    private P packet;
    private boolean cancelled;
    boolean readOnly;

    PacketEvent(final PacketUser user, final P packet, final @Nullable List<Runnable> sentTasks) {
        this.user = user;
        this.type = packet.type();
        this.protocol = user.getProtocol(this.type.flow());
        this.packet = packet;
        this.sentTasks = sentTasks;
    }

    public PacketUser getUser() {
        return this.user;
    }

    public Connection getConnection() {
        return this.user.getConnection();
    }

    /**
     * @return the player of this connection, or {@code null} before the player has joined the game
     */
    public @Nullable ServerPlayer getPlayer() {
        return this.user.getPlayer();
    }

    public @Nullable CraftPlayer getBukkitPlayer() {
        return this.user.getBukkitPlayer();
    }

    /**
     * @return {@link PacketFlow#SERVERBOUND} for received packets, {@link PacketFlow#CLIENTBOUND} for sent ones
     */
    public PacketFlow getFlow() {
        return this.type.flow();
    }

    public boolean isSent() {
        return this.type.flow() == PacketFlow.CLIENTBOUND;
    }

    public PacketType<?> getType() {
        return this.type;
    }

    /**
     * @return the protocol the packet belongs to
     */
    public ConnectionProtocol getProtocol() {
        return this.protocol;
    }

    public P getPacket() {
        return this.packet;
    }

    /**
     * Replaces the packet that is sent or handled.
     *
     * @param packet a packet of the same {@link PacketType}
     */
    public void setPacket(final P packet) {
        this.checkWritable();
        if (packet.type() != this.type) {
            throw new IllegalArgumentException("Packet " + packet.type() + " can not replace " + this.type);
        }
        this.packet = packet;
    }

    public boolean isCancelled() {
        return this.cancelled;
    }

    public void setCancelled(final boolean cancelled) {
        this.checkWritable();
        if (cancelled && this.packet.isTerminal()) {
            throw new IllegalStateException("Packet " + this.type + " switches the connection protocol and can not be cancelled");
        }
        this.cancelled = cancelled;
    }

    /**
     * Runs a task on the event loop once the packet has been written to the client
     * (or dropped, if the packet ends up cancelled). Only for sent packets.
     */
    public void runAfterSent(final Runnable task) {
        if (this.sentTasks == null) {
            throw new IllegalStateException("Only sent packets have tasks after sending");
        }
        this.sentTasks.add(task);
    }

    private void checkWritable() {
        if (this.readOnly) {
            throw new IllegalStateException("MONITOR handlers can not change packets");
        }
    }
}
