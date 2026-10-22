package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Axis;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BoatPassengerOracleTest {

    static BoatPassengerPhysics.State state(DataInputStream in) throws IOException {
        var s = new BoatPassengerPhysics.State(); s.position = PistonPhysicsOracleTest.vector(in);
        s.yaw = in.readFloat(); s.yawOld = in.readFloat(); s.headYaw = in.readFloat(); s.bodyYaw = in.readFloat(); return s;
    }
    static void compare(BoatPassengerPhysics.State e,BoatPassengerPhysics.State a,String context) {
        for (var axis : Axis.values()) PistonPhysicsOracleTest.bits(e.position.get(axis),a.position.get(axis),context + " position " + axis);
        BoatEnvironmentOracleTest.bits(e.yaw,a.yaw,context + " yaw"); BoatEnvironmentOracleTest.bits(e.yawOld,a.yawOld,context + " old yaw");
        BoatEnvironmentOracleTest.bits(e.headYaw,a.headYaw,context + " head yaw"); BoatEnvironmentOracleTest.bits(e.bodyYaw,a.bodyYaw,context + " body yaw");
    }
    @Test
    void matchesActualPlayerPositioning() throws Exception {
        String path = "/vanilla/777/boat-passenger.bin.gz"; var raw = getClass().getResourceAsStream(path); assertNotNull(raw,path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414250,in.readInt()); assertEquals(1,in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt()); assertEquals(4096,in.readInt());
            var physics = new BoatPassengerPhysics();
            for (int i = 0; i < 4096; i++) {
                boolean raft = in.readBoolean(), chest = in.readBoolean(); int count = in.readInt(), index = in.readInt();
                float yaw = in.readFloat(), rotation = in.readFloat(), height = in.readFloat(); boolean authority = in.readBoolean();
                Vec3d attachment = PistonPhysicsOracleTest.vector(in); var state = state(in);
                physics.position(state,new Vec3d(0.5,64.0,0.5),yaw,rotation,height,raft,chest,count,index,attachment,false,authority);
                compare(state(in),state,"26.3 position " + i);
                physics.onPassengerTurned(state,yaw); compare(state(in),state,"26.3 turn " + i);
            }
            assertEquals(-1,in.read());
        }
    }
}
