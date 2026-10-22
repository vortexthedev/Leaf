package ru.minecomplex.mac.entity;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import java.io.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PassengerOracleTest {

    @Test
    void matchesRealEntityRelations() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/passengers.bin.gz"); assertNotNull(raw);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d415047, in.readInt()); assertEquals(2, in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(64, in.readInt());
            for (int scenario = 0; scenario < 64; scenario++) {
                var graph = new PassengerGraph(); for (int id = 1; id <= 8; id++) { graph.spawn(id, id % 2 == 0); graph.pose(id, Pose.CROUCHING); }
                assertEquals(128, in.readInt());
                for (int step = 0; step < 128; step++) {
                    int action = in.readInt(), id = in.readInt(), vehicle = in.readInt(); boolean accepts = in.readBoolean();
                    int[] ids = new int[in.readInt()]; for (int i = 0; i < ids.length; i++) ids[i] = in.readInt();
                    boolean mounted = false;
                    switch (action) {
                        case 0 -> mounted = graph.startRiding(id, vehicle, accepts);
                        case 1 -> graph.stopRiding(id);
                        case 2 -> graph.apply(Wire.of(new EntityPassengers(vehicle, ids)), ignored -> accepts);
                        case 3 -> graph.remove(id);
                        case 4 -> graph.spawn(id, id % 2 == 0);
                        default -> throw new AssertionError(action);
                    }
                    assertEquals(in.readBoolean(), mounted, "26.3 mount result " + scenario + ":" + step);
                    for (int entityId = 1; entityId <= 8; entityId++) {
                        var entity = graph.get(entityId); String context = "26.3 relations " + scenario + ":" + step + ":" + entityId;
                        boolean exists = in.readBoolean(); assertEquals(exists, entity != null, context); if (!exists) continue;
                        assertEquals(in.readInt(), entity.vehicle() == null ? -1 : entity.vehicle(), context);
                        assertEquals(in.readInt(), entity.passengers().size(), context);
                        for (int rider : entity.passengers()) assertEquals(in.readInt(), rider, context);
                        assertEquals(in.readUTF(), entity.pose().name(), context); assertEquals(in.readInt(), entity.boardingCooldown(), context);
                        assertEquals(in.readInt(), graph.root(entityId), context);
                    }
                    assertTrue(graph.unmodeled().isEmpty());
                }
            }
            assertEquals(-1, in.read());
        }
    }
}
