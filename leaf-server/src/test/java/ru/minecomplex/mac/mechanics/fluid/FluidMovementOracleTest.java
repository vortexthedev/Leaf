package ru.minecomplex.mac.mechanics.fluid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

import ru.minecomplex.mac.util.math.Vec3d;

/** Arithmetic only: these fixtures do not establish fluid-contact or full-tick travel coverage. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class FluidMovementOracleTest {

    @Test
    void matchesVanillaArithmetic() throws IOException {
        String path = "/vanilla/777/fluid-movement.bin.gz";
        var raw = getClass().getResourceAsStream(path); assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414355, in.readInt()); assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(8192, in.readInt());
            for (int i = 0; i < 8192; i++) {
                Vec3d input = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                double gravity = in.readDouble(); boolean falling = in.readBoolean(), sprinting = in.readBoolean();
                compare(in, FluidMovement.descend(input), "26.3 descend " + i);
                compare(in, FluidMovement.jump(input), "26.3 jump " + i);
                compare(in, FluidMovement.fallingAdjusted(input, gravity, falling, sprinting), "26.3 falling " + i);
            }
            assertEquals(-1, in.read());
        }
    }
    private static void compare(DataInputStream in, Vec3d actual, String context) throws IOException {
        for (double d : new double[]{actual.x(), actual.y(), actual.z()})
            assertEquals(Double.doubleToLongBits(in.readDouble()), Double.doubleToLongBits(d), context);
    }
}
