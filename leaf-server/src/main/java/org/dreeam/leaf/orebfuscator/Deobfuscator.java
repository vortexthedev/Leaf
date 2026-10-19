package org.dreeam.leaf.orebfuscator;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.dreeam.leaf.orebfuscator.cache.ObfuscationCache;
import org.dreeam.leaf.orebfuscator.config.BlockFlags;
import org.dreeam.leaf.orebfuscator.config.ObfuscationConfig;
import org.dreeam.leaf.orebfuscator.config.OrebfuscatorConfig;
import org.dreeam.leaf.orebfuscator.config.WorldConfigBundle;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sends the real state of obfuscated blocks around a changed block, so blocks that became visible
 * are no longer shown as their replacement.
 */
@NullMarked
final class Deobfuscator {

    private final OrebfuscatorConfig config;
    private final @Nullable ObfuscationCache cache;
    // offsets within updateRadius (manhattan distance), closest first
    private final int[][] offsets;

    Deobfuscator(final OrebfuscatorConfig config, final @Nullable ObfuscationCache cache) {
        this.config = config;
        this.cache = cache;
        this.offsets = precomputeOffsets(config.updateRadius());
    }

    private static int[][] precomputeOffsets(final int radius) {
        final List<int[]> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius) {
                        offsets.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }

        offsets.sort(Comparator.<int[]>comparingInt(o -> Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2]))
            .thenComparingInt(o -> o[1]).thenComparingInt(o -> o[2]).thenComparingInt(o -> o[0]));
        return offsets.toArray(int[][]::new);
    }

    void deobfuscate(final ServerLevel level, final BlockPos origin) {
        final WorldConfigBundle bundle = this.config.world(level);
        final ObfuscationConfig obfuscationConfig = bundle.obfuscation();
        if (obfuscationConfig == null || !obfuscationConfig.isEnabled()) {
            return;
        }

        final ServerChunkCache chunkSource = level.getChunkSource();
        final LongSet updatedBlocks = new LongOpenHashSet();
        final LongArrayList updateOrder = new LongArrayList();
        final LongSet invalidChunks = new LongOpenHashSet();

        for (final int[] offset : this.offsets) {
            final int x = origin.getX() + offset[0];
            final int y = origin.getY() + offset[1];
            final int z = origin.getZ() + offset[2];

            final LevelChunk chunk = chunkSource.getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                continue;
            }

            final int blockState = BlockStateInfo.getId(chunk, x, y, z);
            final long position = BlockPos.asLong(x, y, z);
            if (!BlockFlags.isObfuscateBitSet(bundle.flags(blockState)) || !updatedBlocks.add(position)) {
                continue;
            }
            updateOrder.add(position);

            final long chunkPos = ChunkPos.pack(x >> 4, z >> 4);
            if (this.cache != null && invalidChunks.add(chunkPos)) {
                this.cache.invalidate(new ObfuscationCache.Key(level.dimension(), chunkPos));
            }
        }

        final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int i = 0; i < updateOrder.size(); i++) {
            chunkSource.blockChanged(position.set(updateOrder.getLong(i)));
        }
    }
}
