package ru.minecomplex.mac.mechanics.collision;

import java.util.function.Consumer;

import ru.minecomplex.mac.collision.Shapes;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.engine.blockeffects.MovementBlocks;
import ru.minecomplex.mac.engine.movementtick.MovementTicker;
import ru.minecomplex.mac.player.state.PlayerState;

/**
 * {@code EntityCollisionContext} of the simulated player: the collision shapes of context-dependent blocks as the
 * player sees them. Captured like vanilla's context: {@code entityBottom = entity.getY()} and {@code descending =
 * isShiftKeyDown()} when the collision query starts; placement and always-collide-with-fluid are false.
 */
public final class EntityShapeContext implements BlockCollisions.ShapeContext {
    private final BlockRegistry registry;
    private final MovementBlocks blocks;
    private final double entityBottom;
    private final boolean descending;
    private final double fallDistance;
    private final boolean canWalkOnPowderSnow;
    private final boolean standsOnLava;
    private final ru.minecomplex.mac.world.BlockSource fluidBlocks;
    private final Consumer<Unmodeled> unmodeled;
    private final CompensatedWorld world;
    private final BlockDirection pistonNoClip;

    public EntityShapeContext(MovementBlocks blocks, PlayerState player, Consumer<Unmodeled> unmodeled) {
        this(blocks, player, unmodeled, null, null);
    }
    public EntityShapeContext(MovementBlocks blocks, PlayerState player, Consumer<Unmodeled> unmodeled, CompensatedWorld world, BlockDirection pistonNoClip) {
        this(blocks,player,unmodeled,world,pistonNoClip,world);
    }
    public EntityShapeContext(MovementBlocks blocks,PlayerState player,Consumer<Unmodeled> unmodeled,CompensatedWorld world,
                       BlockDirection pistonNoClip,ru.minecomplex.mac.world.BlockSource fluidBlocks) {
        this.world = world; this.pistonNoClip = pistonNoClip;
        this.registry = blocks.registry;
        this.blocks = blocks;
        this.entityBottom = player.y();
        this.descending = player.shiftKeyDown;
        this.fallDistance = player.fallDistance;
        this.canWalkOnPowderSnow = MovementTicker.canEntityWalkOnPowderSnow(player);
        this.standsOnLava=player.standsOnLava;this.fluidBlocks=fluidBlocks;
        this.unmodeled = unmodeled;
    }

    /** {@code EntityCollisionContext.isAbove}: {@code entityBottom > pos.getY() + shape.max(Y) - 1.0E-5F}. */
    private boolean isAbove(double shapeMaxY, int y) {
        return entityBottom > y + shapeMaxY - 1.0E-5F;
    }

    @Override
    public VoxelShape shape(int state, int x, int y, int z) {
        if (registry.block(state).isInstance("ShulkerBoxBlock")) {
            if (world == null) { unmodeled.accept(Unmodeled.CONTEXT_SHAPE); return VoxelShape.BLOCK; }
            var lid = world.shulker(new BlockPosition(x, y, z), true);
            for (var gap : world.dynamicGaps()) unmodeled.accept(gap);
            if (lid == null) return VoxelShape.BLOCK;
            var direction = BlockDirection.valueOf(ru.minecomplex.mac.world.piston.PistonProperties.get().value(state, "facing").toUpperCase(java.util.Locale.ROOT));
            return Shapes.create(ru.minecomplex.mac.world.shulker.ShulkerGeometry.bounds(direction, lid));
        }
        if (registry.block(state).isInstance("MovingPistonBlock")) {
            if (world == null) { unmodeled.accept(Unmodeled.CONTEXT_SHAPE); return VoxelShape.EMPTY; }
            var pos = new BlockPosition(x, y, z); var piston = world.piston(pos);
            if (piston == null) return VoxelShape.EMPTY;
            if (piston.direction() == null || !Float.isFinite(piston.progress()) || registry.block(piston.movedState()).isInstance("MovingPistonBlock")) {
                unmodeled.accept(Unmodeled.PISTON_BLOCK_ENTITY); return VoxelShape.EMPTY;
            }
            for (var gap : world.dynamicGaps()) unmodeled.accept(gap);
            // Moving block shapes explicitly use getCollisionShape(level, pos), i.e. CollisionContext.empty().
            return piston.collisionShape(registry, BlockCollisions.emptyContext(registry), pos, pistonNoClip);
        }
        if (!registry.has(state, BlockRegistry.FLAG_CONTEXT_DEPENDENT)) {
            return BlockCollisions.staticShape(registry, state, x, y, z);
        }
        if (blocks.isScaffolding(state)) {
            // ScaffoldingBlock.getCollisionShape; the empty-context shape is SHAPE_STABLE
            if (isAbove(1.0, y) && !descending) {
                return registry.collisionShape(state);
            }
            return blocks.scaffoldingUnstableBottom(state) && isAbove(0.0, y) ? MovementBlocks.SCAFFOLDING_UNSTABLE_BOTTOM : VoxelShape.EMPTY;
        }
        if (blocks.isPowderSnow(state)) {
            // PowderSnowBlock.getCollisionShape with the player as the entity
            if (fallDistance > 2.5) {
                return MovementBlocks.POWDER_SNOW_FALLING;
            }
            if (canWalkOnPowderSnow && isAbove(1.0, y) && !descending) {
                return VoxelShape.BLOCK;
            }
            return VoxelShape.EMPTY;
        }
        if (blocks.isLiquidBlock(state)) {
            // Strider's own liquid shape equals the legacy stable half-block shape.
            if(standsOnLava && registry.fluid(state).endsWith("lava") && registry.has(state,BlockRegistry.FLAG_FLUID_SOURCE)
                    && isAbove(0.5,y)){
                int above=bodyFluidState(x,y+1,z);
                if(above<0)return VoxelShape.EMPTY;
                if(!registry.fluid(above).endsWith("lava"))return Shapes.create(0,0,0,1,0.5,1);
            }
            return VoxelShape.EMPTY;
        }
        unmodeled.accept(Unmodeled.CONTEXT_SHAPE);
        return BlockCollisions.staticShape(registry, state, x, y, z);
    }

    private int bodyFluidState(int x,int y,int z){
        if(fluidBlocks==null){unmodeled.accept(Unmodeled.VEHICLE_WORLD);return -1;}
        return fluidBlocks.getBlock(x,y,z);
    }
}
