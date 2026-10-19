package org.dreeam.leaf.orebfuscator;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Properties of every block state, indexed by {@link Block#getId(BlockState)}, so the obfuscation
 * loops only work with plain arrays.
 */
@NullMarked
public final class BlockStateInfo {

    public static final int AIR = Block.getId(Blocks.AIR.defaultBlockState());

    private static final byte OCCLUDING = 1;
    private static final byte LAVA = 1 << 1;
    private static final byte FLUID = 1 << 2;

    private static final byte[] FLAGS = new byte[Block.BLOCK_STATE_REGISTRY.size()];

    static {
        for (final BlockState state : Block.BLOCK_STATE_REGISTRY) {
            byte flags = 0;
            if (state.isSolidRender()) {
                flags |= OCCLUDING;
            }
            if (state.is(Blocks.LAVA)) {
                flags |= LAVA;
            }
            if (!state.getFluidState().isEmpty()) {
                flags |= FLUID;
            }
            FLAGS[Block.getId(state)] = flags;
        }
    }

    private BlockStateInfo() {
    }

    public static int count() {
        return FLAGS.length;
    }

    public static boolean isOccluding(final int id) {
        return (FLAGS[id] & OCCLUDING) != 0;
    }

    public static boolean isLava(final int id) {
        return (FLAGS[id] & LAVA) != 0;
    }

    /**
     * Block state id at the given position of a loaded chunk, air for missing chunks or positions
     * outside of the world, same as the plugin did for neighbor chunks.
     */
    public static int getId(final @Nullable LevelChunk chunk, final int x, final int y, final int z) {
        if (chunk == null) {
            return AIR;
        }

        final LevelChunkSection[] sections = chunk.getSections();
        final int sectionIndex = chunk.getSectionIndex(y);
        if (sectionIndex >= 0 && sectionIndex < sections.length) {
            final LevelChunkSection section = sections[sectionIndex];
            if (section != null && !section.hasOnlyAir()) {
                return Block.getId(section.getBlockState(x & 15, y & 15, z & 15));
            }
        }

        return AIR;
    }
}
