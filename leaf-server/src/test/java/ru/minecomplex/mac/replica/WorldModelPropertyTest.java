package ru.minecomplex.mac.replica;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ru.minecomplex.mac.fixture.outbound.BlockUpdate;
import ru.minecomplex.mac.fixture.outbound.ChunkCacheCenter;
import ru.minecomplex.mac.fixture.outbound.ChunkCacheRadius;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.ForgetChunk;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.Respawn;
import ru.minecomplex.mac.fixture.outbound.SectionBlocksUpdate;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.ChunkColumn;
import ru.minecomplex.mac.world.PalettedSection;

/**
 * Every admissible prefix view equals the world rebuilt from scratch (an independent, fork-free applier), and
 * building views never disturbs the acknowledged world.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class WorldModelPropertyTest {
    private static final int SECTIONS = 4;
    private static final int MIN_Y = 0;

    private record Sent(long seq, OutboundEvent event) {
    }

    private static ChunkData randomChunk(Random random, int x, int z) {
        int[] bits = new int[SECTIONS];
        int[] sizes = new int[SECTIONS];
        List<Integer> palette = new ArrayList<>();
        List<Long> data = new ArrayList<>();
        for (int i = 0; i < SECTIONS; i++) {
            if (random.nextBoolean()) {
                bits[i] = 0;
                sizes[i] = 1;
                palette.add(random.nextInt(8));
            } else {
                bits[i] = 4;
                sizes[i] = 16;
                for (int p = 0; p < 16; p++) {
                    palette.add(random.nextInt(50));
                }
                for (int l = 0; l < 256; l++) {
                    data.add(random.nextLong());
                }
            }
        }
        return new ChunkData(x, z, bits, sizes, palette.stream().mapToInt(Integer::intValue).toArray(),
                data.stream().mapToLong(Long::longValue).toArray());
    }

    private static OutboundEvent randomChange(Random random) {
        int cx = random.nextInt(9) - 4;
        int cz = random.nextInt(9) - 4;
        return switch (random.nextInt(20)) {
            case 0 -> new JoinGame(1, "minecraft:overworld", MIN_Y, SECTIONS * 16, random.nextInt(4));
            case 1 -> new Respawn(random.nextBoolean() ? "minecraft:overworld" : "minecraft:the_nether", 0, MIN_Y, SECTIONS * 16);
            case 2 -> new ChunkCacheCenter(random.nextInt(5) - 2, random.nextInt(5) - 2);
            case 3 -> new ChunkCacheRadius(random.nextInt(4));
            case 4, 5, 6, 7 -> randomChunk(random, cx, cz);
            case 8 -> new ForgetChunk(cx, cz);
            case 9 -> {
                long[] entries = new long[1 + random.nextInt(6)];
                for (int i = 0; i < entries.length; i++) {
                    entries[i] = ((long) random.nextInt(60) << 12) | random.nextInt(4096);
                }
                yield new SectionBlocksUpdate(cx, random.nextInt(SECTIONS), cz, entries);
            }
            default -> new BlockUpdate(cx * 16 + random.nextInt(16), random.nextInt(SECTIONS * 16), cz * 16 + random.nextInt(16), random.nextInt(60));
        };
    }

    /** Fork-free reference: a fresh world with every change applied in order. */
    private static CompensatedWorld rebuild(List<Sent> sent, long through) {
        CompensatedWorld world = null;
        for (Sent s : sent) {
            if (s.seq() > through) {
                break;
            }
            switch (s.event()) {
                case JoinGame join -> world = new CompensatedWorld(join.dimension(), join.minY(), join.height(), join.chunkRadius());
                case Respawn respawn -> {
                    if (world != null && !respawn.dimension().equals(world.dimension())) {
                        world = new CompensatedWorld(respawn.dimension(), respawn.minY(), respawn.height(), world.serverRadius());
                    }
                }
                case ChunkCacheCenter center -> {
                    if (world != null) world.setCenter(center.x(), center.z());
                }
                case ChunkCacheRadius radius -> {
                    if (world != null) world.setRadius(radius.radius());
                }
                case ChunkData chunk -> {
                    if (world != null) world.load(copyOf(PROTOTYPES.computeIfAbsent(chunk, WorldModelPropertyTest::decodeIndependently)));
                }
                case ForgetChunk forget -> {
                    if (world != null) world.forget(forget.x(), forget.z());
                }
                case BlockUpdate update -> {
                    if (world != null) world.setBlock(update.x(), update.y(), update.z(), update.state());
                }
                case SectionBlocksUpdate update -> {
                    if (world != null) {
                        for (long e : update.entries()) {
                            world.setBlock((update.sectionX() << 4) + SectionBlocksUpdate.localX(e), (update.sectionY() << 4) + SectionBlocksUpdate.localY(e),
                                    (update.sectionZ() << 4) + SectionBlocksUpdate.localZ(e), SectionBlocksUpdate.state(e));
                        }
                    }
                }
                default -> throw new IllegalStateException();
            }
        }
        return world;
    }

    private static final java.util.Map<ChunkData, ChunkColumn> PROTOTYPES = new java.util.IdentityHashMap<>();

    private static ChunkColumn copyOf(ChunkColumn prototype) {
        PalettedSection[] sections = new PalettedSection[prototype.sectionCount()];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = prototype.section(i).copy();
        }
        return new ChunkColumn(prototype.x(), prototype.z(), sections);
    }

    /** Unpacks every entry by hand (no {@code PalettedSection.of}), then writes them one by one. */
    private static ChunkColumn decodeIndependently(ChunkData chunk) {
        PalettedSection[] sections = new PalettedSection[chunk.sectionCount()];
        int paletteOffset = 0;
        int dataOffset = 0;
        for (int i = 0; i < sections.length; i++) {
            int bits = chunk.sectionBits()[i];
            int size = chunk.paletteSizes()[i];
            PalettedSection section = PalettedSection.single(chunk.palette()[paletteOffset], 16);
            if (bits == 4) {
                for (int index = 0; index < PalettedSection.SIZE; index++) {
                    long word = chunk.data()[dataOffset + index / 16];
                    int id = (int) (word >>> ((index % 16) * 4) & 15);
                    section.set(index, chunk.palette()[paletteOffset + id]);
                }
                dataOffset += 256;
            }
            paletteOffset += size;
            sections[i] = section;
        }
        return new ChunkColumn(chunk.x(), chunk.z(), sections);
    }

    /** Positions touched by block updates: where a copy-on-write mistake would show. */
    private static List<int[]> touched(List<Sent> sent) {
        List<int[]> positions = new ArrayList<>();
        for (Sent s : sent) {
            if (s.event() instanceof BlockUpdate update) {
                positions.add(new int[]{update.x(), update.y(), update.z()});
            } else if (s.event() instanceof SectionBlocksUpdate update) {
                for (long e : update.entries()) {
                    positions.add(new int[]{(update.sectionX() << 4) + SectionBlocksUpdate.localX(e),
                            (update.sectionY() << 4) + SectionBlocksUpdate.localY(e), (update.sectionZ() << 4) + SectionBlocksUpdate.localZ(e)});
                }
            }
        }
        return positions;
    }

    private static void assertSameWorld(CompensatedWorld expected, CompensatedWorld actual, Random random, List<int[]> touched, String context) {
        if (expected == null || actual == null) {
            assertEquals(expected == null, actual == null, context + ": presence");
            return;
        }
        assertEquals(expected.dimension(), actual.dimension(), context);
        for (int cx = -6; cx <= 6; cx++) {
            for (int cz = -6; cz <= 6; cz++) {
                assertEquals(expected.isLoaded(cx, cz), actual.isLoaded(cx, cz), context + ": chunk " + cx + "," + cz);
            }
        }
        for (int i = 0; i < 100; i++) {
            int x = random.nextInt(144) - 72;
            int y = random.nextInt(SECTIONS * 16 + 8) - 4;
            int z = random.nextInt(144) - 72;
            assertEquals(expected.getBlock(x, y, z), actual.getBlock(x, y, z), context + ": block " + x + "," + y + "," + z);
        }
        for (int[] p : touched) {
            assertEquals(expected.getBlock(p[0], p[1], p[2]), actual.getBlock(p[0], p[1], p[2]), context + ": touched " + p[0] + "," + p[1] + "," + p[2]);
        }
    }

    @Test
    void viewsEqualRebuiltWorlds() {
        int views = 0;
        for (long seed = 0; seed < 40; seed++) {
            Random random = new Random(seed);
            WorldModel model = new WorldModel(BlockRegistry.get());
            List<Sent> sent = new ArrayList<>();
            List<Long> markers = new ArrayList<>();
            long seq = 0;
            sent.add(new Sent(seq, new JoinGame(1, "minecraft:overworld", MIN_Y, SECTIONS * 16, 3)));
            model.sent(seq++, Wire.of(sent.getFirst().event()));
            int acknowledged = 0;
            for (int step = 0; step < 300; step++) {
                if (random.nextInt(5) == 0) {
                    markers.add(seq++);
                } else {
                    OutboundEvent change = randomChange(random);
                    sent.add(new Sent(seq, change));
                    model.sent(seq++, Wire.of(change));
                }
                if (random.nextInt(8) == 0 && acknowledged < markers.size()) {
                    acknowledged += 1 + random.nextInt(markers.size() - acknowledged);
                    long certain = markers.get(acknowledged - 1);
                    long possibleBefore = acknowledged < markers.size() ? markers.get(acknowledged) : seq;
                    SyncWindow window = new SyncWindow(certain, possibleBefore);
                    List<int[]> touched = touched(sent);
                    for (long end : model.prefixEnds(window)) {
                        assertSameWorld(rebuild(sent, end), model.viewAt(window, end), random, touched, "seed " + seed + " prefix " + end);
                        views++;
                    }
                    assertSameWorld(rebuild(sent, certain), model.acknowledged(window), random, touched, "seed " + seed + " acknowledged");
                }
            }
        }
        PROTOTYPES.clear();
        assertTrue(views > 1_000, "enough views checked: " + views);
    }
}
