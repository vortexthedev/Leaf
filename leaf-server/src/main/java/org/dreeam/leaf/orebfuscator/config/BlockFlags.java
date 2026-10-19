package org.dreeam.leaf.orebfuscator.config;

import org.jspecify.annotations.NullMarked;

/**
 * Per block state flags of a world, the upper bits hold the {@link ProximityHeightCondition}.
 */
@NullMarked
public final class BlockFlags {

    public static final int FLAG_OBFUSCATE = 1;
    public static final int FLAG_BLOCK_ENTITY = 2;
    public static final int FLAG_PROXIMITY = 4;
    public static final int FLAG_USE_BLOCK_BELOW = 8;
    public static final int FLAG_ALLOW_FOR_USE_BLOCK_BELOW = 16;

    private BlockFlags() {
    }

    public static boolean isEmpty(final int mask) {
        return (mask & 0xFF) == 0;
    }

    public static boolean isObfuscateBitSet(final int mask) {
        return (mask & FLAG_OBFUSCATE) != 0;
    }

    public static boolean isBlockEntityBitSet(final int mask) {
        return (mask & FLAG_BLOCK_ENTITY) != 0;
    }

    public static boolean isProximityBitSet(final int mask) {
        return (mask & FLAG_PROXIMITY) != 0;
    }

    public static boolean isUseBlockBelowBitSet(final int mask) {
        return (mask & FLAG_USE_BLOCK_BELOW) != 0;
    }

    public static boolean isAllowForUseBlockBelowBitSet(final int mask) {
        return (mask & FLAG_ALLOW_FOR_USE_BLOCK_BELOW) != 0;
    }
}
