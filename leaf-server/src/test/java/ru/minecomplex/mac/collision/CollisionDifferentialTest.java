package ru.minecomplex.mac.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import ru.minecomplex.mac.vanilla.VanillaSamples;
import ru.minecomplex.mac.world.BlockRegistry;

/**
 * Our port of {@code VoxelShape.collide} against vanilla results recorded by {@code tools/datagen}: shapes of
 * random block states moved to random block positions (including near the world border, where coordinate
 * additions lose precision), random boxes (a third snapped to 1/16 so faces touch) and distances. Results must
 * be bit-identical.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class CollisionDifferentialTest {

    @Test
    void collideMatchesVanilla() {
        BlockRegistry registry = BlockRegistry.get();
        List<VanillaSamples.Collide> samples = VanillaSamples.get().collide;
        int touching = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            VanillaSamples.Collide sample = samples.get(i);
            List<VoxelShape> shapes = new ArrayList<>(sample.states().length);
            for (int s = 0; s < sample.states().length; s++) {
                shapes.add(registry.collisionShape(sample.states()[s]).move((double) (sample.baseX() + sample.offsets()[s][0]),
                        (double) (sample.baseY() + sample.offsets()[s][1]), (double) (sample.baseZ() + sample.offsets()[s][2])));
            }
            double actual = Shapes.collide(sample.axis(), sample.box(), shapes, sample.distance());
            if (Double.compare(sample.result(), actual) != 0) {
                failures.add("sample " + i + " states " + java.util.Arrays.toString(sample.states()) + " axis " + sample.axis()
                        + " box " + sample.box() + " distance " + sample.distance() + ": vanilla " + sample.result() + ", ours " + actual);
            }
            if (sample.result() != sample.distance()) {
                touching++;
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + samples.size() + " differ, first: " + failures.getFirst());
        }
        assertTrue(touching > samples.size() / 20, "samples must exercise collisions, only " + touching + " did");
    }

    @Test
    void registryIsConsistent() {
        BlockRegistry registry = BlockRegistry.get();
        assertTrue(registry.isAir(0), "state 0 is air in every version");
        assertEquals("minecraft:air", registry.block(0).name());
        int stone = registry.stateId("minecraft:stone", "");
        assertEquals(1, stone);
        assertTrue(registry.has(stone, BlockRegistry.FLAG_FULL_BLOCK_COLLISION));
        assertEquals(0.6F, registry.block(stone).friction());
        assertEquals(0.98F, registry.block("minecraft:ice").friction());
        assertEquals(0.989F, registry.block("minecraft:blue_ice").friction());
        assertEquals(0.8F, registry.block("minecraft:slime_block").friction());
        assertEquals(0.4F, registry.block("minecraft:soul_sand").speedFactor());
        assertEquals(0.5F, registry.block("minecraft:honey_block").jumpFactor());

        int water = registry.stateId("minecraft:water", "level=0");
        assertEquals("minecraft:water", registry.fluid(water));
        assertTrue(registry.has(water, BlockRegistry.FLAG_FLUID_SOURCE));
        assertTrue(registry.has(water, BlockRegistry.FLAG_CONTEXT_DEPENDENT), "liquids collide for walk-on-fluid entities");
        assertTrue(registry.collisionShape(water).isEmpty());

        int scaffolding = registry.stateId("minecraft:scaffolding", "bottom=false,distance=0,waterlogged=false");
        assertTrue(registry.has(scaffolding, BlockRegistry.FLAG_CONTEXT_DEPENDENT));
        int powderSnow = registry.block("minecraft:powder_snow").firstStateId();
        assertTrue(registry.has(powderSnow, BlockRegistry.FLAG_CONTEXT_DEPENDENT));
        int stoneSlab = registry.stateId("minecraft:stone_slab", "type=bottom,waterlogged=false");
        assertTrue(!registry.has(stoneSlab, BlockRegistry.FLAG_CONTEXT_DEPENDENT) && !registry.has(stoneSlab, BlockRegistry.FLAG_POSITION_DEPENDENT));
        int bamboo = registry.block("minecraft:bamboo").firstStateId();
        assertTrue(registry.has(bamboo, BlockRegistry.FLAG_POSITION_DEPENDENT));
        assertTrue(registry.has(bamboo, BlockRegistry.FLAG_OFFSET));

        int fence = registry.stateId("minecraft:oak_fence", "east=false,north=false,south=false,waterlogged=false,west=false");
        assertTrue(registry.has(fence, BlockRegistry.FLAG_LARGE_COLLISION), "fences are 1.5 blocks tall");
    }
}
