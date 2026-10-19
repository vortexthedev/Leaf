package org.dreeam.leaf.network;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Follows connections from the first byte to the disconnect. Called on the connection's Netty event loop,
 * see {@link PacketHandler} for what that allows.
 */
public interface ConnectionListener {

    /**
     * A client connected; nothing has been received yet.
     */
    default void onConnect(final PacketUser user) {
    }

    /**
     * The protocol of one direction switched, for example {@code LOGIN} to {@code CONFIGURATION}.
     * Packets of the new protocol come after this call.
     */
    default void onProtocolChange(final PacketUser user, final PacketFlow flow, final ConnectionProtocol from, final ConnectionProtocol to) {
    }

    /**
     * The channel closed; nothing more is received or sent.
     */
    default void onDisconnect(final PacketUser user) {
    }
}
