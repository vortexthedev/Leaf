package ru.minecomplex.mac.engine;

import java.util.List;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.util.Tri;

/** The world as the simulated client sees it during one tick. Must not change while a tick is simulated. */
public interface MovementEnvironment {
    /** Fluid registry/tag facts have been resolved in this client-world hypothesis. */
    default boolean fluidsResolved() { return false; }

    /** Dimension's resolved ultraWarm/FAST_LAVA fact, independent of server protocol. */
    default Tri fastLava() { return Tri.EITHER; }

    BlockSource blocks();

    BlockRegistry registry();

    BlockTags tags();

    /** Override only with the block-entity state of this same client-world hypothesis. */
    default Tri suffocation(int state, int x, int y, int z) {
        if (registry().block(state).isInstance("ShulkerBoxBlock") && blocks() instanceof ru.minecomplex.mac.replica.CompensatedWorld world) {
            var lid = world.shulker(new ru.minecomplex.mac.world.BlockPosition(x, y, z), true);
            if (world.dynamicGaps().contains(Unmodeled.SHULKER_TICK_TIMING)) return Tri.EITHER;
            return lid == null || lid.isClosed() ? Tri.TRUE : Tri.FALSE;
        }
        return registry().suffocation(state, tags());
    }

    /** {@code level.hasChunkAt(pos)}: the client holds the chunk of this block column. */
    boolean hasChunkAt(int blockX, int blockZ);

    /** {@code level.getMinY()}. */
    int minY();

    /**
     * Whether {@code level.getEntities(entity, area)} lists a living entity a riptide spin attack touches with a known
     * outcome ({@code checkAutoSpinAttack}; {@code TRUE}: surely one and no player, see {@code EntityMotions.livingIn}):
     * {@code EITHER} where the level's entities are not followed.
     */
    default ru.minecomplex.mac.util.Tri livingIn(Aabb area) {
        return ru.minecomplex.mac.util.Tri.EITHER;
    }

    /** {@code level.getEntityCollisions(entity, box)}: shapes of collidable entities (boats, shulkers …). */
    List<VoxelShape> entityCollisions(Aabb box);

    /**
     * Resolved client border collider, gated by the source entity position and this query box.
     * Null means the resolved close-to-border predicate is false, never an unknown border state.
     * Interpolation follows client border ticks and retains unresolved packet-prefix phase uncertainty.
     */
    VoxelShape borderShape(Aabb box);
}
