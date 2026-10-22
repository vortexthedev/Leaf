package ru.minecomplex.mac.engine.blockeffects;


import java.util.List;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.world.BlockPosition;

/**
 * {@code PotentSulfurBlockEntity.LAUNCH_ENTITY_TICKER} (26.2+), which the client runs for an {@code erupting} or
 * {@code continuous} potent sulfur block in {@code tickBlockEntities}: the entities in the geyser's column, each of
 * which {@code checkFallDistanceAccumulation}, then an entity that simulates its movement, is not a flying player, no
 * passenger and not in {@code #not_affected_by_geysers} (vanilla: the ender dragon) gains {@code (0, 0.2F, 0)} while its
 * {@code y} velocity is below {@code 0.3F + waterBlocks * 0.1}.
 */
public final class GeyserLaunch {
    private GeyserLaunch() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    /** An entity of the geyser's query. */
    public static interface Entity {
        Vec3d velocity();

        void velocity(Vec3d value);

        double fallDistance();

        void fallDistance(double value);

        /** {@code canSimulateMovement() && !(player flying) && !isPassenger() && !is(#not_affected_by_geysers)}. */
        boolean launchable();
    }

    /** {@code level.getEntitiesOfClass(Entity.class, box, NO_SPECTATORS.and(ENTITY_STILL_ALIVE))}. */
    @FunctionalInterface
    public static interface Query {
        List<? extends Entity> entities(Aabb box);
    }

    /** {@code new AABB(pos.above()).expandTowards(0.0, forceHeight - 1, 0.0)}. */
    public static Aabb column(BlockPosition pos, int forceHeight) {
        double minY = pos.y() + 1, maxY = pos.y() + 2;
        double dy = forceHeight - 1;
        if (dy < 0.0) minY += dy; else maxY += dy;
        return new Aabb(pos.x(), minY, pos.z(), pos.x() + 1, maxY, pos.z() + 1);
    }

    public static void launch(Aabb column, int waterBlocks, Query query) {
        for (Entity entity : query.entities(column)) {
            Vec3d velocity = entity.velocity();
            // Entity.checkFallDistanceAccumulation
            if (velocity.y() > -0.5 && entity.fallDistance() > 1.0) entity.fallDistance(1.0);
            if (entity.launchable() && velocity.y() < 0.3F + waterBlocks * 0.1) {
                // addDeltaMovement: a finite momentum
                entity.velocity(entity.velocity().add(0.0, 0.2F, 0.0));
            }
        }
    }
}
