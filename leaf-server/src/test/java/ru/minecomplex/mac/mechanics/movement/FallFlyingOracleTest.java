package ru.minecomplex.mac.mechanics.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class FallFlyingOracleTest {

    @Test
    void movement() throws IOException {
        String path = "/vanilla/777/fall-flying.bin.gz";
        String local = System.getProperty("mac.fallFlying.input");
        var raw = local == null ? getClass().getResourceAsStream(path)
                : java.nio.file.Files.newInputStream(java.nio.file.Path.of(local));
        assertNotNull(raw, path);
        var port = new FallFlyingMovement();
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414346, in.readInt()); assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); int count = in.readInt(); assertEquals(8192, count);
            for (int i = 0; i < count; i++) {
                var movement = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                float yaw = in.readFloat(), pitch = in.readFloat(); double gravity = in.readDouble();
                Vec3d actual = port.update(movement, yaw, pitch, gravity);
                for (double value : new double[]{actual.x(), actual.y(), actual.z()})
                    assertEquals(Double.doubleToLongBits(in.readDouble()), Double.doubleToLongBits(value), "26.3 sample " + i);
            }
            assertEquals(-1, in.read());
        }
    }
}
