package ru.minecomplex.mac.mechanics.entity;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * AbstractBoat.push and Entity.push for resolved actors returned in the client's query order.
 */
public final class BoatPush {
    private BoatPush() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    public static final class Actor {

        public Vec3d position, velocity;

        public Aabb box;

        public boolean boat, noPhysics, vehicle, pushable, needsSync;
    }

    public static void apply(Actor boat, Actor other, boolean sameVehicle) {
        if (other.boat ? other.box.minY() >= boat.box.maxY() : other.box.minY() > boat.box.minY())
            return;
        if (sameVehicle || boat.noPhysics || other.noPhysics)
            return;
        double x = other.position.x() - boat.position.x(), z = other.position.z() - boat.position.z();
        double distance = Math.max(Math.abs(x), Math.abs(z));
        if (distance >= 0.01F) {
            distance = Math.sqrt(distance);
            x /= distance;
            z /= distance;
            double power = 1.0 / distance;
            if (power > 1.0)
                power = 1.0;
            x *= power;
            z *= power;
            x *= 0.05F;
            z *= 0.05F;
            if (!boat.vehicle && boat.pushable)
                push(boat, -x, 0.0, -z);
            if (!other.vehicle && other.pushable)
                push(other, x, 0.0, z);
        }
    }

    private static void push(Actor actor, double x, double y, double z) {
        if ((!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)))
            return;
        actor.velocity = actor.velocity.add(x, y, z);
        actor.needsSync = true;
    }
}
