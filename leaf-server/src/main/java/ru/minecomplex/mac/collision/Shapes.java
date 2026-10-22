package ru.minecomplex.mac.collision;


/** Port of the collision part of vanilla {@code Shapes}. */
public final class Shapes {
    private Shapes() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    /** {@code Shapes.collide(Axis, AABB, Iterable<VoxelShape>, double)}. */
    public static double collide(Axis axis, Aabb box, Iterable<VoxelShape> shapes, double distance) {
        for (VoxelShape shape : shapes) {
            if (Math.abs(distance) < 1.0E-7) {
                return 0.0;
            }
            distance = shape.collide(axis, box, distance);
        }
        return distance;
    }

    /** {@code Shapes.create(AABB)}: empty if any extent is below 1e-7, a cube shape on eighth grids, else an array shape. */
    public static VoxelShape create(Aabb box) {
        return create(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    public static VoxelShape create(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (maxX - minX < 1.0E-7 || maxY - minY < 1.0E-7 || maxZ - minZ < 1.0E-7) {
            return VoxelShape.EMPTY;
        }
        int xBits = findBits(minX, maxX);
        int yBits = findBits(minY, maxY);
        int zBits = findBits(minZ, maxZ);
        if (xBits < 0 || yBits < 0 || zBits < 0) {
            return VoxelShape.of(VoxelShape.BLOCK.discrete(),
                    new Coords.Array(new double[]{minX, maxX}), new Coords.Array(new double[]{minY, maxY}),
                    new Coords.Array(new double[]{minZ, maxZ}), false);
        }
        if (xBits == 0 && yBits == 0 && zBits == 0) {
            return VoxelShape.BLOCK;
        }
        int xSize = 1 << xBits;
        int ySize = 1 << yBits;
        int zSize = 1 << zBits;
        DiscreteShape shape = DiscreteShape.filled(xSize, ySize, zSize,
                (int) Math.round(minX * xSize), (int) Math.round(minY * ySize), (int) Math.round(minZ * zSize),
                (int) Math.round(maxX * xSize), (int) Math.round(maxY * ySize), (int) Math.round(maxZ * zSize));
        return VoxelShape.of(shape, new Coords.Cube(xSize), new Coords.Cube(ySize), new Coords.Cube(zSize), true);
    }

    /** {@code Shapes.findBits}. */
    static int findBits(double min, double max) {
        if (min < -1.0E-7 || max > 1.0000001) {
            return -1;
        }
        for (int bits = 0; bits <= 3; bits++) {
            int parts = 1 << bits;
            double scaledMin = min * parts;
            double scaledMax = max * parts;
            boolean minAligned = Math.abs(scaledMin - Math.round(scaledMin)) < 1.0E-7 * parts;
            boolean maxAligned = Math.abs(scaledMax - Math.round(scaledMax)) < 1.0E-7 * parts;
            if (minAligned && maxAligned) {
                return bits;
            }
        }
        return -1;
    }

    /** {@code Shapes.joinIsNotEmpty(first, second, BooleanOp.AND)}: do the shapes share a filled region? */
    public static boolean intersect(VoxelShape first, VoxelShape second) {
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }
        if (first == second) {
            return true;
        }
        for (Axis axis : Axis.VALUES) {
            if (first.max(axis) < second.min(axis) - 1.0E-7) {
                return false;
            }
            if (second.max(axis) < first.min(axis) - 1.0E-7) {
                return false;
            }
        }
        // AND: neither "only first" nor "only second" regions matter
        IndexMergers.IndexMerger xMerger = IndexMergers.create(1, first.coords(Axis.X), second.coords(Axis.X), false, false);
        IndexMergers.IndexMerger yMerger = IndexMergers.create(xMerger.size() - 1, first.coords(Axis.Y), second.coords(Axis.Y), false, false);
        IndexMergers.IndexMerger zMerger = IndexMergers.create((xMerger.size() - 1) * (yMerger.size() - 1),
                first.coords(Axis.Z), second.coords(Axis.Z), false, false);
        DiscreteShape a = first.discrete();
        DiscreteShape b = second.discrete();
        return !xMerger.forMergedIndexes((x1, x2, xr) -> yMerger.forMergedIndexes((y1, y2, yr) -> zMerger.forMergedIndexes(
                (z1, z2, zr) -> !(a.isFullWide(x1, y1, z1) && b.isFullWide(x2, y2, z2)))));
    }

    /** Vanilla Shapes.or: voxel OR followed by greedy box optimization. */
    public static VoxelShape or(VoxelShape first, VoxelShape second) {
        VoxelShape joined = orUnoptimized(first, second);
        VoxelShape result = VoxelShape.EMPTY;
        for (Aabb box : joined.toAabbs()) result = orUnoptimized(result, create(box));
        return result;
    }

    private static VoxelShape orUnoptimized(VoxelShape first, VoxelShape second) {
        if (first == second) return first;
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        var xm = IndexMergers.create(1, first.coords(Axis.X), second.coords(Axis.X), true, true);
        var ym = IndexMergers.create(xm.size() - 1, first.coords(Axis.Y), second.coords(Axis.Y), true, true);
        var zm = IndexMergers.create((xm.size() - 1) * (ym.size() - 1), first.coords(Axis.Z), second.coords(Axis.Z), true, true);
        int nx = xm.size() - 1, ny = ym.size() - 1, nz = zm.size() - 1;
        long[] bits = new long[(nx * ny * nz + 63) / 64];
        var a = first.discrete(); var b = second.discrete();
        xm.forMergedIndexes((x1, x2, xr) -> ym.forMergedIndexes((y1, y2, yr) -> zm.forMergedIndexes((z1, z2, zr) -> {
            if (a.isFullWide(x1, y1, z1) || b.isFullWide(x2, y2, z2)) {
                int index = (xr * ny + yr) * nz + zr; bits[index >>> 6] |= 1L << (index & 63);
            }
            return true;
        })));
        Coords xs = xm.coords(), ys = ym.coords(), zs = zm.coords();
        return VoxelShape.of(new DiscreteShape(nx, ny, nz, bits), xs, ys, zs,
                xs instanceof Coords.Cube && ys instanceof Coords.Cube && zs instanceof Coords.Cube);
    }
}
