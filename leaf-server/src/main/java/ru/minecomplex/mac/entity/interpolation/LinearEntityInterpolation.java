package ru.minecomplex.mac.entity.interpolation;

import java.util.function.Predicate;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.engine.movementtick.VehiclePacketState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Native 26.3 linear interpolation for boats.
 */
public class LinearEntityInterpolation {

    private int steps;

    private Vec3d target = Vec3d.ZERO, previous;

    private float yaw, pitch, previousYaw, previousPitch;

    public boolean active() {
        return steps > 0;
    }

    public VehiclePacketState.Target target() {
        return active() ? new VehiclePacketState.Target(target, yaw, pitch) : null;
    }

    public void cancel() {
        steps = 0;
        previous = null;
    }

    public LinearEntityInterpolation copy() {
        var copy = new LinearEntityInterpolation();
        copyTo(copy);
        return copy;
    }

    protected final void copyTo(LinearEntityInterpolation copy) {
        copy.steps = steps;
        copy.target = target;
        copy.previous = previous;
        copy.yaw = yaw;
        copy.pitch = pitch;
        copy.previousYaw = previousYaw;
        copy.previousPitch = previousPitch;
    }

    public boolean receive(PlayerState body, Vec3d position, float newYaw, float newPitch) {
        boolean equalYaw = yaw == newYaw;
        boolean equalPitch = pitch == newPitch;
        if (active() && target.equals(position) && equalYaw && equalPitch)
            return false;
        target = position;
        yaw = newYaw;
        pitch = newPitch;
        steps = 3;
        {
            previous = body.position;
            previousYaw = body.yRot;
            previousPitch = body.xRot;
        }
        return true;
    }

    /**
     * Whether the last tick's free check could change the target (its adjusted target differed from the target).
     */
    public boolean queryMattered() {
        return queryMattered;
    }

    private boolean queryMattered;

    public void tick(PlayerState body, Predicate<Aabb> collisionFree) {
        queryMattered = false;
        if (!active()) {
            cancel();
            return;
        }
        if (previous != null) {
            var delta = body.position.subtract(previous);
            if (delta.lengthSqr() > 1.0E-5F) {
                var adjusted = target.add(delta);
                var box = body.makeBoundingBox(adjusted);
                // a target the delta leaves unchanged (bit for bit) stays whatever the free check answers
                queryMattered |= !adjusted.equals(target);
                if (collisionFree.test(box))
                    target = adjusted;
            }
            yaw += body.yRot - previousYaw;
            pitch += body.xRot - previousPitch;
        }
        double alpha = 1.0 / steps;
        body.position = VanillaMath.position(body.position, new Vec3d(lerp(alpha, body.x(), target.x()), lerp(alpha, body.y(), target.y()), lerp(alpha, body.z(), target.z())));
        float nextYaw = (float) (body.yRot + alpha * wrap((double) yaw - body.yRot)) % 360.0F;
        float nextPitch = (float) lerp(alpha, body.xRot, pitch) % 360.0F;
        if (Float.isFinite(nextYaw))
            body.yRot = nextYaw;
        if (Float.isFinite(nextPitch))
            body.xRot = Math.clamp(nextPitch, -90.0F, 90.0F);
        steps--;
        {
            previous = body.position;
            previousYaw = body.yRot;
            previousPitch = body.xRot;
        }
    }

    private static double lerp(double alpha, double from, double to) {
        return from + alpha * (to - from);
    }

    private static double wrap(double angle) {
        double value = angle % 360.0;
        if (value >= 180.0)
            value -= 360.0;
        if (value < -180.0)
            value += 360.0;
        return value;
    }
}
