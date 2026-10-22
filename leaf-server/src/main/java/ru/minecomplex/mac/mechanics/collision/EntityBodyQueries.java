package ru.minecomplex.mac.mechanics.collision;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.movementtick.MovementTicker;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.player.state.PlayerState;

/**
 * {@code level.noCollision(entity, box)} for another entity's body (a boat, a mob): blocks in the empty entity collision
 * context of a non-player, the environment's entity shapes and the world border.
 */
public final class EntityBodyQueries extends MovementTicker {
    public EntityBodyQueries(MovementEnvironment env, PlayerState body) {
        super(env, body, new FluidWorld.EntityFacts(false, body.bodyHeight, true));
    }

    public boolean free(Aabb box) {
        return noCollision(box);
    }
}
