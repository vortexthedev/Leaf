package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.entity.interpolation.BoatInterpolationOracleTest;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BoatBubbleEventOracleTest {

    @Test
    void matchesActualBubbleHandler()throws Exception {
        String path = "/vanilla/777/boat-bubble-events.bin.gz";
        var raw = getClass().getResourceAsStream(path); assertNotNull(raw,path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d41424c,in.readInt()); assertEquals(1,in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt()); assertEquals(2048,in.readInt());
            var state = new BoatState(new Object(),1.375F,0.5625F,0.4F);
            for (int i = 0; i < 2048; i++) {
                int id = in.readUnsignedByte(); boolean simulate = in.readBoolean(); state.body.deltaMovement = BoatInterpolationOracleTest.vector(in);
                assertEquals(Set.of(),MovementTickerBoat.bubbleLaunchEvent(state,id,simulate ? Tri.TRUE : Tri.FALSE));
                var expected = BoatInterpolationOracleTest.vector(in); String context = "26.3 event=" + i;
                FluidContactOracleTest.bits(expected.x(),state.body.deltaMovement.x(),context + " vx");
                FluidContactOracleTest.bits(expected.y(),state.body.deltaMovement.y(),context + " vy");
                FluidContactOracleTest.bits(expected.z(),state.body.deltaMovement.z(),context + " vz"); assertEquals(0,state.tickCount,context);
            }
            assertEquals(-1,in.read());
        }
    }
}
