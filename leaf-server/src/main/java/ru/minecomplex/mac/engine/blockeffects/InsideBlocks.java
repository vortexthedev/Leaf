package ru.minecomplex.mac.engine.blockeffects;


import java.util.List;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.collection.LongMap;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * 26.3 ports of the block walks behind "which blocks did the entity pass through": {@code
 * BlockGetter.forEachBlockIntersectedBetween}, {@code addCollisionsAlongTravel}, {@code BlockPos.betweenClosed},
 * {@code BlockPos.betweenCornersInDirection}, {@code AABB.clip} and {@code AABB.collidedAlongVector}. Visit order and
 * the iteration numbers passed to the visitor are vanilla's: the caller's step budget depends on them.
 */
public final class InsideBlocks {
    private InsideBlocks() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    @FunctionalInterface
    public static interface Visitor {
        /** @return false to stop the walk */
        boolean visit(int x, int y, int z, int iteration);
    }

    /** {@code BlockGetter.forEachBlockIntersectedBetween}. */
    public static boolean forEachBlockIntersectedBetween(Vec3d from, Vec3d to, Aabb aabbAtTarget, Visitor visitor) {
        return forEachBlockIntersectedBetween(from, to, aabbAtTarget, visitor, new LongMap<>());
    }

    /** {@link #forEachBlockIntersectedBetween} with a set the caller reuses for the visited blocks (cleared here). */
    public static boolean forEachBlockIntersectedBetween(Vec3d from, Vec3d to, Aabb aabbAtTarget, Visitor visitor,
                                                  LongMap<Boolean> visited) {
        Vec3d travel = to.subtract(from);
        if (travel.lengthSqr() < (double) (1.0E-5F * 1.0E-5F)) {
            // BlockPos.betweenClosed(AABB): containing(min) .. containing(max), x fastest, then y, then z
            int minX = VanillaMath.floor(aabbAtTarget.minX());
            int minY = VanillaMath.floor(aabbAtTarget.minY());
            int minZ = VanillaMath.floor(aabbAtTarget.minZ());
            int maxX = VanillaMath.floor(aabbAtTarget.maxX());
            int maxY = VanillaMath.floor(aabbAtTarget.maxY());
            int maxZ = VanillaMath.floor(aabbAtTarget.maxZ());
            int width = maxX - minX + 1;
            int height = maxY - minY + 1;
            int depth = maxZ - minZ + 1;
            int end = width * height * depth;
            for (int index = 0; index < end; index++) {
                int x = index % width;
                int slice = index / width;
                int y = slice % height;
                int z = slice / height;
                if (!visitor.visit(minX + x, minY + y, minZ + z, 0)) {
                    return false;
                }
            }
            return true;
        }
        visited.clear();
        Aabb start = aabbAtTarget.move(travel.x() * -1.0, travel.y() * -1.0, travel.z() * -1.0);
        boolean[] stopped = {false};
        betweenCornersInDirection(start, travel, (x, y, z) -> {
            if (!visitor.visit(x, y, z, 0)) {
                stopped[0] = true;
                return false;
            }
            visited.put(asLong(x, y, z), Boolean.TRUE);
            return true;
        });
        if (stopped[0]) {
            return false;
        }
        int iterations = addCollisionsAlongTravel(visited, travel, aabbAtTarget, visitor);
        if (iterations < 0) {
            return false;
        }
        int last = iterations + 1;
        betweenCornersInDirection(aabbAtTarget, travel, (x, y, z) -> {
            if (visited.put(asLong(x, y, z), Boolean.TRUE) == null && !visitor.visit(x, y, z, last)) {
                stopped[0] = true;
                return false;
            }
            return true;
        });
        return !stopped[0];
    }

    /** {@code BlockGetter.addCollisionsAlongTravel}: -1 if the visitor stopped the walk. */
    private static int addCollisionsAlongTravel(LongMap<Boolean> visited, Vec3d deltaMove, Aabb aabbAtTarget, Visitor visitor) {
        double boxSizeX = aabbAtTarget.maxX() - aabbAtTarget.minX();
        double boxSizeY = aabbAtTarget.maxY() - aabbAtTarget.minY();
        double boxSizeZ = aabbAtTarget.maxZ() - aabbAtTarget.minZ();
        int[] cornerDir = furthestCorner(deltaMove);
        double centerX = VanillaMath.lerp(0.5, aabbAtTarget.minX(), aabbAtTarget.maxX());
        double centerY = VanillaMath.lerp(0.5, aabbAtTarget.minY(), aabbAtTarget.maxY());
        double centerZ = VanillaMath.lerp(0.5, aabbAtTarget.minZ(), aabbAtTarget.maxZ());
        Vec3d toCorner = new Vec3d(centerX + boxSizeX * 0.5 * cornerDir[0], centerY + boxSizeY * 0.5 * cornerDir[1],
                centerZ + boxSizeZ * 0.5 * cornerDir[2]);
        Vec3d fromCorner = toCorner.subtract(deltaMove);
        int cornerX = VanillaMath.floor(fromCorner.x());
        int cornerY = VanillaMath.floor(fromCorner.y());
        int cornerZ = VanillaMath.floor(fromCorner.z());
        int signX = VanillaMath.sign(deltaMove.x());
        int signY = VanillaMath.sign(deltaMove.y());
        int signZ = VanillaMath.sign(deltaMove.z());
        double tDeltaX = signX == 0 ? Double.MAX_VALUE : signX / deltaMove.x();
        double tDeltaY = signY == 0 ? Double.MAX_VALUE : signY / deltaMove.y();
        double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / deltaMove.z();
        double tX = tDeltaX * (signX > 0 ? 1.0 - VanillaMath.frac(fromCorner.x()) : VanillaMath.frac(fromCorner.x()));
        double tY = tDeltaY * (signY > 0 ? 1.0 - VanillaMath.frac(fromCorner.y()) : VanillaMath.frac(fromCorner.y()));
        double tZ = tDeltaZ * (signZ > 0 ? 1.0 - VanillaMath.frac(fromCorner.z()) : VanillaMath.frac(fromCorner.z()));
        int iterations = 0;
        boolean[] stopped = {false};
        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    cornerX += signX;
                    tX += tDeltaX;
                } else {
                    cornerZ += signZ;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                cornerY += signY;
                tY += tDeltaY;
            } else {
                cornerZ += signZ;
                tZ += tDeltaZ;
            }
            Vec3d hit = clip(cornerX, cornerY, cornerZ, cornerX + 1, cornerY + 1, cornerZ + 1, fromCorner, toCorner);
            if (hit != null) {
                iterations++;
                // Double arithmetic: the bytecode adds the double constant (double) 1.0E-5F (the decompiled source shows
                // "cornerX + 1.0E-5F", which would be float arithmetic in Java source)
                double hitX = VanillaMath.clamp(hit.x(), cornerX + (double) 1.0E-5F, cornerX + 1.0 - (double) 1.0E-5F);
                double hitY = VanillaMath.clamp(hit.y(), cornerY + (double) 1.0E-5F, cornerY + 1.0 - (double) 1.0E-5F);
                double hitZ = VanillaMath.clamp(hit.z(), cornerZ + (double) 1.0E-5F, cornerZ + 1.0 - (double) 1.0E-5F);
                int oppositeX = VanillaMath.floor(hitX - boxSizeX * cornerDir[0]);
                int oppositeY = VanillaMath.floor(hitY - boxSizeY * cornerDir[1]);
                int oppositeZ = VanillaMath.floor(hitZ - boxSizeZ * cornerDir[2]);
                int currentIteration = iterations;
                betweenCornersInDirection(cornerX, cornerY, cornerZ, oppositeX, oppositeY, oppositeZ, deltaMove, (x, y, z) -> {
                    if (visited.put(asLong(x, y, z), Boolean.TRUE) == null && !visitor.visit(x, y, z, currentIteration)) {
                        stopped[0] = true;
                        return false;
                    }
                    return true;
                });
                if (stopped[0]) {
                    return -1;
                }
            }
        }
        return iterations;
    }

    /** {@code BlockGetter.getFurthestCorner}. */
    private static int[] furthestCorner(Vec3d direction) {
        double xDot = Math.abs(1.0 * direction.x() + 0.0 * direction.y() + 0.0 * direction.z());
        double yDot = Math.abs(0.0 * direction.x() + 1.0 * direction.y() + 0.0 * direction.z());
        double zDot = Math.abs(0.0 * direction.x() + 0.0 * direction.y() + 1.0 * direction.z());
        int xSign = direction.x() >= 0.0 ? 1 : -1;
        int ySign = direction.y() >= 0.0 ? 1 : -1;
        int zSign = direction.z() >= 0.0 ? 1 : -1;
        if (xDot <= yDot && xDot <= zDot) {
            return new int[]{-xSign, -zSign, ySign};
        }
        return yDot <= zDot ? new int[]{zSign, -ySign, -xSign} : new int[]{-ySign, xSign, -zSign};
    }

    @FunctionalInterface
    static interface PositionSink {
        boolean accept(int x, int y, int z);
    }

    /** {@code BlockPos.betweenCornersInDirection(AABB, Vec3)}. */
    static void betweenCornersInDirection(Aabb box, Vec3d direction, PositionSink sink) {
        betweenCornersInDirection(VanillaMath.floor(box.minX()), VanillaMath.floor(box.minY()), VanillaMath.floor(box.minZ()),
                VanillaMath.floor(box.maxX()), VanillaMath.floor(box.maxY()), VanillaMath.floor(box.maxZ()), direction, sink);
    }

    /**
     * {@code BlockPos.betweenCornersInDirection(int…, Vec3)}: starts at the corner the direction moves away from and
     * walks the axes in {@code Direction.axisStepOrder(direction)}, the last one fastest.
     */
    static void betweenCornersInDirection(int firstX, int firstY, int firstZ, int secondX, int secondY, int secondZ,
                                          Vec3d direction, PositionSink sink) {
        int minX = Math.min(firstX, secondX);
        int minY = Math.min(firstY, secondY);
        int minZ = Math.min(firstZ, secondZ);
        int maxX = Math.max(firstX, secondX);
        int maxY = Math.max(firstY, secondY);
        int maxZ = Math.max(firstZ, secondZ);
        int[] diff = {maxX - minX, maxY - minY, maxZ - minZ};
        int startX = direction.x() >= 0.0 ? minX : maxX;
        int startY = direction.y() >= 0.0 ? minY : maxY;
        int startZ = direction.z() >= 0.0 ? minZ : maxZ;
        // axis indices 0 = X, 1 = Y, 2 = Z; Direction.axisStepOrder: |x| < |z| ? YZX : YXZ
        int[] axes = Math.abs(direction.x()) < Math.abs(direction.z()) ? new int[]{1, 2, 0} : new int[]{1, 0, 2};
        double[] components = {direction.x(), direction.y(), direction.z()};
        int[][] steps = new int[3][3];
        int[] maxima = new int[3];
        for (int i = 0; i < 3; i++) {
            int axis = axes[i];
            steps[i][axis] = components[axis] >= 0.0 ? 1 : -1;
            maxima[i] = diff[axis];
        }
        for (int first = 0; first <= maxima[0]; first++) {
            for (int second = 0; second <= maxima[1]; second++) {
                for (int third = 0; third <= maxima[2]; third++) {
                    int x = startX + steps[0][0] * first + steps[1][0] * second + steps[2][0] * third;
                    int y = startY + steps[0][1] * first + steps[1][1] * second + steps[2][1] * third;
                    int z = startZ + steps[0][2] * first + steps[1][2] * second + steps[2][2] * third;
                    if (!sink.accept(x, y, z)) {
                        return;
                    }
                }
            }
        }
    }

    /** {@code AABB.clip(minX…maxZ, from, to)}: the first entry point of the segment into the box, or null. */
    public static Vec3d clip(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Vec3d from, Vec3d to) {
        double[] scale = {1.0};
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        boolean hit = false;
        if (dx > 1.0E-7) {
            hit |= clipPoint(scale, dx, dy, dz, minX, minY, maxY, minZ, maxZ, from.x(), from.y(), from.z());
        } else if (dx < -1.0E-7) {
            hit |= clipPoint(scale, dx, dy, dz, maxX, minY, maxY, minZ, maxZ, from.x(), from.y(), from.z());
        }
        if (dy > 1.0E-7) {
            hit |= clipPoint(scale, dy, dz, dx, minY, minZ, maxZ, minX, maxX, from.y(), from.z(), from.x());
        } else if (dy < -1.0E-7) {
            hit |= clipPoint(scale, dy, dz, dx, maxY, minZ, maxZ, minX, maxX, from.y(), from.z(), from.x());
        }
        if (dz > 1.0E-7) {
            hit |= clipPoint(scale, dz, dx, dy, minZ, minX, maxX, minY, maxY, from.z(), from.x(), from.y());
        } else if (dz < -1.0E-7) {
            hit |= clipPoint(scale, dz, dx, dy, maxZ, minX, maxX, minY, maxY, from.z(), from.x(), from.y());
        }
        if (!hit) {
            return null;
        }
        double s = scale[0];
        return from.add(s * dx, s * dy, s * dz);
    }

    /** {@code AABB.clipPoint}: true when this face is hit closer than the best so far. */
    private static boolean clipPoint(double[] scale, double da, double db, double dc, double point, double minB, double maxB,
                                     double minC, double maxC, double fromA, double fromB, double fromC) {
        double s = (point - fromA) / da;
        double pb = fromB + s * db;
        double pc = fromC + s * dc;
        if (0.0 < s && s < scale[0] && minB - 1.0E-7 < pb && pb < maxB + 1.0E-7 && minC - 1.0E-7 < pc && pc < maxC + 1.0E-7) {
            scale[0] = s;
            return true;
        }
        return false;
    }

    /**
     * {@code AABB.collidedAlongVector}: does the box sweep along the vector touch any of the boxes? The boxes grow by
     * half the box size minus 1e-7.
     */
    public static boolean collidedAlongVector(Aabb box, Vec3d vector, List<Aabb> boxes) {
        Vec3d from = new Vec3d(VanillaMath.lerp(0.5, box.minX(), box.maxX()), VanillaMath.lerp(0.5, box.minY(), box.maxY()),
                VanillaMath.lerp(0.5, box.minZ(), box.maxZ()));
        Vec3d to = from.add(vector);
        double halfX = (box.maxX() - box.minX()) * 0.5 - 1.0E-7;
        double halfY = (box.maxY() - box.minY()) * 0.5 - 1.0E-7;
        double halfZ = (box.maxZ() - box.minZ()) * 0.5 - 1.0E-7;
        for (Aabb part : boxes) {
            Aabb inflated = new Aabb(part.minX() - halfX, part.minY() - halfY, part.minZ() - halfZ,
                    part.maxX() + halfX, part.maxY() + halfY, part.maxZ() + halfZ);
            if (contains(inflated, to) || contains(inflated, from)) {
                return true;
            }
            if (clip(inflated.minX(), inflated.minY(), inflated.minZ(), inflated.maxX(), inflated.maxY(), inflated.maxZ(), from, to) != null) {
                return true;
            }
        }
        return false;
    }

    /** {@code AABB.contains(Vec3)}: min inclusive, max exclusive. */
    private static boolean contains(Aabb box, Vec3d point) {
        return point.x() >= box.minX() && point.x() < box.maxX() && point.y() >= box.minY() && point.y() < box.maxY()
                && point.z() >= box.minZ() && point.z() < box.maxZ();
    }

    /** {@code BlockPos.asLong}. */
    public static long asLong(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) | ((long) z & 0x3FFFFFFL) << 12;
    }
}
