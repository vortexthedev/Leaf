package ru.minecomplex.mac.collision;

/**
 * Port of vanilla {@code VoxelShape} collision ({@code ArrayVoxelShape} / {@code CubeVoxelShape}), operation for
 * operation, from vanilla 26.3. Verified against vanilla results by
 * {@code CollisionDifferentialTest}. Immutable.
 */
public final class VoxelShape {
    public static final VoxelShape EMPTY = new VoxelShape(false, new DiscreteShape(0, 0, 0, new long[0]),
            new Coords.Array(new double[]{0.0}), new Coords.Array(new double[]{0.0}), new Coords.Array(new double[]{0.0}));

    /** {@code CubeVoxelShape} with its own {@code findIndex}; only shapes straight from the block table are cubes. */
    private final boolean cube;
    private final DiscreteShape shape;
    private final Coords xs;
    private final Coords ys;
    private final Coords zs;

    private VoxelShape(boolean cube, DiscreteShape shape, Coords xs, Coords ys, Coords zs) {
        this.cube = cube;
        this.shape = shape;
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
    }

    /** A {@code CubeVoxelShape}: coordinates {@code i / size}, filled voxels in vanilla index order. */
    public static VoxelShape cube(int xSize, int ySize, int zSize, long[] bits) {
        return new VoxelShape(true, new DiscreteShape(xSize, ySize, zSize, bits),
                new Coords.Cube(xSize), new Coords.Cube(ySize), new Coords.Cube(zSize));
    }

    /** An {@code ArrayVoxelShape} with explicit coordinate lists ({@code size + 1} values per axis). */
    public static VoxelShape array(int xSize, int ySize, int zSize, long[] bits, double[] xs, double[] ys, double[] zs) {
        return new VoxelShape(false, new DiscreteShape(xSize, ySize, zSize, bits),
                new Coords.Array(xs), new Coords.Array(ys), new Coords.Array(zs));
    }

    /** {@code Shapes.block()}: the singleton full cube. */
    public static final VoxelShape BLOCK = cube(1, 1, 1, new long[]{1L});

    /** An array shape over explicit coordinate lists (used by {@code Shapes.create}). */
    static VoxelShape of(DiscreteShape shape, Coords xs, Coords ys, Coords zs, boolean cube) {
        return new VoxelShape(cube, shape, xs, ys, zs);
    }

    public boolean isEmpty() {
        return shape.isEmpty();
    }

    /** {@code VoxelShape.toAabbs}, including vanilla neighbour merging and enumeration order. */
    public java.util.List<Aabb> toAabbs() {
        java.util.List<Aabb> boxes = new java.util.ArrayList<>();
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> boxes.add(new Aabb(
                xs.get(x0), ys.get(y0), zs.get(z0), xs.get(x1), ys.get(y1), zs.get(z1))));
        return boxes;
    }

    DiscreteShape discrete() {
        return shape;
    }

    /** {@code VoxelShape.min(Axis)}: lowest filled coordinate, {@code +∞} when empty. */
    public double min(Axis axis) {
        int first = shape.firstFull(axis);
        return first >= shape.size(axis) ? Double.POSITIVE_INFINITY : get(axis, first);
    }

    /** {@code VoxelShape.max(Axis)}: highest filled coordinate, {@code -∞} when empty. */
    public double max(Axis axis) {
        int last = shape.lastFull(axis);
        return last <= 0 ? Double.NEGATIVE_INFINITY : get(axis, last);
    }

    /** Coordinates along an axis ({@code getCoords}); for step-up candidate heights. */
    public int coordCount(Axis axis) {
        return coords(axis).size();
    }

    public double coord(Axis axis, int index) {
        return coords(axis).get(index);
    }

    Coords coords(Axis axis) {
        return switch (axis) {
            case X -> xs;
            case Y -> ys;
            case Z -> zs;
        };
    }

    private double get(Axis axis, int index) {
        return coords(axis).get(index);
    }

    /** {@code VoxelShape.move}: an array shape whose coordinates are {@code coordinate + offset}. */
    public VoxelShape move(double dx, double dy, double dz) {
        if (isEmpty()) {
            return EMPTY;
        }
        return new VoxelShape(false, shape, new Coords.Offset(xs, dx), new Coords.Offset(ys, dy), new Coords.Offset(zs, dz));
    }

    private int findIndex(Axis axis, double value) {
        if (cube) {
            int size = shape.size(axis);
            return (int) Math.floor(clamp(value * size, -1.0, size));
        }
        // Mth.binarySearch(0, size + 1, i -> value < get(axis, i)) - 1
        int from = 0;
        int length = shape.size(axis) + 1;
        while (length > 0) {
            int half = length / 2;
            int middle = from + half;
            if (value < get(axis, middle)) {
                length = half;
            } else {
                from = middle + 1;
                length -= half + 1;
            }
        }
        return from - 1;
    }

    /** {@code Mth.clamp(double, double, double)}. */
    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /** {@code VoxelShape.collide}: how far {@code box} can move by {@code distance} along {@code axis}. */
    public double collide(Axis axis, Aabb box, double distance) {
        return collideX(AxisCycle.between(axis, Axis.X), box, distance);
    }

    private double collideX(AxisCycle axisCycle, Aabb box, double distance) {
        if (isEmpty()) {
            return distance;
        }
        if (Math.abs(distance) < 1.0E-7) {
            return 0.0;
        }
        AxisCycle inverse = axisCycle.inverse();
        Axis axis = inverse.cycle(Axis.X);
        Axis axis2 = inverse.cycle(Axis.Y);
        Axis axis3 = inverse.cycle(Axis.Z);
        double max = box.max(axis);
        double min = box.min(axis);
        int minIndex = findIndex(axis, min + 1.0E-7);
        int maxIndex = findIndex(axis, max - 1.0E-7);
        int from2 = Math.max(0, findIndex(axis2, box.min(axis2) + 1.0E-7));
        int to2 = Math.min(shape.size(axis2), findIndex(axis2, box.max(axis2) - 1.0E-7) + 1);
        int from3 = Math.max(0, findIndex(axis3, box.min(axis3) + 1.0E-7));
        int to3 = Math.min(shape.size(axis3), findIndex(axis3, box.max(axis3) - 1.0E-7) + 1);
        int size = shape.size(axis);
        if (distance > 0.0) {
            for (int i = maxIndex + 1; i < size; i++) {
                for (int j = from2; j < to2; j++) {
                    for (int k = from3; k < to3; k++) {
                        if (shape.isFullWide(inverse, i, j, k)) {
                            double gap = get(axis, i) - max;
                            if (gap >= -1.0E-7) {
                                distance = Math.min(distance, gap);
                            }
                            return distance;
                        }
                    }
                }
            }
        } else if (distance < 0.0) {
            for (int i = minIndex - 1; i >= 0; i--) {
                for (int j = from2; j < to2; j++) {
                    for (int k = from3; k < to3; k++) {
                        if (shape.isFullWide(inverse, i, j, k)) {
                            double gap = get(axis, i + 1) - min;
                            if (gap <= 1.0E-7) {
                                distance = Math.max(distance, gap);
                            }
                            return distance;
                        }
                    }
                }
            }
        }
        return distance;
    }
}
