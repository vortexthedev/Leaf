package ru.minecomplex.mac.mechanics.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class EntityCollisionTest {

    private final BlockRegistry registry = BlockRegistry.get();
    private final Map<Long, Integer> blocks = new HashMap<>();
    private final List<Aabb> entityQueries = new ArrayList<>();

    private final EntityCollision.Environment environment = new EntityCollision.Environment() {
        @Override
        public BlockSource blocks() {
            return (x, y, z) -> blocks.getOrDefault(key(x, y, z), 0);
        }

        @Override
        public BlockRegistry registry() {
            return registry;
        }

        @Override
        public BlockCollisions.ShapeContext shapes() {
            return BlockCollisions.emptyContext(registry);
        }

        @Override
        public List<VoxelShape> entityCollisions(Aabb box) {
            entityQueries.add(box);
            return List.of();
        }

        @Override
        public VoxelShape borderShape(Aabb box) {
            return null;
        }
    };

    private static long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ z;
    }

    private void place(int x, int y, int z, String block, String properties) {
        int state = registry.stateId(block, properties);
        blocks.put(key(x, y, z), state);
    }

    /** A standing player's box (0.6 × 1.8) with its feet at {@code (x, y, z)}. */
    private static Aabb player(double x, double y, double z) {
        return new Aabb(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    private Vec3d collide(Aabb box, Vec3d movement, boolean onGround) {
        return EntityCollision.collide(environment, box, movement, onGround, 0.6F);
    }

    @Test
    void landsOnTopOfABlock() {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                place(x, 63, z, "minecraft:stone", "");
            }
        }
        Vec3d result = collide(player(0.5, 64.2, 0.5), new Vec3d(0, -0.5, 0), false);
        assertEquals(new Vec3d(0, 64.0 - 64.2, 0), result, "stops exactly on the top face");
    }

    @Test
    void stepsOntoABottomSlab() {
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                place(x, 63, z, "minecraft:stone", "");
            }
        }
        place(1, 64, 0, "minecraft:stone_slab", "type=bottom,waterlogged=false");
        Vec3d result = collide(player(0.5, 64, 0.5), new Vec3d(0.3, 0, 0), true);
        assertEquals(0.5, result.y(), "stepped up by the slab height");
        assertEquals(0.3, result.x(), "and kept moving");
    }

    @Test
    void doesNotStepOntoAFullBlock() {
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                place(x, 63, z, "minecraft:stone", "");
            }
        }
        place(1, 64, 0, "minecraft:stone", "");
        place(1, 65, 0, "minecraft:stone", "");
        Vec3d result = collide(player(0.5, 64, 0.5), new Vec3d(0.3, 0, 0), true);
        assertEquals(0.0, result.y());
        assertEquals(1.0 - 0.8, result.x(), 1e-15, "pushed against the block face");
    }

    @Test
    void stepCandidatesAreDistinctSortedAndSkipTheReachedHeight() {
        List<VoxelShape> colliders = new ArrayList<>();
        colliders.add(registry.collisionShape(registry.stateId("minecraft:stone_slab", "type=bottom,waterlogged=false")).move(0, 64, 0));
        colliders.add(registry.collisionShape(registry.stateId("minecraft:stone_slab", "type=bottom,waterlogged=false")).move(1, 64, 0));
        colliders.add(registry.collisionShape(registry.stateId("minecraft:stone", "")).move(2, 63, 0));
        float[] heights = EntityCollision.candidateStepUpHeights(new Aabb(0, 64, 0, 0.6, 65.8, 0.6), colliders, 0.6F, 0.0F);
        assertArrayEquals(new float[]{0.5F}, heights, "0 is the reached height, 1 is above the step, the stone is below");
    }

    @Test
    void entityQueryGrowsByStepHeightFrom26_3() {
        collide(player(0.5, 64, 0.5), new Vec3d(0.1, 0, 0), true);
        Aabb expected = player(0.5, 64, 0.5).expandTowards(0.1, 0, 0);
        // Independent expectation: 26.3 adds the step height to the entity query.
        expected = expected.expandTowards(0, 0.6F, 0);
        assertEquals(1, entityQueries.size());
        assertEquals(expected, entityQueries.getFirst());
    }
}
