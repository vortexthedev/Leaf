package ru.minecomplex.mac.world.shulker;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockPosition;

import java.util.List;

/**
 * Shulker box opening pushes. Queries include vanilla spectator/visibility filtering.
 */
public final class ShulkerPush {
    private ShulkerPush() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    public static void push(BlockPosition pos, BlockDirection direction, ShulkerAnimation after, Query query) {
        Aabb box = ShulkerGeometry.delta(direction, after.progressOld(), after.progress(),
                new Vec3d(pos.x() + 0.5, pos.y(), pos.z() + 0.5));
        for (Entity entity : query.entities(box))
            if (!entity.ignored()) {
                entity.move(new Vec3d((box.maxX() - box.minX() + 0.01) * direction.x,
                        (box.maxY() - box.minY() + 0.01) * direction.y,
                        (box.maxZ() - box.minZ() + 0.01) * direction.z));
            }
    }

    public static interface Entity {
        boolean ignored();

        /**
         * Real Entity.move(SHULKER_BOX), without piston accumulator or piston NOCLIP.
         */
        void move(Vec3d delta);
    }

    @FunctionalInterface
    public static interface Query {
        List<? extends Entity> entities(Aabb box);
    }
}
