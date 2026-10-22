package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.replica.CompensatedWorld;

/**
 * Which block callbacks the client runs ({@code LevelChunk.setBlockState}, {@code Level.setBlock}): {@code onPlace} only
 * on the server in vanilla 26.3, so a block the server sets never leaves {@link Unmodeled#PISTON_NEIGHBOURS}; the
 * neighbours' {@code updateShape} of a client change without {@code UPDATE_KNOWN_SHAPE} still does where not ported.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class NeighbourCallbacksTest {
    private static CompensatedWorld world(BlockRegistry registry) {
        int bits = 32 - Integer.numberOfLeadingZeros(registry.stateCount() - 1);
        CompensatedWorld world = new CompensatedWorld("minecraft:overworld", -64, 384, 8, registry);
        PalettedSection[] sections = new PalettedSection[24];
        for (int i = 0; i < sections.length; i++) sections[i] = PalettedSection.single(CompensatedWorld.AIR, bits);
        world.load(new ChunkColumn(0, 0, sections));
        return world;
    }

    @Test
    void blocksTheServerSetsRunNoOnPlace() {
        BlockRegistry registry = BlockRegistry.get();
        CompensatedWorld world = world(registry);
        // rails, redstone wire, fire, a bed, a piston, water: custom onPlace (fire, rails: also custom updateShape)
        String[][] blocks = {{"minecraft:rail", "shape=north_south,waterlogged=false"}, {"minecraft:redstone_wire",
                "east=none,north=none,power=0,south=none,west=none"}, {"minecraft:fire", "age=0,east=false,north=false,south=false,up=false,west=false"},
                {"minecraft:piston", "extended=false,facing=north"}, {"minecraft:water", "level=0"}};
        int x = 2;
        for (String[] block : blocks) {
            int state = registry.stateId(block[0], block[1]);
            world.setServerVerifiedBlock(x, 70, 2, state);
            x += 2;
        }
        assertFalse(world.dynamicGaps().contains(Unmodeled.PISTON_NEIGHBOURS), "" + world.dynamicGaps());
    }

    @Test
    void aClientChangeStillReportsANeighboursUnportedShapeUpdate() {
        BlockRegistry registry = BlockRegistry.get();
        CompensatedWorld world = world(registry);
        world.setServerVerifiedBlock(5, 70, 5, registry.stateId("minecraft:stone", ""));
        world.setServerVerifiedBlock(6, 70, 5, registry.stateId("minecraft:oak_fence", "east=false,north=false,south=false,waterlogged=false,west=false"));
        assertFalse(world.dynamicGaps().contains(Unmodeled.PISTON_NEIGHBOURS));
        // breaking the stone client-side (flags 11) updates the fence's shape: FenceBlock.updateShape is not ported
        world.destroyBlock(5, 70, 5);
        // (destroyBlock hands the gap of its own change to the block prediction it belongs to)
        assertTrue(world.dynamicGaps().contains(Unmodeled.BLOCK_PREDICTION), world.dynamicGaps().toString());
    }
}
