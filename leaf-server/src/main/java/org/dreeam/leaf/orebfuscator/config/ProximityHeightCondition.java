package org.dreeam.leaf.orebfuscator.config;

import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.NullMarked;

/**
 * Height range of a proximity hidden block, packed into the 25 most significant bits of the block
 * flags: 12 bit min y | 12 bit max y | 1 bit present.
 */
@NullMarked
public final class ProximityHeightCondition {

    public static final int MATCH_ALL = create(DimensionType.MIN_Y, DimensionType.MAX_Y);

    private ProximityHeightCondition() {
    }

    private static int unsigned(final int y) {
        return (Mth.clamp(y, DimensionType.MIN_Y, DimensionType.MAX_Y) - DimensionType.MIN_Y) & 0xFFF;
    }

    private static int signed(final int y) {
        return y + DimensionType.MIN_Y;
    }

    public static int create(final int minY, final int maxY) {
        return unsigned(minY) << 20 | unsigned(maxY) << 8 | 0x80;
    }

    public static int remove(final int hideCondition) {
        return hideCondition & 0x7F;
    }

    public static boolean match(final int hideCondition, final int y) {
        return isPresent(hideCondition) && getMinY(hideCondition) <= y && getMaxY(hideCondition) >= y;
    }

    public static boolean isPresent(final int hideCondition) {
        return (hideCondition & 0x80) != 0;
    }

    public static int getMinY(final int hideCondition) {
        return signed(hideCondition >>> 20);
    }

    public static int getMaxY(final int hideCondition) {
        return signed(hideCondition << 12 >>> 20);
    }
}
