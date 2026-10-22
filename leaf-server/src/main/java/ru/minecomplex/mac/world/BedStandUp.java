package ru.minecomplex.mac.world;
import ru.minecomplex.mac.util.math.Trig;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.Axis;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.util.Tri;

import java.util.Set;

/**
 * {@code BedBlock.findStandUpPosition} (from 26.3 {@code AbstractBedBlock}; the same logic in all 13 releases) for the
 * local player in the client's world, as {@code LivingEntity.stopSleeping} calls it: the side away from the player's
 * look, the bed's surroundings ({@code bedSurroundStandUpOffsets}) and then on and behind it ({@code
 * bedAboveStandUpOffsets}), first avoiding dangerous blocks; a bunk bed (a bed below) also one block down and above.
 * Each candidate is {@code DismountHelper.findSafeDismountLocation}: the floor of the block (or of the block below when
 * it is empty and full height), the player type's 0.6 × 1.8 box there free of block collisions (empty context), not in
 * {@code #invalid_spawn_inside} and inside the border.
 */
public final class BedStandUp {
    private final CompensatedWorld world;
    private final BlockRegistry registry;
    private final BlockTags tags;
    private final Set<Unmodeled> gaps;
    /**
     * Whether an answer depended on something not known (a border phase, a block entity's shape).
     */
    private boolean unknown;

    private BedStandUp(CompensatedWorld world, BlockRegistry registry, BlockTags tags, Set<Unmodeled> gaps) {
        this.world = world;
        this.registry = registry;
        this.tags = tags;
        this.gaps = gaps;
    }

    /**
     * {@code LivingEntity.stopSleeping} in the client's world for the local player sleeping at the bed (x, y, z) with yaw
     * {@code yRot}: in a loaded chunk, on a bed ({@code BedBlock}; from 26.3 {@code AbstractBedBlock}), it stands up at
     * {@code findStandUpPosition} (or the block above's centre plus 0.1) turned towards the bed's bottom centre
     * ({@code (float) wrapDegrees(atan2(z, x) * 57.2957763671875 - 90.0)}: {@code 180.0F / (float) Math.PI} folded by the
     * compiler). A straw bed (26.3) may destroy itself first ({@code onStopSleeping}: {@code STRAW_BED_RULE} of the
     * environment, not modelled): not known.
     */
    public static Outcome stopSleeping(CompensatedWorld world, BlockRegistry registry, BlockTags tags, int x, int y, int z, float yRot, Set<Unmodeled> gaps) {
        if (!world.isLoaded(x >> 4, z >> 4)) return Outcome.STAYS;
        int state = world.getBlock(x, y, z);
        if (state == CompensatedWorld.UNLOADED) return Outcome.STAYS;
        var search = new BedStandUp(world, registry, tags, gaps);
        var type = registry.block(state);
        if (!type.isInstance(search.bedClass())) return Outcome.STAYS;
        if (type.isInstance("StrawBedBlock")) return Outcome.UNKNOWN;
        BlockDirection forward = BlockDirection.valueOf(facing(registry, state).toUpperCase(java.util.Locale.ROOT));
        Vec3d found = search.position(x, y, z, forward, yRot);
        if (search.unknown) return Outcome.UNKNOWN;
        Vec3d standUp = found != null ? found : new Vec3d(x + 0.5, y + 1 + 0.1, z + 0.5);
        Vec3d look = new Vec3d(x + 0.5, y, z + 0.5).subtract(standUp).normalize();
        float yaw = (float) ru.minecomplex.mac.util.math.VanillaMath.wrapDegrees(
                ru.minecomplex.mac.util.math.VanillaMath.atan2(look.z(), look.x()) * 57.2957763671875 - 90.0);
        return new Outcome(standUp, yaw, true);
    }

    private static String facing(BlockRegistry registry, int state) {
        for (String property : registry.properties(state).split(",")) {
            if (property.startsWith("facing=")) return property.substring("facing=".length());
        }
        throw new IllegalStateException("A bed without facing: " + registry.block(state).name());
    }

    private static int[][] surround(BlockDirection forward, BlockDirection side) {
        return new int[][]{
                {side.x, side.z},
                {side.x - forward.x, side.z - forward.z},
                {side.x - forward.x * 2, side.z - forward.z * 2},
                {-forward.x * 2, -forward.z * 2},
                {-side.x - forward.x * 2, -side.z - forward.z * 2},
                {-side.x - forward.x, -side.z - forward.z},
                {-side.x, -side.z},
                {-side.x + forward.x, -side.z + forward.z},
                {forward.x, forward.z},
                {side.x + forward.x, side.z + forward.z}
        };
    }

    /**
     * {@code Direction.getClockWise} (around Y).
     */
    static BlockDirection clockWise(BlockDirection d) {
        return switch (d) {
            case NORTH -> BlockDirection.EAST;
            case EAST -> BlockDirection.SOUTH;
            case SOUTH -> BlockDirection.WEST;
            case WEST -> BlockDirection.NORTH;
            default -> throw new IllegalArgumentException("No horizontal direction: " + d);
        };
    }

    /**
     * {@code Direction.isFacingAngle}: {@code normal.x * -sin(yaw) + normal.z * cos(yaw) > 0.0F}.
     */
    static boolean facingAngle(BlockDirection d, float yaw) {
        float radians = yaw * (float) (Math.PI / 180.0);
        float dx = -Trig.sin(radians);
        float dz = Trig.cos(radians);
        return d.x * dx + d.z * dz > 0.0F;
    }

    private Vec3d position(int bx, int by, int bz, BlockDirection forward, float yaw) {
        BlockDirection right = clockWise(forward);
        BlockDirection side = facingAngle(right, yaw) ? right.opposite() : right;
        int[][] surround = surround(forward, side), above = {{0, 0}, {-forward.x, -forward.z}};
        if (registry.block(state(bx, by - 1, bz)).isInstance(bedClass())) {
            Vec3d at = atOffset(bx, by, bz, surround, true);
            if (at == null) at = atOffset(bx, by - 1, bz, surround, true);
            if (at == null) at = atOffset(bx, by, bz, above, true);
            if (at == null) at = atOffset(bx, by, bz, surround, false);
            if (at == null) at = atOffset(bx, by - 1, bz, surround, false);
            if (at == null) at = atOffset(bx, by, bz, above, false);
            return at;
        }
        int[][] offsets = new int[surround.length + above.length][];
        System.arraycopy(surround, 0, offsets, 0, surround.length);
        System.arraycopy(above, 0, offsets, surround.length, above.length);
        Vec3d safe = atOffset(bx, by, bz, offsets, true);
        return safe != null ? safe : atOffset(bx, by, bz, offsets, false);
    }

    /**
     * {@code isBunkBed}: the block below is a bed ({@code BedBlock}; from 26.3 any {@code AbstractBedBlock}).
     */
    private String bedClass() {
        return registry.blocks().stream().anyMatch(b -> b.isInstance("AbstractBedBlock")) ? "AbstractBedBlock" : "BedBlock";
    }

    private Vec3d atOffset(int bx, int by, int bz, int[][] offsets, boolean checkDangerous) {
        for (int[] offset : offsets) {
            Vec3d at = safeDismount(bx + offset[0], by, bz + offset[1], checkDangerous);
            if (unknown) return null;
            if (at != null) return at;
        }
        return null;
    }

    /**
     * {@code DismountHelper.findSafeDismountLocation(PLAYER, level, pos, checkDangerous)}.
     */
    private Vec3d safeDismount(int x, int y, int z, boolean checkDangerous) {
        int here = state(x, y, z);
        if (checkDangerous && dangerous(here)) return null;
        double floor;
        VoxelShape shape = nonClimbable(here, x, y, z);
        if (!shape.isEmpty()) {
            floor = shape.max(Axis.Y);
        } else {
            double below = nonClimbable(state(x, y - 1, z), x, y - 1, z).max(Axis.Y);
            floor = below >= 1.0 ? below - 1.0 : Double.NEGATIVE_INFINITY;
        }
        // isBlockFloorValid
        if (Double.isInfinite(floor) || !(floor < 1.0)) return null;
        if (checkDangerous && floor <= 0.0 && dangerous(state(x, y - 1, z))) return null;
        Vec3d position = new Vec3d(x + 0.5, y + floor, z + 0.5);
        // EntityType.PLAYER.getDimensions().makeBoundingBox(position)
        float w = 0.6F / 2.0F, h = 1.8F;
        Aabb box = new Aabb(position.x() - w, position.y(), position.z() - w, position.x() + w, position.y() + h, position.z() + w);
        boolean[] blocked = {false};
        BlockCollisions.collect(this::blockOrAir, registry, BlockCollisions.emptyContext(registry, world, gaps::add), box,
                (bx, by, bz, collision) -> {
                    if (!collision.isEmpty()) blocked[0] = true;
                });
        if (blocked[0]) return null;
        if (tags.contains(BlockTag.INVALID_SPAWN_INSIDE, registry.block(here))
                || tags.contains(BlockTag.INVALID_SPAWN_INSIDE, registry.block(state(x, y + 1, z)))) return null;
        // WorldBorder.isWithinBounds(aabb): (minX, minZ) and (maxX - 1.0E-5F, maxZ - 1.0E-5F)
        Tri low = world.withinBorder(box.minX(), box.minZ()), high = world.withinBorder(box.maxX() - 1.0E-5F, box.maxZ() - 1.0E-5F);
        if (low == Tri.EITHER || high == Tri.EITHER) {
            unknown = true;
            gaps.add(Unmodeled.WORLD_BORDER_CLOCK);
            return null;
        }
        return low == Tri.TRUE && high == Tri.TRUE ? position : null;
    }

    /**
     * {@code DismountHelper.nonClimbableShape}: empty for {@code #climbable} and an open trapdoor, else the collision.
     */
    private VoxelShape nonClimbable(int state, int x, int y, int z) {
        var type = registry.block(state);
        if (tags.contains(BlockTag.CLIMBABLE, type)) return VoxelShape.EMPTY;
        if (type.isInstance("TrapDoorBlock") && registry.properties(state).contains("open=true"))
            return VoxelShape.EMPTY;
        return BlockCollisions.emptyContext(registry, world, gaps::add).shape(state, x, y, z);
    }

    /**
     * {@code EntityType.isBlockDangerous} of the player (immune to nothing, not fire immune): {@code isBurningBlock}
     * ({@code #fire}, lava, magma, a lit {@code #campfires} campfire, a lava cauldron), a wither rose, a sweet berry
     * bush, a cactus, powder snow.
     */
    private boolean dangerous(int state) {
        var type = registry.block(state);
        String name = type.name();
        if (tags.contains(BlockTag.FIRE, type) || name.equals("minecraft:lava") || name.equals("minecraft:magma_block")
                || name.equals("minecraft:lava_cauldron")) return true;
        if (tags.contains(BlockTag.CAMPFIRES, type) && registry.properties(state).contains("lit=true")) return true;
        return name.equals("minecraft:wither_rose") || name.equals("minecraft:sweet_berry_bush") || name.equals("minecraft:cactus")
                || name.equals("minecraft:powder_snow");
    }

    /**
     * The client's block; an unloaded chunk reads as air ({@code ClientChunkCache}'s empty chunk).
     */
    private int state(int x, int y, int z) {
        int state = world.getBlock(x, y, z);
        return state == CompensatedWorld.UNLOADED ? CompensatedWorld.AIR : state;
    }

    private int blockOrAir(int x, int y, int z) {
        return state(x, y, z);
    }

    /**
     * What {@code stopSleeping} does with the player before the standing pose.
     */
    public record Outcome(Vec3d position, float yRot, boolean known) {
        /**
         * No bed in a loaded chunk at the sleeping position: the player stays where it is.
         */
        public static final Outcome STAYS = new Outcome(null, 0.0F, true);
        /**
         * The stand-up position is not known.
         */
        public static final Outcome UNKNOWN = new Outcome(null, 0.0F, false);

        /**
         * Whether the player is moved (and turned to the bed, its pitch set to 0).
         */
        public boolean moves() {
            return position != null;
        }
    }
}
