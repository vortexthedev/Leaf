package org.dreeam.leaf.orebfuscator.obfuscation;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.dreeam.leaf.orebfuscator.BlockStateInfo;
import org.dreeam.leaf.orebfuscator.config.BlockFlags;
import org.dreeam.leaf.orebfuscator.config.ObfuscationConfig;
import org.dreeam.leaf.orebfuscator.config.ProximityConfig;
import org.dreeam.leaf.orebfuscator.config.ProximityHeightCondition;
import org.dreeam.leaf.orebfuscator.config.WorldConfigBundle;
import org.dreeam.leaf.orebfuscator.util.OrebfuscatorThread;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Obfuscates the section data of a chunk packet. The sections are read and written with the vanilla
 * {@link LevelChunkSection} network format, the decisions are made on the original block state ids.
 */
@NullMarked
public final class ChunkObfuscator {

    private static final int SECTION_SIZE = 16 * 16 * 16;

    private final ServerLevel level;
    private final WorldConfigBundle bundle;
    private final int chunkX;
    private final int chunkZ;
    // north, east, south and west neighbor in ChunkObfuscator#neighborIndex order, null if not loaded
    private final @Nullable LevelChunk[] neighbors;

    private final int minBuildHeight;
    private final int maxBuildHeight;
    private final LevelChunkSection[] sections;
    // original block state ids per section, null for sections that only contain air
    private final int @Nullable [][] blockIds;

    // set by isAdjacentBlockOccluding when the block is only hidden because of lava
    private boolean isLava;

    private ChunkObfuscator(final ServerLevel level, final WorldConfigBundle bundle, final int chunkX, final int chunkZ, final @Nullable LevelChunk[] neighbors, final byte[] data) {
        this.level = level;
        this.bundle = bundle;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.neighbors = neighbors;

        this.minBuildHeight = level.getMinY();
        this.maxBuildHeight = level.getMaxY() + 1;

        final int sectionCount = level.getSectionsCount();
        this.sections = new LevelChunkSection[sectionCount];
        this.blockIds = new int[sectionCount][];

        final FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        for (int i = 0; i < sectionCount; i++) {
            final LevelChunkSection section = new LevelChunkSection(level.palettedContainerFactory());
            section.read(input);
            this.sections[i] = section;
            this.blockIds[i] = section.hasOnlyAir() ? null : unpackIds(section.getStates());
        }
    }

    /**
     * @param neighbors the 4 neighbor chunks (north, east, south, west), null entries are treated as air
     */
    public static ObfuscationResult obfuscate(final ServerLevel level, final WorldConfigBundle bundle, final int chunkX, final int chunkZ,
                                              final @Nullable LevelChunk[] neighbors, final byte[] data) {
        return new ChunkObfuscator(level, bundle, chunkX, chunkZ, neighbors, data).process(data);
    }

    private static int[] unpackIds(final PalettedContainer<BlockState> states) {
        final PalettedContainer.Data<BlockState> data = states.data;
        final BitStorage storage = data.storage();
        final Palette<BlockState> palette = data.palette();

        final int[] ids = new int[SECTION_SIZE];
        storage.unpack(ids);

        // the global palette already stores block state ids
        if (!(palette instanceof GlobalPalette<BlockState>)) {
            final int[] paletteIds = new int[palette.getSize()];
            for (int i = 0; i < paletteIds.length; i++) {
                paletteIds[i] = Block.getId(palette.valueFor(i));
            }
            for (int i = 0; i < SECTION_SIZE; i++) {
                ids[i] = paletteIds[ids[i]];
            }
        }

        return ids;
    }

    private ObfuscationResult process(final byte[] originalData) {
        final WorldConfigBundle bundle = this.bundle;
        final ObfuscationConfig obfuscationConfig = bundle.obfuscation();
        final ProximityConfig proximityConfig = bundle.proximity();

        final LongSet blockEntities = new LongOpenHashSet();
        final LongArrayList proximityBlocks = new LongArrayList();
        final BooleanArrayList lavaObfuscated = new BooleanArrayList();

        final RandomGenerator random = Thread.currentThread() instanceof OrebfuscatorThread thread
            ? thread.random()
            : ThreadLocalRandom.current();

        final int baseX = this.chunkX << 4;
        final int baseZ = this.chunkZ << 4;

        int layerY = Integer.MIN_VALUE;
        int layerYBlockState = -1;
        boolean modified = false;

        final int lastSection = Math.min(this.sections.length - 1, bundle.maxSectionIndex());
        for (int sectionIndex = Math.max(0, bundle.minSectionIndex()); sectionIndex <= lastSection; sectionIndex++) {
            final int[] ids = this.blockIds[sectionIndex];
            if (ids == null) {
                continue;
            }

            final LevelChunkSection section = this.sections[sectionIndex];
            final int baseY = this.minBuildHeight + (sectionIndex << 4);

            for (int index = 0; index < SECTION_SIZE; index++) {
                final int y = baseY + (index >> 8 & 15);
                if (!bundle.shouldObfuscate(y)) {
                    continue;
                }

                int blockState = ids[index];
                final int obfuscateBits = bundle.flags(blockState, y);
                if (BlockFlags.isEmpty(obfuscateBits)) {
                    continue;
                }

                final int x = baseX + (index & 15);
                final int z = baseZ + (index >> 4 & 15);

                final boolean isObfuscateBitSet = BlockFlags.isObfuscateBitSet(obfuscateBits);
                boolean obfuscated = false;

                // should the block be obfuscated
                if (isObfuscateBitSet && obfuscationConfig != null && obfuscationConfig.shouldObfuscate(y)
                    && this.shouldObfuscate(x, y, z)) {
                    if (this.isLava) {
                        proximityBlocks.add(BlockPos.asLong(x, y, z));
                        lavaObfuscated.add(true);
                    }
                    if (obfuscationConfig.layerObfuscation()) {
                        if (layerY != y) {
                            layerY = y;
                            layerYBlockState = bundle.nextRandomObfuscationBlock(random, y);
                        }
                        blockState = layerYBlockState;
                    } else {
                        blockState = bundle.nextRandomObfuscationBlock(random, y);
                    }
                    obfuscated = true;
                }

                // should the block be proximity hidden
                if (!obfuscated && BlockFlags.isProximityBitSet(obfuscateBits) && proximityConfig != null
                    && proximityConfig.shouldObfuscate(y)) {
                    proximityBlocks.add(BlockPos.asLong(x, y, z));
                    lavaObfuscated.add(false);
                    if (BlockFlags.isUseBlockBelowBitSet(obfuscateBits)) {
                        final boolean allowNonOcclude = !isObfuscateBitSet || !ProximityHeightCondition.isPresent(obfuscateBits);
                        blockState = this.getBlockStateBelow(random, x, y, z, allowNonOcclude);
                    } else {
                        blockState = bundle.nextRandomProximityBlock(random, y);
                    }
                    obfuscated = true;
                }

                if (obfuscated) {
                    section.setBlockState(index & 15, index >> 8 & 15, index >> 4 & 15, Block.stateById(blockState), false);
                    modified = true;
                    if (BlockFlags.isBlockEntityBitSet(obfuscateBits)) {
                        blockEntities.add(BlockPos.asLong(x, y, z));
                    }
                }

                this.isLava = false;
            }
        }

        final byte[] data = modified ? this.write() : originalData;
        return new ObfuscationResult(data, blockEntities, proximityBlocks.toLongArray(), lavaObfuscated.toBooleanArray());
    }

    private byte[] write() {
        int size = 0;
        for (final LevelChunkSection section : this.sections) {
            size += section.getSerializedSize();
        }

        final byte[] data = new byte[size];
        final FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        output.writerIndex(0);
        for (final LevelChunkSection section : this.sections) {
            section.write(output);
        }

        if (output.writerIndex() != size) {
            throw new IllegalStateException("Didn't fill chunk buffer: expected " + size + " bytes, got " + output.writerIndex());
        }
        return data;
    }

    // first block below the given position that wouldn't be obfuscated in any way at the given position
    private int getBlockStateBelow(final RandomGenerator random, final int x, final int y, final int z, final boolean allowNonOcclude) {
        for (int targetY = y - 1; targetY > this.minBuildHeight; targetY--) {
            final int blockState = this.getOwnBlockState(x, targetY, z);
            if (allowNonOcclude || BlockStateInfo.isOccluding(blockState)) {
                final int mask = this.bundle.flags(blockState, y);
                if (BlockFlags.isEmpty(mask) || BlockFlags.isAllowForUseBlockBelowBitSet(mask)) {
                    return blockState;
                }
            }
        }

        return this.bundle.nextRandomProximityBlock(random, y);
    }

    private boolean shouldObfuscate(final int x, final int y, final int z) {
        return this.isAdjacentBlockOccluding(x, y + 1, z)
            && this.isAdjacentBlockOccluding(x, y - 1, z)
            && this.isAdjacentBlockOccluding(x + 1, y, z)
            && this.isAdjacentBlockOccluding(x - 1, y, z)
            && this.isAdjacentBlockOccluding(x, y, z + 1)
            && this.isAdjacentBlockOccluding(x, y, z - 1);
    }

    private boolean isAdjacentBlockOccluding(final int x, final int y, final int z) {
        final int blockId = this.getBlockState(x, y, z);
        if (BlockStateInfo.isOccluding(blockId)) {
            return true;
        }

        if (BlockStateInfo.isLava(blockId)) {
            this.isLava = BlockStateInfo.isLava(this.getBlockState(x, y + 1, z));
            return this.isLava;
        }

        return false;
    }

    // original block state of this chunk, x and z have to be inside of it
    private int getOwnBlockState(final int x, final int y, final int z) {
        final int[] ids = this.blockIds[this.level.getSectionIndex(y)];
        return ids != null ? ids[(y & 15) << 8 | (z & 15) << 4 | (x & 15)] : BlockStateInfo.AIR;
    }

    private int getBlockState(final int x, final int y, final int z) {
        if (y >= this.maxBuildHeight || y < this.minBuildHeight) {
            return BlockStateInfo.AIR;
        }

        final int offsetX = (x >> 4) - this.chunkX;
        final int offsetZ = (z >> 4) - this.chunkZ;
        if (offsetX == 0 && offsetZ == 0) {
            return this.getOwnBlockState(x, y, z);
        }

        return BlockStateInfo.getId(this.neighbors[neighborIndex(offsetX, offsetZ)], x, y, z);
    }

    /**
     * Index of the neighbor chunk, matches the order of {@link #NEIGHBOR_OFFSETS}.
     */
    static int neighborIndex(final int offsetX, final int offsetZ) {
        if (offsetX == 1) {
            return 0;
        } else if (offsetZ == 1) {
            return 1;
        } else if (offsetX == -1) {
            return 2;
        }
        return 3;
    }

    /**
     * Chunk offsets of the 4 neighbors: {x, z} pairs in {@link #neighborIndex} order.
     */
    public static final int[][] NEIGHBOR_OFFSETS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
}
