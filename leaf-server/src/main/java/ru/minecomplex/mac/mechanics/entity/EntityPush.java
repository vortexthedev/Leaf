package ru.minecomplex.mac.mechanics.entity;


/**
 * {@code Entity.push(Entity)} with the local player as {@code this}: the client half of {@code LivingEntity.doPush}
 * ({@code entity.push(this)}) when a living entity's {@code pushEntities} finds the local player. Both are pushed apart by
 * {@code 0.05F} scaled with the inverse square root of their larger horizontal offset when that is at least {@code 0.01F}.
 */
public final class EntityPush {
    private EntityPush() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    /**
     * @param self        the local player ({@code this})
     * @param pusher      the pushing entity ({@code entity})
     * @param sameVehicle {@code isPassengerOfSameVehicle(entity)}
     */
    public static void pushedBy(BoatPush.Actor self, BoatPush.Actor pusher, boolean sameVehicle) {
        if (sameVehicle || pusher.noPhysics || self.noPhysics)
            return;
        double xa = pusher.position.x() - self.position.x();
        double za = pusher.position.z() - self.position.z();
        double dd = Math.max(Math.abs(xa), Math.abs(za));
        if (dd >= 0.01F) {
            dd = Math.sqrt(dd);
            xa /= dd;
            za /= dd;
            double pow = 1.0 / dd;
            if (pow > 1.0)
                pow = 1.0;
            xa *= pow;
            za *= pow;
            xa *= 0.05F;
            za *= 0.05F;
            if (!self.vehicle && self.pushable)
                push(self, -xa, 0.0, -za);
            if (!pusher.vehicle && pusher.pushable)
                push(pusher, xa, 0.0, za);
        }
    }

    /**
     * {@code Entity.push(x, y, z)}: from 1.21.11 a non-finite impulse is ignored.
     */
    private static void push(BoatPush.Actor actor, double x, double y, double z) {
        if ((!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)))
            return;
        actor.velocity = actor.velocity.add(x, y, z);
        actor.needsSync = true;
    }
}
