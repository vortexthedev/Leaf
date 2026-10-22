package ru.minecomplex.mac.world;

import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;

/**
 * A {@code block_event}. Vanilla dispatches to the current client block, ignoring {@code blockType}.
 */
public record BlockEvent(int x, int y, int z, int action, int parameter, int blockType) implements BlockInput {
    public static BlockEvent of(ClientboundBlockEventPacket packet) {
        var pos = packet.getPos();
        return new BlockEvent(pos.getX(), pos.getY(), pos.getZ(), packet.getB0(), packet.getB1(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(packet.getBlock()));
    }
}
