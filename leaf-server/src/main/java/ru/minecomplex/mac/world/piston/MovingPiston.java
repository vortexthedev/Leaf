package ru.minecomplex.mac.world.piston;

import ru.minecomplex.mac.collision.Shapes;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockRegistry;

import java.util.Locale;

/**
 * Immutable client PistonMovingBlockEntity fields; lifecycle/ticker side effects are owned by PistonWorld.
 */
public record MovingPiston(int movedState, BlockDirection direction, boolean extending, boolean source,
                           float progress, float progressOld, int deathTicks, long lastTicked) {
    public MovingPiston(int movedState, BlockDirection direction, boolean extending, boolean source) {
        this(movedState, direction, extending, source, 0.0F, 0.0F, 0, 0);
    }

    public BlockDirection movementDirection() {
        return extending ? direction : direction.opposite();
    }

    public float getProgress(float partialTick) {
        if (partialTick > 1.0F) partialTick = 1.0F;
        return progressOld + partialTick * (progress - progressOld);
    }

    public float extendedProgress(float progress) {
        return extending ? progress - 1.0F : 1.0F - progress;
    }

    public MovingPiston advance(long gameTime) {
        float next = progress + 0.5F;
        if (next >= 1.0F) next = 1.0F;
        return new MovingPiston(movedState, direction, extending, source, next, progress, deathTicks, gameTime);
    }

    public MovingPiston waitForRemoval(long gameTime) {
        return new MovingPiston(movedState, direction, extending, source, progress, progress, deathTicks + 1, gameTime);
    }

    public VoxelShape collisionShape(BlockRegistry registry, BlockCollisions.ShapeContext context, BlockPosition pos, BlockDirection noClip) {
        var properties = PistonProperties.get();
        VoxelShape base = VoxelShape.EMPTY;
        if (!extending && source && registry.block(movedState).isInstance("PistonBaseBlock")) {
            int extended = properties.with(movedState, "extended", "true");
            base = context.shape(extended, pos.x(), pos.y(), pos.z());
        }
        if (progress < 1.0 && noClip == movementDirection()) return base;
        int state;
        if (source) {
            state = properties.with(properties.defaultState("minecraft:piston_head"), "facing", direction.name().toLowerCase(Locale.ROOT));
            state = properties.with(state, "short", Boolean.toString(extending != (1.0F - progress < 0.25F)));
        } else state = movedState;
        float offset = extendedProgress(progress);
        double dx = direction.x * offset, dy = direction.y * offset, dz = direction.z * offset;
        return Shapes.or(base, context.shape(state, pos.x(), pos.y(), pos.z()).move(dx, dy, dz));
    }
}
