package ru.minecomplex.mac.listener;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import java.io.*;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.fixture.inbound.VehicleMovement;
import ru.minecomplex.mac.fixture.outbound.VehicleCorrection;
import ru.minecomplex.mac.util.Tri;
import static org.junit.jupiter.api.Assertions.*;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;

import ru.minecomplex.mac.engine.movementtick.VehiclePacketOracleTest;
import ru.minecomplex.mac.engine.movementtick.VehiclePacketState;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class VehicleCorrectionOracleTest {

    @Test
    void matchesRealClientHandler() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/vehicle-correction.bin.gz"); assertNotNull(raw);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d415643, in.readInt()); assertEquals(1, in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(2048, in.readInt());
            for (int i = 0; i < 2048; i++) {
                boolean authority = in.readBoolean(), interpolating = in.readBoolean();
                Vec3d position = VehiclePacketOracleTest.vector(in); float yaw = in.readFloat(), pitch = in.readFloat();
                var target = new VehiclePacketState.Target(VehiclePacketOracleTest.vector(in), in.readFloat(), in.readFloat()); boolean ground = in.readBoolean();
                var root = new VehiclePacketState(new Object(), authority ? Tri.TRUE : Tri.FALSE, position, yaw, pitch,
                        interpolating ? target : null, interpolating, ground);
                Vec3d corrected = VehiclePacketOracleTest.vector(in);
                var correction = new VehicleCorrection(corrected.x(), corrected.y(), corrected.z(), in.readFloat(), in.readFloat());
                var result = VehicleCorrectionHandler.apply(Wire.of(correction), true, root);
                Vec3d expectedPosition = VehiclePacketOracleTest.vector(in); float expectedYaw = in.readFloat(), expectedPitch = in.readFloat();
                var expected = new ArrayList<VehicleMovement>(); for (int n = in.readInt(); n > 0; n--) expected.add((VehicleMovement) LocalPlayerOracleCodec.packet(in));
                String context = "26.3 correction " + i;
                assertEquals(expectedPosition, result.root().position(), context); assertEquals(Float.floatToIntBits(expectedYaw), Float.floatToIntBits(result.root().yaw()), context);
                assertEquals(Float.floatToIntBits(expectedPitch), Float.floatToIntBits(result.root().pitch()), context);
                assertEquals(expected, Wire.in(result.replies()), context); assertTrue(result.unmodeled().isEmpty(), context);
                assertSame(root.generation(), result.root().generation());
            }
            assertEquals(-1, in.read());
        }
    }
}
