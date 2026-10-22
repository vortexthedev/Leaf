package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.vanilla.OracleBits;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class OtherVehicleOracleTest {

    static OtherVehicleRiding.State state(DataInputStream in, int kind, int duration) throws IOException {
        var s = new OtherVehicleRiding.State();
        s.movement = OracleBits.vector(in);
        s.yaw = in.readFloat();
        s.pitch = in.readFloat();
        s.yawOld = in.readFloat();
        s.headYaw = in.readFloat();
        s.bodyYaw = in.readFloat();
        s.speed = in.readFloat();
        s.onGround = in.readBoolean();
        s.needsSync = in.readBoolean();
        s.boostDuration = duration;
        if (kind < 2) {
            s.boosting = in.readBoolean();
            s.boostTime = in.readInt();
        }
        if (kind >= 3) {
            s.pendingJump = in.readFloat();
            s.jumping = in.readBoolean();
            s.dashing = in.readBoolean();
            s.dashCooldown = in.readInt();
        }
        return s;
    }

    static void compare(DataInputStream in, OtherVehicleRiding.State actual, int kind, int duration, String context) throws IOException {
        var e = state(in, kind, duration);
        for (var axis : ru.minecomplex.mac.collision.Axis.values()) FluidContactOracleTest.bits(e.movement.get(axis), actual.movement.get(axis), context + " movement " + axis);
        float[] expected = { e.yaw, e.pitch, e.yawOld, e.headYaw, e.bodyYaw, e.speed, e.pendingJump }, values = { actual.yaw, actual.pitch, actual.yawOld, actual.headYaw, actual.bodyYaw, actual.speed, actual.pendingJump };
        for (int n = 0; n < expected.length; n++) OracleBits.bits(expected[n], values[n], context + " float " + n);
        assertEquals(e.onGround, actual.onGround, context + " ground");
        assertEquals(e.needsSync, actual.needsSync, context + " sync");
        assertEquals(e.boosting, actual.boosting, context + " boosting");
        assertEquals(e.boostTime, actual.boostTime, context + " boost clock");
        assertEquals(e.jumping, actual.jumping, context + " jumping");
        assertEquals(e.dashing, actual.dashing, context + " dashing");
        assertEquals(e.dashCooldown, actual.dashCooldown, context + " cooldown");
    }

    @Test
    void matchesConcreteVanillaEntities() throws Exception {
        String path = "/vanilla/777/other-vehicle-controls.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414f56, in.readInt());
            assertEquals(2, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            var velocitySetter = OtherVehicleRiding.class.getDeclaredMethod("movement", OtherVehicleRiding.State.class, Vec3d.class);
            velocitySetter.setAccessible(true);
            int mask = in.readInt();
            assertEquals(3 | (4) | (24), mask);
            assertEquals(2048, in.readInt());
            for (var type : OtherVehicleRiding.Kind.values()) if ((mask & (1 << type.ordinal())) == 0)
                assertThrows(IllegalArgumentException.class, () -> new OtherVehicleRiding(type), "26.3 absent " + type);
            for (int kind = 0; kind < 5; kind++) if ((mask & (1 << kind)) != 0) {
                var riding = new OtherVehicleRiding(OtherVehicleRiding.Kind.values()[kind]);
                for (int i = 0; i < 2048; i++) {
                    int duration = kind < 2 ? in.readInt() : 0;
                    var facts = new OtherVehicleRiding.Facts(in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readDouble(), in.readDouble(), in.readFloat());
                    var rider = new OtherVehicleRiding.Rider(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readBoolean());
                    var s = state(in, kind, duration).copy();
                    String context = "26.3 " + OtherVehicleRiding.Kind.values()[kind] + " " + i;
                    if (kind >= 3) {
                        riding.charge(s, facts, in.readInt());
                        compare(in, s, kind, duration, context + " charge");
                    }
                    FluidTravelOracleTest.compare(in, riding.input(facts, rider), context + " input");
                    OracleBits.bits(in.readFloat(), riding.speed(s, facts), context + " speed");
                    riding.riddenTick(s, facts, rider);
                    compare(in, s, kind, duration, context + " ridden");
                    if (kind < 2) {
                        OracleBits.bits(in.readFloat(), riding.boostFactor(s), context + " boost factor");
                        riding.boostSynced(s, duration);
                        compare(in, s, kind, duration, context + " sync boost");
                    }
                    if (kind >= 3) {
                        riding.jump(s, facts, rider, in.readFloat());
                        compare(in, s, kind, duration, context + " direct dash");
                        boolean first = in.readBoolean();
                        s.dashCooldown = 0;
                        riding.dashSynced(s, first);
                        compare(in, s, kind, duration, context + " sync dash");
                    }
                    boolean simulate = in.readBoolean();
                    int[] count = { 0 };
                    Vec3d[] input = { null };
                    float[] speed = { 0 };
                    riding.travelRidden(s, facts, rider, simulate, (state, v, f) -> {
                        count[0]++;
                        input[0] = v;
                        speed[0] = f;
                    }, u -> fail(context + " uncertainty"));
                    int expectedCalls = in.readInt();
                    assertEquals(expectedCalls, count[0], context + " travel calls");
                    if (expectedCalls != 0) {
                        FluidTravelOracleTest.compare(in, input[0], context + " travel input");
                        OracleBits.bits(in.readFloat(), speed[0], context + " travel speed");
                    }
                    compare(in, s, kind, duration, context + " orchestration");
                    velocitySetter.invoke(riding, s, OracleBits.vector(in));
                    compare(in, s, kind, duration, context + " direct velocity setter");
                }
            }
            assertEquals(-1, in.read());
        }
    }
}
