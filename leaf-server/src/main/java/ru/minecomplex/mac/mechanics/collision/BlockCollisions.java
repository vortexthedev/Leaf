package ru.minecomplex.mac.mechanics.collision;


import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.Shapes;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockOffsets;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockType;
import ru.minecomplex.mac.world.piston.PistonProperties;
import ru.minecomplex.mac.world.shulker.ShulkerGeometry;

/**
 * Port of vanilla {@code BlockCollisions} + {@code Cursor3D}: the block shapes that can collide with a box, in
 * vanilla's iteration order (the order matters: step-up candidates and {@code Shapes.collide} walk the list).
 * Verified against vanilla iteration by {@code BlockCollisionsDifferentialTest}.
 *
 * <p>Uses the empty collision context: the shape of each state is its vanilla shape with
 * {@code CollisionContext.empty()}, offset by position where vanilla does so. Entity-dependent shapes (liquids,
 * scaffolding, powder snow) are supplied by the movement engine through {@link ShapeContext}.
 */
public final class BlockCollisions {
    private BlockCollisions() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    /** Receives each colliding block with its shape moved to the block position. */
    public static interface Sink {
        void accept(int x, int y, int z, VoxelShape shape);
    }

    /**
     * Collision shape of a state at a position, before it is moved to the position. Returns {@link VoxelShape#BLOCK}
     * itself exactly where vanilla returns the {@code Shapes.block()} instance.
     */
    @FunctionalInterface
    public static interface ShapeContext {
        VoxelShape shape(int state, int x, int y, int z);
    }

    /** Resolved client-world predicate; callers must branch/report uncertainty for unresolved block entities. */
    @FunctionalInterface
    public static interface SuffocationContext {
        boolean isSuffocating(int state, int x, int y, int z);
    }

    /** {@code CollisionContext.empty()}: static shapes, position offsets applied. */
    public static ShapeContext emptyContext(BlockRegistry registry) {
        return (state, x, y, z) -> staticShape(registry, state, x, y, z);
    }
    /** Empty entity context with dynamic block entities from the same client-world hypothesis. */
    public static ShapeContext emptyContext(BlockRegistry registry, CompensatedWorld world,
                                           java.util.function.Consumer<ru.minecomplex.mac.engine.Unmodeled> uncertainty) {
        return new ShapeContext() {
            public VoxelShape shape(int state, int x, int y, int z) {
                var block = registry.block(state); var pos = new BlockPosition(x,y,z);
                if (block.isInstance("ShulkerBoxBlock")) {
                    var lid = world.shulker(pos,true); world.dynamicGaps().forEach(uncertainty);
                    if (lid == null) return VoxelShape.BLOCK;
                    var direction = BlockDirection.valueOf(PistonProperties.get().value(state,"facing").toUpperCase(java.util.Locale.ROOT));
                    return Shapes.create(ShulkerGeometry.bounds(direction,lid));
                }
                if (block.isInstance("MovingPistonBlock")) {
                    var piston = world.piston(pos); if (piston == null) return VoxelShape.EMPTY;
                    world.dynamicGaps().forEach(uncertainty);
                    if (piston.direction() == null || !Float.isFinite(piston.progress()) || registry.block(piston.movedState()).isInstance("MovingPistonBlock")) {
                        uncertainty.accept(ru.minecomplex.mac.engine.Unmodeled.PISTON_BLOCK_ENTITY); return VoxelShape.EMPTY;
                    }
                    return piston.collisionShape(registry,this,pos,null);
                }
                return staticShape(registry,state,x,y,z);
            }
        };
    }

    /** The shape of a state that does not depend on the colliding entity (offsets applied). */
    public static VoxelShape staticShape(BlockRegistry registry, int state, int x, int y, int z) {
        if (!registry.has(state, BlockRegistry.FLAG_POSITION_DEPENDENT)) {
            return registry.collisionShape(state);
        }
        double[] offset = BlockOffsets.offset(registry.block(state), x, z);
        return registry.baseCollisionShape(state).move(offset[0], offset[1], offset[2]);
    }

    /** {@code Mth.floor(double)}. */
    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    public static void collect(BlockSource world, BlockRegistry registry, ShapeContext context, Aabb box, Sink sink) {
        collect(world, registry, context, box, false, null, sink);
    }

    /** Vanilla BlockCollisions.onlySuffocatingBlocks, with the predicate evaluated before cursor-face filters. */
    public static void collect(BlockSource world, BlockRegistry registry, ShapeContext context, Aabb box,
                               boolean onlySuffocating, SuffocationContext suffocation, Sink sink) {
        if (onlySuffocating) java.util.Objects.requireNonNull(suffocation, "suffocation");
        int x0 = floor(box.minX() - 1.0E-7) - 1;
        int x1 = floor(box.maxX() + 1.0E-7) + 1;
        int y0 = floor(box.minY() - 1.0E-7) - 1;
        int y1 = floor(box.maxY() + 1.0E-7) + 1;
        int z0 = floor(box.minZ() - 1.0E-7) - 1;
        int z1 = floor(box.maxZ() + 1.0E-7) + 1;
        int width = x1 - x0 + 1;
        int height = y1 - y0 + 1;
        int depth = z1 - z0 + 1;
        int end = width * height * depth;
        VoxelShape entityShape = null;
        BlockType movingPiston = registry.block("minecraft:moving_piston");
        for (int index = 0; index < end; index++) {
            int cx = index % width;
            int slice = index / width;
            int cy = slice % height;
            int cz = slice / height;
            int type = 0;
            if (cx == 0 || cx == width - 1) {
                type++;
            }
            if (cy == 0 || cy == height - 1) {
                type++;
            }
            if (cz == 0 || cz == depth - 1) {
                type++;
            }
            if (type == 3) {
                continue;
            }
            int x = x0 + cx;
            int y = y0 + cy;
            int z = z0 + cz;
            int state = world.getBlock(x, y, z);
            if (state == CompensatedWorld.UNLOADED) {
                continue;
            }
            if (onlySuffocating && !suffocation.isSuffocating(state, x, y, z)) {
                continue;
            }
            if (type == 1 && !registry.has(state, BlockRegistry.FLAG_LARGE_COLLISION)) {
                continue;
            }
            if (type == 2 && registry.block(state) != movingPiston) {
                continue;
            }
            VoxelShape shape = context.shape(state, x, y, z);
            if (shape == VoxelShape.BLOCK) {
                // AABB.intersects with the block's unit cube
                if (box.minX() < x + 1.0 && box.maxX() > x && box.minY() < y + 1.0 && box.maxY() > y
                        && box.minZ() < z + 1.0 && box.maxZ() > z) {
                    sink.accept(x, y, z, shape.move(x, y, z));
                }
            } else {
                VoxelShape moved = shape.move(x, y, z);
                if (!moved.isEmpty()) {
                    if (entityShape == null) {
                        entityShape = Shapes.create(box);
                    }
                    if (Shapes.intersect(moved, entityShape)) {
                        sink.accept(x, y, z, moved);
                    }
                }
            }
        }
    }
}
