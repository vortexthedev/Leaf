package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.replica.CompensatedWorld;

/**
 * {@code Level.precipitationAt} apart from {@code isRaining}: the block column ({@code canSeeSky}, the
 * {@code MOTION_BLOCKING} heightmap; {@code ../Client/src/main/java/net/minecraft/.../Level.java}, {@code ChunkSkyLightSources},
 * {@code Heightmap}) with the client's light timing ({@code ClientLevel.pollLightUpdates}, {@code Minecraft.runTick}),
 * and the biome ({@code Biome.getPrecipitationAt}, {@code BiomeManager.getBiome}).
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class RainAtPositionTest {
    private static final int SEA = 63;
    private static final BlockTags TAGS=(tag,block)->net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,net.minecraft.resources.Identifier.parse(block.name())))
            .is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,net.minecraft.resources.Identifier.parse(tag.key())));

    private static CompensatedWorld world() {
        BlockRegistry registry = BlockRegistry.get();
        int bits = 32 - Integer.numberOfLeadingZeros(registry.stateCount() - 1);
        CompensatedWorld world = new CompensatedWorld("minecraft:overworld", -64, 384, 8, registry);
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                PalettedSection[] sections = new PalettedSection[24];
                for (int i = 0; i < sections.length; i++) sections[i] = PalettedSection.single(CompensatedWorld.AIR, bits);
                world.load(new ChunkColumn(cx, cz, sections));
            }
        }
        return world;
    }

    private static void ticks(CompensatedWorld world, int count) {
        for (int i = 0; i < count; i++) world.tickLight();
    }

    private static int state(String block, String properties) {
        int id = BlockRegistry.get().stateId(block, properties);
        if (id < 0) throw new IllegalArgumentException(block);
        return id;
    }

    @Test
    void anOpenColumnSeesTheSkyOnceItsLightDataSurelyRan() {
        CompensatedWorld world = world();
        world.queueLight(0, 0);
        // the first entry of the queue runs in the first frame, which follows at most 10 ticks
        for (int i = 0; i < CompensatedWorld.FRAME_TICKS; i++) {
            assertEquals(Tri.EITHER, world.rainColumn(5, 70, 5, TAGS), "tick " + i);
            world.tickLight();
        }
        assertEquals(Tri.TRUE, world.rainColumn(5, 70, 5, TAGS));
        // another column of the chunk waited for the same light data
        assertEquals(Tri.TRUE, world.rainColumn(0, -64, 15, TAGS));
        // above the build height and below the world (the heightmap is never below minY)
        assertEquals(Tri.TRUE, world.rainColumn(5, 400, 5, TAGS));
        assertEquals(Tri.FALSE, world.rainColumn(5, -65, 5, TAGS));
        // a column the client has not loaded
        assertEquals(Tri.EITHER, world.rainColumn(40, 70, 5, TAGS));
    }

    @Test
    void theQueueRunsAtLeastTenEntriesAFrameAndEverythingFromAThousand() {
        CompensatedWorld world = world();
        for (int i = 0; i < 25; i++) world.queueLight(1, 1);
        world.queueLight(0, 0);
        // the 26th entry: three frames
        ticks(world, 29);
        assertEquals(Tri.EITHER, world.rainColumn(5, 70, 5, TAGS));
        ticks(world, 1);
        assertEquals(Tri.TRUE, world.rainColumn(5, 70, 5, TAGS));

        CompensatedWorld burst = world();
        for (int i = 0; i < 999; i++) burst.queueLight(1, 1);
        burst.queueLight(0, 0);
        // a queue of 1000 is polled whole
        ticks(burst, 9);
        assertEquals(Tri.EITHER, burst.rainColumn(5, 70, 5, TAGS));
        ticks(burst, 1);
        assertEquals(Tri.TRUE, burst.rainColumn(5, 70, 5, TAGS));
    }

    @Test
    void aMotionBlockingBlockOrFluidAboveStopsTheRainAtOnceAndARemovedOneIsRelitByTheNextFrame() {
        CompensatedWorld world = world();
        world.setBlock(5, 100, 5, state("minecraft:stone", ""));
        assertEquals(Tri.FALSE, world.rainColumn(5, 70, 5, TAGS));
        // the heightmap counts the block's own position, not below it
        assertEquals(Tri.FALSE, world.rainColumn(5, 100, 5, TAGS));
        world.setBlock(5, 100, 5, CompensatedWorld.AIR);
        ticks(world, CompensatedWorld.FRAME_TICKS - 1);
        assertEquals(Tri.EITHER, world.rainColumn(5, 70, 5, TAGS));
        world.tickLight();
        assertEquals(Tri.TRUE, world.rainColumn(5, 70, 5, TAGS));

        world.setBlock(6, 90, 6, state("minecraft:water", "level=0"));
        assertEquals(Tri.FALSE, world.rainColumn(6, 70, 6, TAGS));
        world.setBlock(7, 90, 7, state("minecraft:glass", ""));
        assertEquals(Tri.FALSE, world.rainColumn(7, 70, 7, TAGS));
        // a block that neither blocks motion nor is air: its light dampening and faces are not modelled
        world.setBlock(8, 90, 8, state("minecraft:torch", ""));
        ticks(world, CompensatedWorld.FRAME_TICKS);
        assertEquals(Tri.EITHER, world.rainColumn(8, 70, 8, TAGS));
        assertEquals(Tri.TRUE, world.rainColumn(8, 91, 8, TAGS));
    }

    @Test
    void from26_3TheHeightmapReadsTheBlockTag() {
        CompensatedWorld world = world();
        int stone = state("minecraft:stone", "");
        world.setBlock(5, 100, 5, stone);
        ticks(world, CompensatedWorld.FRAME_TICKS);
        assertEquals(Tri.EITHER, world.rainColumn(5, 70, 5, BlockTags.NONE));
        BlockTags tagged = (tag, block) -> tag == BlockTag.BLOCKS_MOTION_IN_HEIGHTMAP && block.name().equals("minecraft:stone");
        assertEquals(Tri.FALSE, world.rainColumn(5, 70, 5, tagged));
        world.setBlock(6, 100, 6, state("minecraft:water", "level=0"));
        assertEquals(Tri.FALSE, world.rainColumn(6, 70, 6, TAGS));
    }

    @Test
    void copiesKeepTheLightTiming() {
        CompensatedWorld world = world();
        world.queueLight(0, 0);
        CompensatedWorld copy = world.detachedCopy();
        ticks(copy, CompensatedWorld.FRAME_TICKS);
        assertEquals(Tri.TRUE, copy.rainColumn(5, 70, 5, TAGS));
        assertEquals(Tri.EITHER, world.rainColumn(5, 70, 5, TAGS));
        CompensatedWorld fork = world.fork();
        assertEquals(Tri.EITHER, fork.rainColumn(5, 70, 5, TAGS));
    }

    @Test
    void aBiomeRainsWhereItHasPrecipitationAndIsWarmEnough() {
        var warm = new Precipitation.Facts(true, 0.8F, false);
        assertEquals(Tri.TRUE, Precipitation.rain(warm, 300, SEA));
        assertEquals(Tri.FALSE, Precipitation.rain(new Precipitation.Facts(false, 0.8F, false), 70, SEA));
        // getTemperature >= 0.15F below the snow level (seaLevel + 17)
        assertEquals(Tri.TRUE, Precipitation.rain(new Precipitation.Facts(true, 0.15F, false), SEA + 17, SEA));
        assertEquals(Tri.FALSE, Precipitation.rain(new Precipitation.Facts(true, Math.nextDown(0.15F), false), SEA + 17, SEA));
        // above it the temperature falls by (v + y - snowLevel) * 0.05F / 40.0F, |v| < 22: decided outside the noise's reach
        var cold = new Precipitation.Facts(true, 0.2F, false);
        assertEquals(Tri.TRUE, Precipitation.rain(cold, SEA + 17 + 1, SEA));
        assertEquals(Tri.EITHER, Precipitation.rain(cold, SEA + 17 + 20, SEA));
        assertEquals(Tri.FALSE, Precipitation.rain(cold, SEA + 17 + 100, SEA));
        // 0.2F - (22 + 18) * 0.05F / 40.0F == 0.15F: the coldest noise still rains
        assertEquals(Tri.TRUE, Precipitation.rain(cold, SEA + 17 + 18, SEA));
        assertEquals(Tri.EITHER, Precipitation.rain(cold, SEA + 17 + 19, SEA));
        // FROZEN: 0.2F or the base temperature
        assertEquals(Tri.TRUE, Precipitation.rain(new Precipitation.Facts(true, 0.5F, true), 70, SEA));
        assertEquals(Tri.EITHER, Precipitation.rain(new Precipitation.Facts(true, 0.0F, true), 70, SEA));
    }

    @Test
    void theZoomedBiomeIsKnownWhereItsEightCandidatesAgree() {
        CompensatedWorld world = world();
        // quart x < 0 is a desert (no precipitation), the rest plains
        Precipitation.Source source = new Precipitation.Source() {
            @Override public Precipitation.Facts noiseBiome(String dimension, int quartX, int quartY, int quartZ) {
                return new Precipitation.Facts(quartX >= 0, 2.0F, false);
            }
            @Override public Integer seaLevel(String dimension) { return SEA; }
        };
        // x 6: cells (6 - 2) >> 2 = 1 and 2
        assertEquals(Tri.TRUE, Precipitation.rain(source, world, 6, 70, 6));
        // x 2: cells 0 and 1; x 1: cells -1 and 0
        assertEquals(Tri.TRUE, Precipitation.rain(source, world, 2, 70, 6));
        assertEquals(Tri.EITHER, Precipitation.rain(source, world, 1, 70, 6));
        assertEquals(Tri.FALSE, Precipitation.rain(source, world, -6, 70, 6));
        // a cell in a column the client has not loaded (plains there), a server that does not answer
        assertEquals(Tri.EITHER, Precipitation.rain(source, world, 6, 70, 30));
        assertEquals(Tri.EITHER, Precipitation.rain(Precipitation.Source.UNKNOWN, world, 6, 70, 6));
    }

    @Test
    void theNoiseBoundCoversTheSimplexRange() {
        // max over r of (0.5 - r^2)^4 * sqrt(2) * r, three corners, times 70 and 8
        double corner = 0.0;
        for (int i = 0; i <= 1_000_000; i++) {
            double r = Math.sqrt(0.5) * i / 1_000_000.0;
            corner = Math.max(corner, Math.pow(0.5 - r * r, 4) * Math.sqrt(2.0) * r);
        }
        double r = Math.sqrt(1.0 / 18.0);
        assertEquals(Math.pow(0.5 - r * r, 4) * Math.sqrt(2.0) * r, corner, 1e-9);
        assertEquals(true, 8.0 * 70.0 * 3.0 * corner < Precipitation.NOISE_BOUND);
    }

    private static final Precipitation.Source PLAINS = new Precipitation.Source() {
        @Override public Precipitation.Facts noiseBiome(String dimension, int quartX, int quartY, int quartZ) {
            return new Precipitation.Facts(true, 0.8F, false);
        }
        @Override public Integer seaLevel(String dimension) { return SEA; }
    };

    private static Tri inRain(CompensatedWorld world, java.util.List<ru.minecomplex.mac.fixture.outbound.OutboundEvent> pending, double y) {
        return Precipitation.inRain(world, Wire.ofOut(pending), TAGS, PLAINS, 5.5, y, 5.5, y + 1.8);
    }

    @Test
    void changesInFlightLeaveTheirColumnOrBiomesUndecided() {
        CompensatedWorld world = world();
        java.util.List<ru.minecomplex.mac.fixture.outbound.OutboundEvent> none = java.util.List.of();
        assertEquals(Tri.TRUE, inRain(world, none, 70.0));
        assertEquals(Tri.EITHER, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.BlockUpdate(5, 200, 5, 1)), 70.0));
        assertEquals(Tri.TRUE, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.BlockUpdate(6, 200, 5, 1)), 70.0));
        long here = 1L << 12 | 5L << 8 | 5L << 4 | 3L, there = 1L << 12 | 6L << 8 | 5L << 4 | 3L;
        assertEquals(Tri.EITHER, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.SectionBlocksUpdate(0, 6, 0, new long[]{there, here})), 70.0));
        assertEquals(Tri.TRUE, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.SectionBlocksUpdate(0, 6, 0, new long[]{there})), 70.0));
        assertEquals(Tri.TRUE, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.SectionBlocksUpdate(1, 6, 0, new long[]{here})), 70.0));
        assertEquals(Tri.EITHER, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.LightUpdate(0, 0)), 70.0));
        assertEquals(Tri.TRUE, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.LightUpdate(1, 0)), 70.0));
        assertEquals(Tri.EITHER, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.ForgetChunk(0, 0)), 70.0));
        // x 5: cells 0 and 1, both in chunk 0; a biome change of chunk 0 matters, of chunk 1 not
        assertEquals(Tri.EITHER, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.ChunkBiomes(new long[]{0L})), 70.0));
        assertEquals(Tri.TRUE, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.ChunkBiomes(new long[]{1L})), 70.0));
        assertEquals(Tri.EITHER, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.JoinGame(1, "minecraft:overworld", -64, 384, 8)), 70.0));
        assertEquals(Tri.TRUE, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.ChunkCacheCenter(3, 3)), 70.0));
        assertEquals(Tri.EITHER, inRain(world, java.util.List.of(new ru.minecomplex.mac.fixture.outbound.ChunkCacheCenter(12, 0)), 70.0));
        assertEquals(Tri.EITHER, inRain(world, java.util.List.of(
                new ru.minecomplex.mac.fixture.outbound.ChunkCacheCenter(12, 0),
                new ru.minecomplex.mac.fixture.outbound.ChunkCacheCenter(0, 0)), 70.0));
    }

    @Test
    void theRainAtTheTopOfTheBoxCountsWhereTheFeetAreCovered() {
        CompensatedWorld world = world();
        java.util.List<ru.minecomplex.mac.fixture.outbound.OutboundEvent> none = java.util.List.of();
        // a block at the feet stops the rain there, not at the top of the box (y 71.8 → 71) above it
        world.setBlock(5, 70, 5, state("minecraft:stone", ""));
        ticks(world, CompensatedWorld.FRAME_TICKS);
        assertEquals(Tri.TRUE, inRain(world, none, 70.0));
        // a block above both
        world.setBlock(5, 100, 5, state("minecraft:stone", ""));
        assertEquals(Tri.FALSE, inRain(world, none, 70.0));
        // a biome without rain
        CompensatedWorld open = world();
        Precipitation.Source desert = new Precipitation.Source() {
            @Override public Precipitation.Facts noiseBiome(String dimension, int quartX, int quartY, int quartZ) {
                return new Precipitation.Facts(false, 2.0F, false);
            }
            @Override public Integer seaLevel(String dimension) { return SEA; }
        };
        assertEquals(Tri.FALSE, Precipitation.inRain(open, Wire.ofOut(none), TAGS, desert, 5.5, 70.0, 5.5, 71.8));
        // rain at the feet decides even where the top of the box is in another biome (cells by height)
        Precipitation.Source layered = new Precipitation.Source() {
            @Override public Precipitation.Facts noiseBiome(String dimension, int quartX, int quartY, int quartZ) {
                return new Precipitation.Facts(quartY < 18, 0.8F, false);
            }
            @Override public Integer seaLevel(String dimension) { return SEA; }
        };
        assertEquals(Tri.TRUE, Precipitation.inRain(open, Wire.ofOut(none), TAGS, layered, 5.5, 64.0, 5.5, 90.0));
        assertEquals(Tri.FALSE, Precipitation.inRain(open, Wire.ofOut(none), TAGS, layered, 5.5, 90.0, 5.5, 91.8));
    }

    @Test
    void pendingLightCannotMakeRainReachThroughAResolvedRoof() {
        CompensatedWorld world = world();
        world.setBlock(5, 100, 5, state("minecraft:stone", ""));
        var light = java.util.List.<ru.minecomplex.mac.fixture.outbound.OutboundEvent>of(
                new ru.minecomplex.mac.fixture.outbound.LightUpdate(0, 0));
        assertEquals(Tri.FALSE, inRain(world, light, 70.0));
        // A block packet can remove that roof; its uncertainty must still be retained.
        assertEquals(Tri.EITHER, inRain(world, java.util.List.of(light.getFirst(),
                new ru.minecomplex.mac.fixture.outbound.BlockUpdate(5, 100, 5, 0)), 70.0));
        assertEquals(Tri.EITHER, inRain(world(), light, 70.0));
    }
}
