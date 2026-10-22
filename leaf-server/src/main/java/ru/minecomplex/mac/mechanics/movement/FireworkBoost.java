package ru.minecomplex.mac.mechanics.movement;

import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * {@code FireworkRocketEntity.tick}'s push of the living entity a rocket is attached to while that entity glides
 * ({@code isFallFlying}); the same in every supported release (its bytecode differs only in sound, impulse and
 * client-side accessors). The constants are doubles ({@code ldc2_w}).
 */
public final class FireworkBoost {

    public FireworkBoost() {
    }

    /**
     * {@code setDeltaMovement(movement.add(look.x * 0.1 + (look.x * 1.5 - movement.x) * 0.5, …))} with {@code look} the
     * entity's {@code getLookAngle()} ({@code calculateViewVector(getXRot(), getYRot())}).
     */
    public Vec3d boost(Vec3d movement, float yRot, float xRot) {
        float xr = xRot * (float) (Math.PI / 180.0), yr = -yRot * (float) (Math.PI / 180.0);
        float yc = Trig.cos(yr), ys = Trig.sin(yr), xc = Trig.cos(xr), xs = Trig.sin(xr);
        double lx = ys * xc, ly = -xs, lz = yc * xc;
        return movement.add(lx * 0.1 + (lx * 1.5 - movement.x()) * 0.5, ly * 0.1 + (ly * 1.5 - movement.y()) * 0.5,
                lz * 0.1 + (lz * 1.5 - movement.z()) * 0.5);
    }
}
