package org.dreeam.leaf.orebfuscator.config;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import org.dreeam.leaf.orebfuscator.BlockStateInfo;
import org.dreeam.leaf.orebfuscator.LeafOrebfuscator;
import org.dreeam.leaf.orebfuscator.util.WeightedRandom;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Obfuscation and proximity settings resolved for one world.
 */
@NullMarked
public final class WorldConfigBundle {

    private final @Nullable ObfuscationConfig obfuscation;
    private final @Nullable ProximityConfig proximity;

    private final int @Nullable [] blockFlags;
    private final boolean needsObfuscation;

    private final int minY;
    private final int maxY;
    private final int minSectionIndex;
    private final int maxSectionIndex;
    private final int minBuildHeight;

    private final WeightedRandom @Nullable [] obfuscationRandoms;
    private final WeightedRandom @Nullable [] proximityRandoms;

    WorldConfigBundle(final ServerLevel level, final List<ObfuscationConfig> obfuscationConfigs, final List<ProximityConfig> proximityConfigs) {
        final String worldName = level.getWorld().getName();
        this.obfuscation = findConfig(obfuscationConfigs, worldName, "obfuscation");
        this.proximity = findConfig(proximityConfigs, worldName, "proximity");

        final boolean obfuscationEnabled = this.obfuscation != null && this.obfuscation.isEnabled();
        final boolean proximityEnabled = this.proximity != null && this.proximity.isEnabled();

        this.needsObfuscation = obfuscationEnabled || proximityEnabled;
        this.blockFlags = this.needsObfuscation ? this.createBlockFlags(obfuscationEnabled, proximityEnabled) : null;

        this.minY = Math.min(
            this.obfuscation != null ? this.obfuscation.minY() : DimensionType.MAX_Y,
            this.proximity != null ? this.proximity.minY() : DimensionType.MAX_Y);
        this.maxY = Math.max(
            this.obfuscation != null ? this.obfuscation.maxY() : DimensionType.MIN_Y,
            this.proximity != null ? this.proximity.maxY() : DimensionType.MIN_Y);

        this.minBuildHeight = level.getMinY();
        this.minSectionIndex = level.getSectionIndex(this.minY);
        this.maxSectionIndex = level.getSectionIndex(this.maxY - 1) + 1;

        final int height = level.getHeight();
        this.obfuscationRandoms = obfuscationEnabled ? this.obfuscation.createWeightedRandoms(this.minBuildHeight, height) : null;
        this.proximityRandoms = proximityEnabled ? this.proximity.createWeightedRandoms(this.minBuildHeight, height) : null;
    }

    private static <T extends WorldConfig> @Nullable T findConfig(final List<T> configs, final String worldName, final String type) {
        T match = null;
        for (final T config : configs) {
            if (config.matchesWorldName(worldName)) {
                if (match != null) {
                    LeafOrebfuscator.LOGGER.warn("world '{}' has more than one {} config, choosing first one", worldName, type);
                    break;
                }
                match = config;
            }
        }

        LeafOrebfuscator.debug("using '%s' %s config for world '%s'", match == null ? "null" : match.name(), type, worldName);
        return match;
    }

    private int[] createBlockFlags(final boolean obfuscationEnabled, final boolean proximityEnabled) {
        final int[] flags = new int[BlockStateInfo.count()];

        if (obfuscationEnabled) {
            for (final BlockParser.ConfigBlockValue value : this.obfuscation.hiddenBlocks()) {
                for (final Block block : value.blocks()) {
                    setBlockBits(flags, block, BlockFlags.FLAG_OBFUSCATE);
                }
            }
        }

        if (proximityEnabled) {
            for (final Map.Entry<BlockParser.ConfigBlockValue, Integer> entry : this.proximity.hiddenBlocks().entrySet()) {
                for (final Block block : entry.getKey().blocks()) {
                    setBlockBits(flags, block, entry.getValue());
                }
            }
            for (final Block block : this.proximity.allowForUseBlockBelow()) {
                setBlockBits(flags, block, BlockFlags.FLAG_ALLOW_FOR_USE_BLOCK_BELOW);
            }
        }

        return flags;
    }

    private static void setBlockBits(final int[] flags, final Block block, final int bits) {
        for (final BlockState state : block.getStateDefinition().getPossibleStates()) {
            final int id = Block.getId(state);
            int mask = flags[id] | bits;
            if (state.hasBlockEntity()) {
                mask |= BlockFlags.FLAG_BLOCK_ENTITY;
            }
            flags[id] = mask;
        }
    }

    public @Nullable ObfuscationConfig obfuscation() {
        return this.obfuscation;
    }

    public @Nullable ProximityConfig proximity() {
        return this.proximity;
    }

    public boolean needsObfuscation() {
        return this.needsObfuscation;
    }

    public int flags(final int blockState) {
        return this.blockFlags != null ? this.blockFlags[blockState] : 0;
    }

    /**
     * Flags with the proximity bit set if the block is proximity hidden at this height.
     */
    public int flags(final int blockState, final int y) {
        if (this.blockFlags == null) {
            return 0;
        }

        int flags = this.blockFlags[blockState];
        if (ProximityHeightCondition.match(flags, y)) {
            flags |= BlockFlags.FLAG_PROXIMITY;
        }
        return flags;
    }

    public int minSectionIndex() {
        return this.minSectionIndex;
    }

    public int maxSectionIndex() {
        return this.maxSectionIndex;
    }

    public boolean shouldObfuscate(final int y) {
        return y >= this.minY && y <= this.maxY;
    }

    public int nextRandomObfuscationBlock(final RandomGenerator random, final int y) {
        return this.obfuscationRandoms != null ? this.obfuscationRandoms[y - this.minBuildHeight].next(random) : 0;
    }

    public int nextRandomProximityBlock(final RandomGenerator random, final int y) {
        return this.proximityRandoms != null ? this.proximityRandoms[y - this.minBuildHeight].next(random) : 0;
    }
}
