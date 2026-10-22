package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import java.io.*;
import java.util.List;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import static org.junit.jupiter.api.Assertions.*;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ShulkerLifetimeOracleTest {

    static ChunkData chunk(int state) { return new ChunkData(0, 0, new int[]{0, 0, 0, 0, 0}, new int[]{1, 1, 1, 1, 1}, new int[]{0, 0, 0, 0, state}, new long[0]); }
    @Test
    void matchesVanilla() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/shulker-lifetime.bin.gz"); assertNotNull(raw);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d41484c, in.readInt()); assertEquals(2, in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int state = in.readInt(); assertEquals(96, in.readInt());
            for (int scenario = 0; scenario < 96; scenario++) {
                var model = new WorldModel(BlockRegistry.get());
                model.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 80, 2))); model.sent(1, Wire.of(chunk(state)));
                try (var hypothesis = model.hypothesis(new SyncWindow(1, 2), 1)) {
                    CompensatedWorld world = hypothesis.world(); var pos = new BlockPosition(0, 64, 0); assertEquals(80, in.readInt());
                    for (int step = 0; step < 80; step++) {
                        int action = in.readInt(), parameter = in.readInt();
                        switch (action) {
                            case 0 -> world.load(new ChunkColumn(0, 0, new PalettedSection[]{null, null, null, null, PalettedSection.single(state, 32 - Integer.numberOfLeadingZeros(BlockRegistry.get().stateCount() - 1))}));
                            // block entity data (standalone or a chunk entry) gets or creates the block entity
                            case 1, 2 -> world.shulker(pos, true);
                            case 3 -> world.shulkerEvent(new BlockEvent(0, 64, 0, 1, parameter, 0));
                            case 4 -> world.tickBlockEntities(step, null, null, area -> List.of());
                            case 5 -> world.setBlock(0, 64, 0, 0);
                            case 6 -> world.setBlock(0, 64, 0, state);
                            default -> throw new AssertionError(action);
                        }
                        var lid = world.shulker(pos, false); boolean exists = in.readBoolean();
                        assertEquals(exists, lid != null, "26.3 existence " + scenario + ":" + step);
                        if (exists) {
                            String context = "26.3 scenario " + scenario + " step " + step;
                            assertEquals(in.readInt(), lid.openCount(), context); assertEquals(in.readUTF(), lid.status().name(), context);
                            assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(lid.progress()), context);
                            assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(lid.progressOld()), context);
                        }
                        assertFalse(world.dynamicGaps().contains(ru.minecomplex.mac.engine.Unmodeled.SHULKER_TICK_TIMING));
                    }
                }
            }
            assertEquals(2097061, in.readInt(), "26.3 ChunkPos bounds margin");
            for (int n = in.readInt(); n > 0; n--) {
                int coordinate = in.readInt(); boolean exists = in.readBoolean();
                var world = new CompensatedWorld("minecraft:overworld", 0, 80, 2, BlockRegistry.get());
                world.setCenter(coordinate >> 4, coordinate >> 4);
                var sections = new PalettedSection[5]; sections[4] = PalettedSection.single(state, 16);
                world.load(new ChunkColumn(coordinate >> 4, coordinate >> 4, sections));
                assertEquals(exists, world.shulker(new BlockPosition(coordinate, 64, coordinate), true) != null, "26.3 bound " + coordinate);
            }
            assertEquals(-1, in.read());
        }
    }
}
