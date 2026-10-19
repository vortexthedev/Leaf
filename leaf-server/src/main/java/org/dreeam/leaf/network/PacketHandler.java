package org.dreeam.leaf.network;

import net.minecraft.network.protocol.Packet;

/**
 * Handles a packet on its way in or out.
 * <p>
 * Called on the connection's Netty event loop, not on the server thread: do not touch worlds, entities
 * or other server state from here without scheduling it onto the server thread.
 */
@FunctionalInterface
public interface PacketHandler<P extends Packet<?>> {

    void handle(PacketEvent<P> event);
}
