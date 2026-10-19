package org.dreeam.leaf.orebfuscator.obfuscation;

import it.unimi.dsi.fastutil.longs.LongSet;
import org.jspecify.annotations.NullMarked;

/**
 * Obfuscated section data of a chunk packet.
 *
 * @param data            the section data to send
 * @param blockEntities   positions ({@link net.minecraft.core.BlockPos#asLong}) of hidden block entities
 *                        that have to be removed from the packet
 * @param proximityBlocks positions of proximity hidden blocks
 * @param lavaObfuscated  for each proximity block, whether it is only hidden because of lava
 */
@NullMarked
public record ObfuscationResult(byte[] data, LongSet blockEntities, long[] proximityBlocks, boolean[] lavaObfuscated) {
}
