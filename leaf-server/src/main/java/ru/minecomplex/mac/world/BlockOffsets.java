package ru.minecomplex.mac.world;


import ru.minecomplex.mac.util.math.VanillaMath;

/**
 * {@code BlockBehaviour.Properties.offsetType} functions and {@code Mth.getSeed}, unchanged 1.21.4 – 26.3.
 * Verified against vanilla samples by {@code OffsetDifferentialTest}.
 */
public final class BlockOffsets {
    private BlockOffsets() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    /** {@code Mth.getSeed(int, int, int)}: note the first product is an {@code int} multiplication. */
    public static long seed(int x, int y, int z) {
        long seed = x * 3129871 ^ z * 116129781L ^ y;
        seed = seed * seed * 42317861L + seed * 11L;
        return seed >> 16;
    }

    /** Offset of the block at {@code (x, y, z)}: {@code [dx, dy, dz]}, zero for blocks without an offset. */
    public static double[] offset(BlockType block, int x, int z) {
        double[] result = new double[3];
        if (block.offsetKind() == BlockType.OffsetKind.NONE) {
            return result;
        }
        long seed = seed(x, 0, z);
        float max = block.maxHorizontalOffset();
        if (block.offsetKind() == BlockType.OffsetKind.XYZ) {
            result[1] = ((float) (seed >> 4 & 15L) / 15.0F - 1.0) * block.maxVerticalOffset();
        }
        result[0] = VanillaMath.clamp(((float) (seed & 15L) / 15.0F - 0.5) * 0.5, -max, max);
        result[2] = VanillaMath.clamp(((float) (seed >> 8 & 15L) / 15.0F - 0.5) * 0.5, -max, max);
        return result;
    }
}
