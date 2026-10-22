package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Named combinatorial scenarios are real vanilla traces; coverage is asserted rather than inferred from a seed. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
public class MovementMatrixOracleTest {

    @Test
    void allInputMasksAcrossSurfacesAndBoundaries() throws Exception {
        var trace = LocalTickOracle.read(false, "movement-matrix");
        var masks = new HashMap<String, Set<Integer>>();
        var yaw = new HashSet<Integer>(); var pitch = new HashSet<Integer>();
        for (var scenario : trace.scenarios()) {
            String family = scenario.family().split("/")[1];
            for (var tick : scenario.ticks()) {
                masks.computeIfAbsent(family, ignored -> new HashSet<>()).add(tick.rawKeys());
                yaw.add(Float.floatToIntBits(tick.yaw())); pitch.add(Float.floatToIntBits(tick.pitch()));
            }
        }
        assertEquals(16, masks.size());
        masks.forEach((family, inputs) -> assertEquals(128, inputs.size(), family));
        assertEquals(12, yaw.size()); assertEquals(6, pitch.size());
        assertTrue(yaw.contains(Float.floatToIntBits(-0F))); assertTrue(yaw.contains(Float.floatToIntBits(0F)));
        assertEquals(1024, trace.scenarios().size());
        LocalPlayerPhysicsOracleTest.verifyTrace(trace, 16384);
    }
}
