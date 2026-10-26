package org.dreeam.leaf.network;

/**
 * Invoked on the connection event loop, after framing/decompression and before native packet decoding.
 */
@FunctionalInterface
public interface PacketFrameHandler {
    void handle(PacketFrameEvent event);
}
