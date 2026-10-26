package org.dreeam.leaf.network;

import net.minecraft.network.protocol.Packet;

/**
 * Handles a packet on its way in or out.
 * <p>
 * Network listeners run on the connection's Netty event loop: do not touch worlds, entities or other
 * server state there without scheduling it onto the server thread. Explicit {@link LeafPackets#listenHandling}
 * listeners run after the native handler's server-thread check; {@link PacketEvent#isHandling()} identifies
 * that phase. A registration runs in exactly one phase.
 */
@FunctionalInterface
public interface PacketHandler<P extends Packet<?>> {

    void handle(PacketEvent<P> event);
}
