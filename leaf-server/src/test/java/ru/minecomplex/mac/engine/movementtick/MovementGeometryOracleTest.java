package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Whole real LocalPlayer ticks against explicitly received partial-block states. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class MovementGeometryOracleTest {

    @Test
    void everyMaskOnPartialBlocks() throws Exception {
        var trace = LocalTickOracle.read(false, "movement-geometry");
        var masks = new HashMap<String, Set<Integer>>();
        var yaw = new HashSet<Integer>();
        var pitch = new HashSet<Integer>();
        var contacts = new HashSet<String>();
        var supports = new HashSet<String>();
        for (var scenario : trace.scenarios()) {
            String family = scenario.family().split("/")[1];
            for (var tick : scenario.ticks()) {
                masks.computeIfAbsent(family, ignored -> new HashSet<>()).add(tick.rawKeys());
                yaw.add(Float.floatToIntBits(tick.yaw()));
                pitch.add(Float.floatToIntBits(tick.pitch()));
                var after = tick.after().base().player();
                if (after.horizontalCollision()) contacts.add(family);
                if (after.onGround() && after.y() > 64.0) supports.add(family);
            }
        }
        assertEquals(Set.of("FENCE", "WALL", "GATE_CLOSED", "GATE_OPEN", "DOOR_CLOSED", "DOOR_OPEN",
                "TRAPDOOR_CLOSED", "TRAPDOOR_OPEN", "CARPET", "SNOW_1", "SNOW_4", "SNOW_8",
                "FARMLAND", "PATH", "BED", "CHEST"), masks.keySet());
        masks.forEach((family, inputs) -> assertEquals(128, inputs.size(), family));
        assertEquals(12, yaw.size());
        assertEquals(6, pitch.size());
        assertEquals(1024, trace.scenarios().size());
        assertTrue(contacts.containsAll(Set.of("FENCE", "WALL", "GATE_CLOSED", "DOOR_CLOSED", "DOOR_OPEN",
                "TRAPDOOR_OPEN", "CHEST")), "Actual horizontal contacts: " + contacts);
        assertTrue(supports.containsAll(Set.of("CARPET", "SNOW_4", "TRAPDOOR_CLOSED")),
                "Actual partial-block support: " + supports);
        assertFalse(contacts.contains("GATE_OPEN"), "An open gate must not become a wall");
        LocalPlayerPhysicsOracleTest.verifyTrace(trace, 16384);
    }
}
