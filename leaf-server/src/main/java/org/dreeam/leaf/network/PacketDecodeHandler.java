package org.dreeam.leaf.network;

@FunctionalInterface
public interface PacketDecodeHandler {
    void handle(PacketDecodeEvent event);
}
