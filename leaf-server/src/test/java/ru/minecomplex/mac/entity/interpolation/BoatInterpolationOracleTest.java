package ru.minecomplex.mac.entity.interpolation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.engine.movementtick.BoatEnvironmentOracleTest;

import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
public class BoatInterpolationOracleTest {

    public static Vec3d vector(DataInputStream in)throws IOException { return new Vec3d(in.readDouble(),in.readDouble(),in.readDouble()); }
    static void initial(DataInputStream in,PlayerState body)throws IOException { body.position = vector(in); body.yRot = in.readFloat(); body.xRot = in.readFloat(); }
    static void matches(DataInputStream in,PlayerState body,String context)throws IOException {
        var expected = vector(in); FluidContactOracleTest.bits(expected.x(),body.x(),context + " x");
        FluidContactOracleTest.bits(expected.y(),body.y(),context + " y"); FluidContactOracleTest.bits(expected.z(),body.z(),context + " z");
        BoatEnvironmentOracleTest.bits(in.readFloat(),body.yRot,context + " yaw"); BoatEnvironmentOracleTest.bits(in.readFloat(),body.xRot,context + " pitch");
    }
    @Test
    void matchesActualLinearBoatHandler()throws Exception {
        String path = "/vanilla/777/boat-interpolation.bin.gz";
        var raw = getClass().getResourceAsStream(path); assertNotNull(raw,path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414249,in.readInt()); assertEquals(1,in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt()); assertEquals(128,in.readInt());
            float width = in.readFloat(), height = in.readFloat();
            for (int s = 0; s < 128; s++) {
                var body = new PlayerState(); body.fixedBodyDimensions = true; body.bodyWidth = width; body.bodyHeight = height;
                initial(in,body); var interpolation = new BoatInterpolation(); assertEquals(32,in.readInt());
                for (int t = 0; t < 32; t++) {
                    boolean request = in.readBoolean(), cancel = in.readBoolean(); var target = vector(in); float yaw = in.readFloat(), pitch = in.readFloat();
                    if (request) interpolation.receive(body,target,yaw,pitch);
                    if (cancel) interpolation.cancel();
                    body.position = body.position.add(vector(in)); body.yRot += in.readFloat(); body.xRot += in.readFloat();
                    boolean free = in.readBoolean(); var boxes = new ArrayList<Aabb>();
                    interpolation.tick(body,box -> { boxes.add(box); return free; });
                    String context = "26.3 scenario=" + s + " tick=" + t; matches(in,body,context);
                    assertEquals(in.readBoolean(),interpolation.active(),context + " active"); assertEquals(in.readInt(),boxes.size(),context + " queries");
                    for (var box : boxes) {
                        double[] actual = {box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()};
                        for (double value : actual) FluidContactOracleTest.bits(in.readDouble(),value,context + " query box");
                    }
                }
            }
            assertEquals(-1,in.read());
        }
    }
}
