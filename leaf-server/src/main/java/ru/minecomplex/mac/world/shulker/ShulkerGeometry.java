package ru.minecomplex.mac.world.shulker;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.world.BlockDirection;

/**
 * Shulker.getProgressDeltaAabb for a unit shulker box, preserving expand/contract/move order.
 */
public final class ShulkerGeometry {
    private ShulkerGeometry() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    public static Aabb delta(BlockDirection direction, float from, float to, Vec3d bottomCenter) {
        double max = Math.max(from, to), min = Math.min(from, to);
        Aabb box = new Aabb(-0.5, 0.0, -0.5, 0.5, 1.0, 0.5)
                .expandTowards(direction.x * max, direction.y * max, direction.z * max);
        double x = -direction.x * (1.0 + min), y = -direction.y * (1.0 + min), z = -direction.z * (1.0 + min);
        // AABB.contract: positive values move the maximum face, negative values move the minimum face.
        double minX = box.minX(), minY = box.minY(), minZ = box.minZ(), maxX = box.maxX(), maxY = box.maxY(), maxZ = box.maxZ();
        if (x < 0.0) minX -= x;
        else if (x > 0.0) maxX -= x;
        if (y < 0.0) minY -= y;
        else if (y > 0.0) maxY -= y;
        if (z < 0.0) minZ -= z;
        else if (z > 0.0) maxZ -= z;
        box = new Aabb(minX, minY, minZ, maxX, maxY, maxZ);
        return box.move(bottomCenter.x(), bottomCenter.y(), bottomCenter.z());
    }

    public static Aabb bounds(BlockDirection direction, ShulkerAnimation lid) {
        return delta(direction, -1.0F, 0.5F * lid.getProgress(1.0F), new Vec3d(0.5, 0.0, 0.5));
    }
}
