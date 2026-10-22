package ru.minecomplex.mac.mechanics.fluid;

import ru.minecomplex.mac.util.math.Vec3d;

/** Verified LivingEntity fluid arithmetic. Contact, travel and phase selection belong to the tick model. */
public final class FluidMovement {
    private FluidMovement() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    public static Vec3d descend(Vec3d movement) {
        return movement.add(0.0, -0.04F, 0.0);
    }

    public static Vec3d jump(Vec3d movement) {
        return movement.add(0.0, 0.04F, 0.0);
    }

    public static Vec3d fallingAdjusted(Vec3d movement, double gravity, boolean falling, boolean sprinting) {
        if (gravity != 0.0 && !sprinting) {
            double y;
            if (falling && Math.abs(movement.y() - 0.005) >= 0.003
                    && Math.abs(movement.y() - gravity / 16.0) < 0.003) {
                y = -0.003;
            } else {
                y = movement.y() - gravity / 16.0;
            }
            return new Vec3d(movement.x(), y, movement.z());
        }
        return movement;
    }
}
