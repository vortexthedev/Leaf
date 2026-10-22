package ru.minecomplex.mac.collision;

/**
 * {@code BitSetDiscreteVoxelShape}: filled voxels of a shape, index {@code (x * ySize + y) * zSize + z}.
 * Immutable after construction.
 */
final class DiscreteShape {
    final int xSize;
    final int ySize;
    final int zSize;
    private final long[] bits;
    /** Bounds of the filled voxels ({@code BitSetDiscreteVoxelShape.xMin..zMax}); min = size, max = 0 when empty. */
    private final int[] minFull = new int[3];
    private final int[] maxFull = new int[3];

    DiscreteShape(int xSize, int ySize, int zSize, long[] bits) {
        if (xSize < 0 || ySize < 0 || zSize < 0) {
            throw new IllegalArgumentException("Need all positive sizes: x: " + xSize + ", y: " + ySize + ", z: " + zSize);
        }
        this.xSize = xSize;
        this.ySize = ySize;
        this.zSize = zSize;
        this.bits = bits;
        minFull[0] = xSize;
        minFull[1] = ySize;
        minFull[2] = zSize;
        for (int x = 0; x < xSize; x++) {
            for (int y = 0; y < ySize; y++) {
                for (int z = 0; z < zSize; z++) {
                    if (isFull(x, y, z)) {
                        minFull[0] = Math.min(minFull[0], x);
                        minFull[1] = Math.min(minFull[1], y);
                        minFull[2] = Math.min(minFull[2], z);
                        maxFull[0] = Math.max(maxFull[0], x + 1);
                        maxFull[1] = Math.max(maxFull[1], y + 1);
                        maxFull[2] = Math.max(maxFull[2], z + 1);
                    }
                }
            }
        }
    }

    /** {@code BitSetDiscreteVoxelShape.withFilledBounds}: every voxel in {@code [min, max)} filled. */
    static DiscreteShape filled(int xSize, int ySize, int zSize, int xMin, int yMin, int zMin, int xMax, int yMax, int zMax) {
        long[] bits = new long[(xSize * ySize * zSize + 63) / 64];
        for (int x = xMin; x < xMax; x++) {
            for (int y = yMin; y < yMax; y++) {
                for (int z = zMin; z < zMax; z++) {
                    int index = (x * ySize + y) * zSize + z;
                    bits[index >>> 6] |= 1L << (index & 63);
                }
            }
        }
        return new DiscreteShape(xSize, ySize, zSize, bits);
    }

    int size(Axis axis) {
        return axis.choose(xSize, ySize, zSize);
    }

    int firstFull(Axis axis) {
        return minFull[axis.ordinal()];
    }

    int lastFull(Axis axis) {
        return maxFull[axis.ordinal()];
    }

    boolean isFull(int x, int y, int z) {
        int index = (x * ySize + y) * zSize + z;
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }

    boolean isFullWide(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0) {
            return false;
        }
        return x < xSize && y < ySize && z < zSize && isFull(x, y, z);
    }

    boolean isFullWide(AxisCycle cycle, int x, int y, int z) {
        return isFullWide(cycle.cycle(x, y, z, Axis.X), cycle.cycle(x, y, z, Axis.Y), cycle.cycle(x, y, z, Axis.Z));
    }

    boolean isEmpty() {
        for (long word : bits) {
            if (word != 0) {
                return false;
            }
        }
        return true;
    }

    /** Vanilla's greedy Z strips, then X rectangles, then Y boxes; consumes only a private copy. */
    void forAllBoxes(BoxConsumer consumer) {
        java.util.BitSet remaining = java.util.BitSet.valueOf(bits);
        for (int y = 0; y < ySize; y++) {
            for (int x = 0; x < xSize; x++) {
                int startZ = -1;
                for (int z = 0; z <= zSize; z++) {
                    if (z < zSize && remaining.get(index(x, y, z))) {
                        if (startZ == -1) startZ = z;
                    } else if (startZ != -1) {
                        int endX = x;
                        int endY = y;
                        remaining.clear(index(x, y, startZ), index(x, y, z));
                        while (stripFull(remaining, startZ, z, endX + 1, y)) {
                            remaining.clear(index(endX + 1, y, startZ), index(endX + 1, y, z));
                            endX++;
                        }
                        while (rectangleFull(remaining, x, endX + 1, startZ, z, endY + 1)) {
                            for (int cx = x; cx <= endX; cx++) {
                                remaining.clear(index(cx, endY + 1, startZ), index(cx, endY + 1, z));
                            }
                            endY++;
                        }
                        consumer.accept(x, y, startZ, endX + 1, endY + 1, z);
                        startZ = -1;
                    }
                }
            }
        }
    }

    private int index(int x, int y, int z) {
        return (x * ySize + y) * zSize + z;
    }

    private boolean stripFull(java.util.BitSet remaining, int startZ, int endZ, int x, int y) {
        return x < xSize && y < ySize
                && remaining.nextClearBit(index(x, y, startZ)) >= index(x, y, endZ);
    }

    private boolean rectangleFull(java.util.BitSet remaining, int startX, int endX, int startZ, int endZ, int y) {
        for (int x = startX; x < endX; x++) {
            if (!stripFull(remaining, startZ, endZ, x, y)) return false;
        }
        return true;
    }

    @FunctionalInterface
    interface BoxConsumer {
        void accept(int x0, int y0, int z0, int x1, int y1, int z1);
    }
}
