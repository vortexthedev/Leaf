package ru.minecomplex.mac.mechanics.entity;

import ru.minecomplex.mac.engine.movementtick.BoatPassengerPhysics;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/** BodyRotationControl.clientTick. Movement threshold is a double in the actual bytecode. */
public final class MobBodyRotation {
    public static final class State {
        public int headStableTime;
        public float lastStableHeadYaw;

        public State copy() {
            var copy = new State();
            copy.headStableTime = headStableTime;
            copy.lastStableHeadYaw = lastStableHeadYaw;
            return copy;
        }
    }

    /** The mob's rotations the control reads ({@code getYRot()}) and turns ({@code yHeadRot}, {@code yBodyRot}). */
    public static final class Look {
        public float yaw, headYaw, bodyYaw;

        public Look set(float yaw, float headYaw, float bodyYaw) {
            this.yaw = yaw;
            this.headYaw = headYaw;
            this.bodyYaw = bodyYaw;
            return this;
        }
    }

    private static float rotate(float base, float target, float max) {
        return target - VanillaMath.clamp(BoatPassengerPhysics.wrap(target - base), -max, max);
    }

    public void tick(State state, Look look, Vec3d oldPosition, Vec3d position, float maxHeadYaw,
                     boolean firstPassengerIsMob, boolean camelRefuses) {
        if (camelRefuses) return;
        double dx = position.x() - oldPosition.x(), dz = position.z() - oldPosition.z();
        if (dx * dx + dz * dz > 2.500000277905201E-7) {
            look.bodyYaw = look.yaw;
            look.headYaw = rotate(look.headYaw, look.bodyYaw, maxHeadYaw);
            state.lastStableHeadYaw = look.headYaw;
            state.headStableTime = 0;
        } else if (!firstPassengerIsMob) {
            if (Math.abs(look.headYaw - state.lastStableHeadYaw) > 15.0F) {
                state.headStableTime = 0;
                state.lastStableHeadYaw = look.headYaw;
                look.bodyYaw = rotate(look.bodyYaw, look.headYaw, maxHeadYaw);
            } else if (++state.headStableTime > 10) {
                float fraction = VanillaMath.clamp((state.headStableTime - 10) / 10.0F, 0.0F, 1.0F);
                look.bodyYaw = rotate(look.bodyYaw, look.headYaw, maxHeadYaw * (1.0F - fraction));
            }
        }
    }
}
