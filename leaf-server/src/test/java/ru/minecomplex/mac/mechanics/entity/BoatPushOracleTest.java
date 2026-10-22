package ru.minecomplex.mac.mechanics.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;

import ru.minecomplex.mac.entity.interpolation.BoatInterpolationOracleTest;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BoatPushOracleTest {

    static BoatPush.Actor actor(DataInputStream in,boolean boat)throws IOException {
        var actor = new BoatPush.Actor(); actor.boat = boat; actor.position = BoatInterpolationOracleTest.vector(in);
        actor.box = new Aabb(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());
        actor.noPhysics = in.readBoolean(); actor.vehicle = in.readBoolean(); actor.pushable = in.readBoolean();
        actor.velocity = BoatInterpolationOracleTest.vector(in); actor.needsSync = in.readBoolean(); return actor;
    }
    static void matches(DataInputStream in,BoatPush.Actor actor,String context)throws IOException {
        var expected = BoatInterpolationOracleTest.vector(in);
        FluidContactOracleTest.bits(expected.x(),actor.velocity.x(),context + " vx"); FluidContactOracleTest.bits(expected.y(),actor.velocity.y(),context + " vy");
        FluidContactOracleTest.bits(expected.z(),actor.velocity.z(),context + " vz"); assertEquals(in.readBoolean(),actor.needsSync,context + " sync");
    }
    @Test
    void matchesActualBoatPush()throws Exception {
        String path = "/vanilla/777/boat-push.bin.gz";
        var raw = getClass().getResourceAsStream(path); assertNotNull(raw,path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414253,in.readInt()); assertEquals(2,in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt()); assertEquals(4096,in.readInt());
            for (int i = 0; i < 4096; i++) {
                boolean otherBoat = in.readBoolean(), sameVehicle = in.readBoolean(); var own = actor(in,true); var other = actor(in,otherBoat);
                BoatPush.apply(own,other,sameVehicle); String context = "26.3 case=" + i;
                matches(in,own,context + " own"); matches(in,other,context + " other");
            }
            assertEquals(-1,in.read());
        }
    }
}
