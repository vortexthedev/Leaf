package ru.minecomplex.mac.mechanics.movement;

import ru.minecomplex.mac.util.math.VanillaMath;

/**
 * Rotation part of {@code PositionMoveRotation.calculateAbsolute} and the storage rules of
 * {@code Entity.setYRot/setXRot}, as applied by the client to {@code player_position} and {@code player_rotation}.
 */
public final class RotationUpdate {
    private RotationUpdate() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    /**
     * Absolute yaw computed by {@code calculateAbsolute}.
     */
    public static float absoluteYRot(float currentYRot, float changeYRot, boolean relative) {
        float offset = relative ? currentYRot : 0.0F;
        return offset + changeYRot;
    }

    /**
     * Absolute pitch computed by {@code calculateAbsolute}; clamped to ±90 from 1.21.5.
     */
    public static float absoluteXRot(float currentXRot, float changeXRot, boolean relative) {
        float offset = relative ? currentXRot : 0.0F;
        float absolute = offset + changeXRot;
        return VanillaMath.clamp(absolute, -90.0F, 90.0F);
    }

    /**
     * Yaw that {@code handleRotatePlayer} passes to {@code setYRot}: the packet value up to 1.21.8, the
     * {@code calculateAbsolute} result from 1.21.9 ({@code 0.0F + yRot} turns -0.0 into +0.0 when absolute).
     */
    public static float rotationPacketYRot(float currentYRot, float yRot, boolean relative) {
        return absoluteYRot(currentYRot, yRot, relative);
    }

    /**
     * Pitch that {@code handleRotatePlayer} passes to {@code setXRot}: the packet value up to 1.21.8 (no ±90 clamp
     * before {@code % 360}), the clamped {@code calculateAbsolute} result from 1.21.9.
     */
    public static float rotationPacketXRot(float currentXRot, float xRot, boolean relative) {
        return absoluteXRot(currentXRot, xRot, relative);
    }

    /**
     * Yaw after {@code Entity.setYRot(value)}: non-finite values are discarded.
     */
    public static float storeYRot(float current, float value) {
        return Float.isFinite(value) ? value : current;
    }

    /**
     * Pitch after {@code Entity.setXRot(value)}: {@code Math.clamp(value % 360, -90, 90)}, non-finite discarded.
     */
    public static float storeXRot(float current, float value) {
        return Float.isFinite(value) ? Math.clamp(value % 360.0F, -90.0F, 90.0F) : current;
    }
}
