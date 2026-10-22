package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.List;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.collision.Axis;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.fixture.inbound.PaddleBoat;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.world.BlockRegistry;

/** {@code AbstractBoat.floatBoat}, {@code controlBoat} and the paddle packet against vanilla 26.3. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BoatMotionOracleTest {

    /** A boat's movement fields as vanilla recorded them: the body's position, velocity and yaw and the boat's own. */
    record Sample(Vec3d position, Vec3d velocity, float yaw, float deltaRotation, float landFriction, double waterLevel,
                  double lastYd, BoatState.Status status, BoatState.Status oldStatus, boolean leftPaddle, boolean rightPaddle) {
        static Sample read(DataInputStream in) throws IOException {
            var position = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
            var velocity = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
            float yaw = in.readFloat(), deltaRotation = in.readFloat(), landFriction = in.readFloat();
            double waterLevel = in.readDouble(), lastYd = in.readDouble();
            String status = in.readUTF(), old = in.readUTF();
            return new Sample(position, velocity, yaw, deltaRotation, landFriction, waterLevel, lastYd,
                    status.equals("NONE") ? null : BoatState.Status.valueOf(status),
                    old.equals("NONE") ? null : BoatState.Status.valueOf(old), in.readBoolean(), in.readBoolean());
        }

        /** The boat's own fields; with {@code body} also the body's position, velocity and yaw. */
        void applyTo(BoatState boat, boolean body) {
            if (body) {
                boat.body.position = position;
                boat.body.deltaMovement = velocity;
                boat.body.yRot = yaw;
            }
            boat.deltaRotation = deltaRotation;
            boat.landFriction = landFriction;
            boat.waterLevel = waterLevel;
            boat.lastYd = lastYd;
            boat.status = status;
            boat.oldStatus = oldStatus;
            boat.leftPaddle = leftPaddle;
            boat.rightPaddle = rightPaddle;
        }

        void compare(BoatState actual, String context) {
            for (var axis : Axis.values()) {
                PistonPhysicsOracleTest.bits(position.get(axis), actual.body.position.get(axis), context + " position " + axis);
                PistonPhysicsOracleTest.bits(velocity.get(axis), actual.body.deltaMovement.get(axis), context + " velocity " + axis);
            }
            assertEquals(Float.floatToIntBits(yaw), Float.floatToIntBits(actual.body.yRot), context + " yaw");
            assertEquals(Float.floatToIntBits(deltaRotation), Float.floatToIntBits(actual.deltaRotation), context + " rotation");
            assertEquals(Float.floatToIntBits(landFriction), Float.floatToIntBits(actual.landFriction), context + " land friction");
            PistonPhysicsOracleTest.bits(waterLevel, actual.waterLevel, context + " water level");
            PistonPhysicsOracleTest.bits(lastYd, actual.lastYd, context + " vertical history");
            assertEquals(status, actual.status, context);
            assertEquals(oldStatus, actual.oldStatus, context);
            assertEquals(leftPaddle, actual.leftPaddle, context);
            assertEquals(rightPaddle, actual.rightPaddle, context);
        }
    }

    @Test
    void matchesVanilla() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/boat-motion.bin.gz"); assertNotNull(raw);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d41424d, in.readInt()); assertEquals(1, in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(4096, in.readInt());
            var boat = new BoatState(new Object(), 1.375F, 0.5625F, 0.0F);
            var ticker = new MovementTickerBoat(LocalPlayerFluidOracleTest.environment(BlockRegistry.get(), List.of()), boat, null);
            for (int i = 0; i < 4096; i++) {
                Sample.read(in).applyTo(boat, true);
                double gravity = in.readDouble(), defaultGravity = in.readDouble(); float height = in.readFloat();
                boolean driver = in.readBoolean(), vehicle = in.readBoolean(), free = in.readBoolean(); float surface = in.readFloat(); int flags = in.readUnsignedByte();
                ticker.floatBoat(gravity, defaultGravity, height, driver, new MovementTickerBoat.Surface() {
                    public float waterLevelAbove() { return surface; } public boolean canSnapTo(double y) { return free; }
                });
                Sample.read(in).compare(boat, "26.3 floating " + i);
                ticker.controlBoat(vehicle, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0);
                Sample.read(in).compare(boat, "26.3 controls " + i);
                assertEquals(new PaddleBoat(in.readBoolean(), in.readBoolean()), Wire.in(ticker.paddles(driver)));
            }
            assertEquals(-1, in.read());
        }
    }
}
