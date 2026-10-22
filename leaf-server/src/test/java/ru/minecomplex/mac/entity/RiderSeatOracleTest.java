package ru.minecomplex.mac.entity;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkCacheCenter;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EntityAttributes;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;

/**
 * MARS ({@code tools/datagen/.../RiderSeatSamples.java}): real vanilla boats, rafts and happy ghasts place real mobs and
 * players ({@code Entity.positionRider}); the model, fed the same spawns, scales and passengers on the wire, places them
 * in the vehicle's tick ({@code tickPassenger} → {@code rideTick}) at the same positions, bit for bit.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class RiderSeatOracleTest {
    private static final int ME = 1;

    @Test
    void ridersSitWhereVanillaPutsThem() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/" + ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL + "/rider-seats.bin.gz");
        assertNotNull(raw, "Missing /vanilla/" + ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL + "/rider-seats.bin.gz (tools/datagen/run_rider_seats.py)");
        BlockRegistry registry = BlockRegistry.get();
        int compared = 0;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d415253, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int samples = in.readInt();
            for (int sample = 0; sample < samples; sample++) {
                String vehicleType = in.readUTF();
                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                int yawByte = in.readInt();
                double vehicleScale = in.readDouble();
                int count = in.readInt();
                List<String> riders = new ArrayList<>();
                List<Double> scales = new ArrayList<>();
                List<Vec3d> expected = new ArrayList<>();
                for (int r = 0; r < count; r++) {
                    riders.add(in.readUTF());
                    scales.add(in.readDouble());
                    expected.add(new Vec3d(in.readDouble(), in.readDouble(), in.readDouble()));
                }
                var model = new WorldModel(registry);
                List<OutboundEvent> events = new ArrayList<>();
                events.add(new JoinGame(ME, "minecraft:overworld", -64, 384, 8));
                int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
                events.add(new ChunkCacheCenter(cx, cz));
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        events.add(new ChunkData(cx + dx, cz + dz, new int[24], ones(24), new int[24], new long[0],
                                ChunkBlockEntities.encode(List.of())));
                    }
                }
                float yaw = yawByte * 360 / 256.0F;
                events.add(new EntitySpawn(100, vehicleType, x, y, z, yaw, 0.0F, yaw, 0, false, 0, 0, 0, 1, 100));
                if (vehicleScale != 1.0) events.add(scale(100, vehicleScale));
                int[] ids = new int[count];
                for (int r = 0; r < count; r++) {
                    ids[r] = 101 + r;
                    events.add(new EntitySpawn(ids[r], riders.get(r), x + 1, y + 1, z + 1, 0.0F, 0.0F, 0.0F, 0, false, 0, 0, 0, 1, ids[r]));
                    if (scales.get(r) != 1.0) events.add(scale(ids[r], scales.get(r)));
                }
                events.add(new EntityPassengers(100, ids));
                for (int i = 0; i < events.size(); i++) model.sent(i, Wire.of(events.get(i)));
                long last = events.size() - 1;
                try (var hypothesis = model.hypothesis(new SyncWindow(last, last + 1), last)) {
                    var world = hypothesis.world();
                    world.tickEntities(true, (id, body, self, gaps) -> box -> true);
                    world.tickEntities(false, (id, body, self, gaps) -> box -> true);
                    for (int r = 0; r < count; r++) {
                        String context = "sample " + sample + " " + vehicleType + " " + riders + " rider " + r;
                        Vec3d seat = world.entities().motions().position(ids[r]);
                        if (DIAGNOSE) {
                            String key = vehicleType + " " + riders.get(r) + (count > 1 ? " i" + r + "/" + count : "") + (scales.get(r) != 1.0 ? " scaled" : "");
                            String delta = seat == null ? "unplaced" : String.format("d=(%.6f %.6f %.6f)", seat.x() - expected.get(r).x(), seat.y() - expected.get(r).y(), seat.z() - expected.get(r).z());
                            if (seat == null || !seat.equals(expected.get(r))) mismatches.merge(key + " " + delta, 1, Integer::sum);
                            else matches.merge(key, 1, Integer::sum);
                            continue;
                        }
                        assertNotNull(seat, context + ": not placed");
                        assertEquals(Double.doubleToLongBits(expected.get(r).x()), Double.doubleToLongBits(seat.x()), context + " x " + seat + " vs " + expected.get(r));
                        assertEquals(Double.doubleToLongBits(expected.get(r).y()), Double.doubleToLongBits(seat.y()), context + " y " + seat + " vs " + expected.get(r));
                        assertEquals(Double.doubleToLongBits(expected.get(r).z()), Double.doubleToLongBits(seat.z()), context + " z " + seat + " vs " + expected.get(r));
                        compared++;
                    }
                }
            }
        }
        if (DIAGNOSE) {
            mismatches.forEach((k, n) -> System.out.println("MISMATCH " + n + " " + k));
            System.out.println("MATCHED " + matches);
            return;
        }
        assertTrue(compared > 0);
    }

    private static final boolean DIAGNOSE = Boolean.getBoolean("mac.diagnose");
    private final java.util.Map<String, Integer> mismatches = new java.util.TreeMap<>(), matches = new java.util.TreeMap<>();

    private static int[] ones(int n) {
        int[] values = new int[n];
        java.util.Arrays.fill(values, 1);
        return values;
    }

    private static EntityAttributes scale(int id, double value) {
        return EntityAttributes.of(id, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:scale", value, List.of())));
    }
}
