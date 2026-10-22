package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * {@link BoatPassengerPhysics#seatYaw} against the oracle-verified {@link BoatPassengerPhysics#position}, and its inverse
 * {@link BoatPassengerPhysics#riderYawBeforeSeat} for yaws a vanilla tick can start with (within 105° of the boat's).
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BoatSeatYawTest {
    @Test
    void seatYawIsThePositionRiderYaw() {
        var seat = new BoatPassengerPhysics();
        var random = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            float yaw = (random.nextFloat() - 0.5F) * 2000.0F, d = (random.nextFloat() - 0.5F) * 20.0F, boat = (random.nextFloat() - 0.5F) * 2000.0F;
            var state = new BoatPassengerPhysics.State();
            state.yaw = yaw;
            seat.position(state, Vec3d.ZERO, boat, d, 0.5625F, false, false, 1, 0, Vec3d.ZERO, false, true);
            assertEquals(Float.floatToIntBits(state.yaw), Float.floatToIntBits(BoatPassengerPhysics.seatYaw(yaw, d, boat)), yaw + " " + d + " " + boat);
        }
    }

    @Test
    void theStartYawIsFoundOrReportedUncertain() {
        var random = new Random(11);
        int found = 0, uncertain = 0;
        for (int i = 0; i < 100_000; i++) {
            float before = (random.nextFloat() - 0.5F) * 720.0F, d = random.nextInt(4) == 0 ? 0.0F : (random.nextFloat() - 0.5F) * 10.0F;
            // a tick starts within 105° of the boat (onPassengerTurned), then the boat turns by d
            float start = before + (random.nextFloat() - 0.5F) * 210.0F;
            float delta = BoatPassengerPhysics.wrap(start - before);
            start = start + Math.clamp(delta, -105.0F, 105.0F) - delta;
            float after = before + d;
            float end = BoatPassengerPhysics.seatYaw(start, d, after);
            Float inverse = BoatPassengerPhysics.riderYawBeforeSeat(end, d, after);
            if (inverse == null) {
                uncertain++;
                continue;
            }
            found++;
            float a = start * (float) (Math.PI / 180.0), b = inverse * (float) (Math.PI / 180.0);
            assertEquals(Trig.sin(a), Trig.sin(b), start + " " + d + " " + after + " -> " + inverse);
            assertEquals(Trig.cos(a), Trig.cos(b), start + " " + d + " " + after + " -> " + inverse);
        }
        assertTrue(found > 80_000, "found " + found + ", uncertain " + uncertain);
    }
}
