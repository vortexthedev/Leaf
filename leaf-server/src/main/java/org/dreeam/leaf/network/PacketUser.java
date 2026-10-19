package org.dreeam.leaf.network;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.net.SocketAddress;
import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jspecify.annotations.Nullable;

/**
 * One client connection, from the handshake to the disconnect. Every connection has exactly one user,
 * see {@link LeafPackets#user(org.bukkit.entity.Player)} and {@link LeafPackets#users()}.
 */
public final class PacketUser {

    private final Connection connection;
    private volatile ConnectionProtocol inboundProtocol = ConnectionProtocol.HANDSHAKING;
    private volatile ConnectionProtocol outboundProtocol = ConnectionProtocol.HANDSHAKING;
    private final ConcurrentHashMap<Object, Object> data = new ConcurrentHashMap<>();

    // Packets queued with sendSilently that the interceptor has not seen yet, with how many times each was queued
    private final IdentityHashMap<Packet<?>, Integer> silent = new IdentityHashMap<>();
    private volatile int silentCount;

    public PacketUser(final Connection connection) {
        this.connection = connection;
    }

    public Connection getConnection() {
        return this.connection;
    }

    /**
     * @return the channel, or {@code null} before the connection is active
     */
    public @Nullable Channel getChannel() {
        return this.connection.channel;
    }

    public @Nullable SocketAddress getAddress() {
        return this.connection.address;
    }

    public boolean isConnected() {
        return this.connection.isConnected();
    }

    /**
     * @return the protocol version from the handshake, {@code 0} before it
     */
    public int getProtocolVersion() {
        return this.connection.protocolVersion;
    }

    /**
     * @return the protocol of received packets: {@code HANDSHAKING}, {@code STATUS}, {@code LOGIN}, {@code CONFIGURATION} or {@code PLAY}
     */
    public ConnectionProtocol getInboundProtocol() {
        return this.inboundProtocol;
    }

    /**
     * @return the protocol of sent packets; it switches at a different moment than the inbound one
     */
    public ConnectionProtocol getOutboundProtocol() {
        return this.outboundProtocol;
    }

    public ConnectionProtocol getProtocol(final PacketFlow flow) {
        return flow == PacketFlow.SERVERBOUND ? this.inboundProtocol : this.outboundProtocol;
    }

    /**
     * @return the player, or {@code null} before the player has joined the game
     */
    public @Nullable ServerPlayer getPlayer() {
        return this.connection.getPlayer();
    }

    public @Nullable CraftPlayer getBukkitPlayer() {
        final ServerPlayer player = this.getPlayer();
        return player != null ? player.getBukkitEntity() : null;
    }

    /**
     * @return the profile once the login has finished it, otherwise {@code null}
     */
    public @Nullable GameProfile getProfile() {
        final PacketListener listener = this.connection.getPacketListener();
        if (listener instanceof ServerCommonPacketListenerImpl common) {
            return common.getPlayerProfile();
        }
        if (listener instanceof ServerLoginPacketListenerImpl login) {
            return login.authenticatedProfile;
        }
        return null;
    }

    public @Nullable String getName() {
        final GameProfile profile = this.getProfile();
        return profile != null ? profile.name() : null;
    }

    public @Nullable UUID getUniqueId() {
        final GameProfile profile = this.getProfile();
        return profile != null ? profile.id() : null;
    }

    /**
     * Data a plugin keeps for this connection; it is dropped with the user.
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T getData(final Object key) {
        return (T) this.data.get(key);
    }

    public void setData(final Object key, final @Nullable Object value) {
        if (value == null) {
            this.data.remove(key);
        } else {
            this.data.put(key, value);
        }
    }

    /**
     * Sends a packet; it passes through the handlers like any other.
     */
    public void send(final Packet<?> packet) {
        this.connection.send(packet);
    }

    /**
     * Sends a packet and runs the task on the event loop once it is written.
     */
    public void send(final Packet<?> packet, final Runnable afterSent) {
        this.connection.send(packet, PacketSendListener.thenRun(afterSent));
    }

    /**
     * Sends a packet without passing it to the handlers. Keeps its order with the other sent packets.
     */
    public void sendSilently(final Packet<?> packet) {
        synchronized (this.silent) {
            this.silent.merge(packet, 1, Integer::sum);
            this.silentCount++;
        }
        this.connection.send(packet);
    }

    // Called by the interceptor on the event loop for every sent packet
    boolean consumeSilent(final Packet<?> packet) {
        if (this.silentCount == 0) {
            return false;
        }
        synchronized (this.silent) {
            final Integer count = this.silent.get(packet);
            if (count == null) {
                return false;
            }
            if (count == 1) {
                this.silent.remove(packet);
            } else {
                this.silent.put(packet, count - 1);
            }
            this.silentCount--;
            return true;
        }
    }

    /**
     * Handles a packet as if the client had sent it; it passes through the handlers first.
     * The packet must belong to the current {@link #getInboundProtocol()}.
     */
    public void receive(final Packet<?> packet) {
        this.receive(packet, false);
    }

    /**
     * Handles a packet as if the client had sent it, without passing it to the handlers.
     */
    public void receiveSilently(final Packet<?> packet) {
        this.receive(packet, true);
    }

    private void receive(final Packet<?> packet, final boolean silently) {
        final Channel channel = this.connection.channel;
        if (channel == null || !channel.isOpen()) {
            return;
        }
        channel.eventLoop().execute(() -> {
            final ChannelHandlerContext ctx = channel.pipeline().context(PacketInterceptor.NAME);
            if (ctx == null) {
                return;
            }
            if (silently) {
                ctx.fireChannelRead(packet);
            } else {
                ((PacketInterceptor) ctx.handler()).channelRead(ctx, packet);
            }
        });
    }

    /**
     * Disconnects with a reason, in any protocol. Players are kicked like {@code Player#kick}.
     */
    public void disconnect(final Component reason) {
        final PacketListener listener = this.connection.getPacketListener();
        if (listener instanceof ServerCommonPacketListenerImpl common) {
            common.disconnect(reason);
        } else if (listener instanceof ServerLoginPacketListenerImpl login) {
            login.disconnect(reason);
        } else {
            this.connection.disconnect(reason);
        }
    }

    // Called by Connection when a protocol switch is applied on the event loop
    public void protocolChanged(final PacketFlow flow, final ConnectionProtocol protocol) {
        final ConnectionProtocol previous;
        if (flow == PacketFlow.SERVERBOUND) {
            previous = this.inboundProtocol;
            this.inboundProtocol = protocol;
        } else {
            previous = this.outboundProtocol;
            this.outboundProtocol = protocol;
        }
        if (previous != protocol) {
            LeafPackets.fireProtocolChange(this, flow, previous, protocol);
        }
    }

    @Override
    public String toString() {
        final String name = this.getName();
        return "PacketUser[" + (name != null ? name : this.connection.getLoggableAddress(true)) + "]";
    }
}
