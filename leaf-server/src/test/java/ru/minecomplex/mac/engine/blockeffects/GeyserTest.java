package ru.minecomplex.mac.engine.blockeffects;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.fixture.outbound.BlockEntityData;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.ChunkColumn;
import ru.minecomplex.mac.world.PalettedSection;

/**
 * Potent sulfur geysers ( {@code ../Client/src/main/java/net/minecraft/.../PotentSulfurBlockEntity.java},
 * {@code PotentSulfurBlock.getTicker}): the client's {@code LAUNCH_ENTITY_TICKER} in {@code tickBlockEntities}.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class GeyserTest {
    private static final BlockRegistry REGISTRY = BlockRegistry.get();

    private static int state(String block, String properties) {
        int id = REGISTRY.stateId(block, properties);
        if (id < 0) throw new IllegalArgumentException(block + "[" + properties + "]");
        return id;
    }

    private static int sulfur(String state) {
        return state("minecraft:potent_sulfur", "potent_sulfur_state=" + state);
    }

    private static final int WATER = state("minecraft:water", "level=0");

    private static CompensatedWorld world() {
        int bits = 32 - Integer.numberOfLeadingZeros(REGISTRY.stateCount() - 1);
        CompensatedWorld world = new CompensatedWorld("minecraft:overworld", -64, 384, 8, REGISTRY);
        PalettedSection[] sections = new PalettedSection[24];
        for (int i = 0; i < sections.length; i++) sections[i] = PalettedSection.single(CompensatedWorld.AIR, bits);
        world.load(new ChunkColumn(0, 0, sections));
        return world;
    }

    /** A geyser at (5, 60, 5) under {@code water} blocks of water. */
    private static CompensatedWorld geyser(String state, int water) {
        CompensatedWorld world = world();
        world.setBlock(5, 60, 5, sulfur(state));
        for (int y = 61; y < 61 + water; y++) world.setBlock(5, y, 5, WATER);
        return world;
    }

    static final class Body implements GeyserLaunch.Entity {
        Vec3d velocity;
        double fallDistance;
        boolean launchable = true;

        Body(double vy, double fallDistance) {
            velocity = new Vec3d(0.1, vy, -0.1);
            this.fallDistance = fallDistance;
        }

        @Override public Vec3d velocity() { return velocity; }
        @Override public void velocity(Vec3d value) { velocity = value; }
        @Override public double fallDistance() { return fallDistance; }
        @Override public void fallDistance(double value) { fallDistance = value; }
        @Override public boolean launchable() { return launchable; }
    }

    /** Ticks the block entities with the bodies; returns the areas the geysers queried. */
    private static List<Aabb> tick(CompensatedWorld world, Body... bodies) {
        List<Aabb> areas = new ArrayList<>();
        world.tickBlockEntities(0L, null, null, area -> List.of(), area -> {
            areas.add(area);
            return List.of(bodies);
        });
        return areas;
    }

    @Test
    void anEruptingGeyserLiftsWhatIsSlowerThanItsWaterAllows() {
        CompensatedWorld world = geyser("erupting", 3);
        // three water blocks: the source block is 64, the force reaches 18 blocks from 61
        Body still = new Body(0.0, 0.0), fast = new Body(0.3F + 3 * 0.1, 0.0), justBelow = new Body(Math.nextDown(0.3F + 3 * 0.1), 0.0);
        List<Aabb> areas = tick(world, still, fast, justBelow);
        assertEquals(List.of(new Aabb(5, 61, 5, 6, 79, 6)), areas);
        assertEquals(new Vec3d(0.1, 0.0 + 0.2F, -0.1), still.velocity);
        assertEquals(new Vec3d(0.1, 0.3F + 3 * 0.1, -0.1), fast.velocity);
        assertEquals(Math.nextDown(0.3F + 3 * 0.1) + 0.2F, justBelow.velocity.y());
        assertFalse(world.dynamicGaps().contains(Unmodeled.GEYSER), world.dynamicGaps().toString());
        // a continuous geyser alike
        Body body = new Body(0.0, 0.0);
        tick(geyser("continuous", 1), body);
        assertEquals(0.0 + 0.2F, body.velocity.y());
    }

    @Test
    void everyListedEntityChecksItsFallDistanceAndOnlyTheLaunchableOnesRise() {
        CompensatedWorld world = geyser("continuous", 2);
        Body falling = new Body(-0.4, 3.0), fastFalling = new Body(-0.6, 3.0), flying = new Body(0.0, 3.0), edge = new Body(-0.5, 3.0);
        flying.launchable = false;
        tick(world, falling, fastFalling, flying, edge);
        // checkFallDistanceAccumulation: only above -0.5
        assertEquals(3.0, edge.fallDistance);
        assertEquals(1.0, falling.fallDistance);
        assertEquals(-0.4 + 0.2F, falling.velocity.y());
        assertEquals(3.0, fastFalling.fallDistance);
        assertEquals(-0.6 + 0.2F, fastFalling.velocity.y());
        assertEquals(1.0, flying.fallDistance);
        assertEquals(0.0, flying.velocity.y());
    }

    @Test
    void onlyEruptingAndContinuousGeysersWithAShortWaterColumnLaunch() {
        for (String state : new String[]{"dry", "wet", "dormant"}) {
            assertEquals(List.of(), tick(geyser(state, 3), new Body(0.0, 0.0)), state);
        }
        // five water blocks: no source block within four above it
        assertEquals(List.of(), tick(geyser("erupting", 5), new Body(0.0, 0.0)));
        // a block that is neither water nor passable in the water column
        CompensatedWorld stairs = geyser("erupting", 3);
        stairs.setBlock(5, 62, 5, state("minecraft:oak_stairs", "facing=north,half=bottom,shape=straight,waterlogged=true"));
        assertEquals(List.of(), tick(stairs, new Body(0.0, 0.0)));
        // no water at all: the block above is the source, no water blocks and no force (the box reaches one block down)
        assertEquals(List.of(new Aabb(5, 60, 5, 6, 62, 6)), tick(geyser("erupting", 0)));
    }

    @Test
    void anObstructionCutsTheColumnAndAnUnknownShapeIsReported() {
        CompensatedWorld world = geyser("erupting", 3);
        world.setBlock(5, 66, 5, state("minecraft:stone", ""));
        assertEquals(List.of(new Aabb(5, 61, 5, 6, 66, 6)), tick(world));
        // a flower has no collision: passable
        CompensatedWorld flower = geyser("erupting", 3);
        flower.setBlock(5, 66, 5, state("minecraft:dandelion", ""));
        assertEquals(List.of(new Aabb(5, 61, 5, 6, 79, 6)), tick(flower));
        // positionContext(the geyser's y): scaffolding, lava and powder snow above it have no collision for it
        for (int block : new int[]{state("minecraft:scaffolding", "bottom=false,distance=0,waterlogged=false"),
                state("minecraft:scaffolding", "bottom=true,distance=3,waterlogged=false"), state("minecraft:lava", "level=0"),
                state("minecraft:powder_snow", "")}) {
            CompensatedWorld context = geyser("erupting", 3);
            context.setBlock(5, 66, 5, block);
            assertEquals(List.of(new Aabb(5, 61, 5, 6, 79, 6)), tick(context), REGISTRY.block(block).name());
            assertFalse(context.dynamicGaps().contains(Unmodeled.GEYSER));
        }
        // in the water column: waterlogged scaffolding counts as water, a lava source ends it as the source block
        CompensatedWorld lava = geyser("erupting", 3);
        lava.setBlock(5, 62, 5, state("minecraft:lava", "level=0"));
        assertEquals(List.of(new Aabb(5, 61, 5, 6, 67, 6)), tick(lava));
    }

    @Test
    void theTickerFollowsItsBlockAndChunk() {
        CompensatedWorld world = geyser("erupting", 3);
        world.setBlock(5, 60, 5, state("minecraft:stone", ""));
        assertEquals(List.of(), tick(world));
        world.setBlock(5, 60, 5, sulfur("erupting"));
        assertEquals(1, tick(world).size());
        // a state change keeps the ticker (rebinding), a copy keeps it too
        world.setBlock(5, 60, 5, sulfur("dormant"));
        world.setBlock(5, 60, 5, sulfur("continuous"));
        assertEquals(1, tick(world).size());
        assertEquals(1, tick(world.detachedCopy()).size());
        assertEquals(1, tick(world.fork()).size());
        // without its query the launch is not known
        world.tickBlockEntities(0L, null, null, area -> List.of(), null);
        assertTrue(world.dynamicGaps().contains(Unmodeled.GEYSER));

    }

    @Test
    void aChunksBlockEntitiesCreateTheTickers() {
        var model = new WorldModel(REGISTRY);
        model.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 32, 2)));
        // the lowest section is all erupting potent sulfur, the other one water; one block entity listed
        int sulfur = sulfur("erupting");
        model.sent(1, Wire.of(new ChunkData(0, 0, new int[]{0, 0}, new int[]{1, 1}, new int[]{sulfur, WATER}, new long[0],
                ChunkBlockEntities.encode(List.of(new BlockEntityData(5, 15, 5, "minecraft:potent_sulfur", new byte[]{0}))))));
        try (var hypothesis = model.hypothesis(new SyncWindow(1, 2), 1)) {
            CompensatedWorld world = hypothesis.world();
            // water 16..31 is too deep for a source: the top is opened
            for (int y = 20; y < 32; y++) world.setBlock(5, y, 5, CompensatedWorld.AIR);
            List<Aabb> areas = tick(world);
            assertEquals(List.of(new Aabb(5, 16, 5, 6, 40, 6)), areas, "only the listed block entity ticks");
        }
    }
}
