package ru.minecomplex.mac.mechanics.movement;

import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.Vec3d;

/** LivingEntity.updateFallFlyingMovement. Effective gravity belongs to the current movement hypothesis. */
public final class FallFlyingMovement {
    public FallFlyingMovement() { }
    public Vec3d update(Vec3d movement, float yaw, float pitch, double gravity) {
        float xr = pitch * (float) (Math.PI / 180.0), yr = -yaw * (float) (Math.PI / 180.0);
        float yc = Trig.cos(yr), ys = Trig.sin(yr), xc = Trig.cos(xr), xs = Trig.sin(xr);
        Vec3d look = new Vec3d(ys * xc, -xs, yc * xc);
        double lookHorizontal = Math.sqrt(look.x() * look.x() + look.z() * look.z());
        double horizontal = movement.horizontalDistance();
        double cosine = Math.cos(xr), lift = cosine * cosine;
        movement = movement.add(0.0, gravity * (-1.0 + lift * 0.75), 0.0);
        if (movement.y() < 0.0 && lookHorizontal > 0.0) {
            double convert = movement.y() * -0.1 * lift;
            movement = movement.add(look.x() * convert / lookHorizontal, convert, look.z() * convert / lookHorizontal);
        }
        if (xr < 0.0F && lookHorizontal > 0.0) {
            double convert = horizontal * -Trig.sin(xr) * 0.04;
            movement = movement.add(-look.x() * convert / lookHorizontal, convert * 3.2, -look.z() * convert / lookHorizontal);
        }
        if (lookHorizontal > 0.0) {
            movement = movement.add((look.x() / lookHorizontal * horizontal - movement.x()) * 0.1, 0.0,
                    (look.z() / lookHorizontal * horizontal - movement.z()) * 0.1);
        }
        return movement.multiply(0.99F, 0.98F, 0.99F);
    }
}
