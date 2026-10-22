package ru.minecomplex.mac.engine.movementtick;

import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * AbstractBoat.positionRider/clampRotation for a Player passenger, including the 26.3 split old-yaw update.
 */
public final class BoatPassengerPhysics {

    public static final class State {

        public Vec3d position = Vec3d.ZERO;

        public float yaw, yawOld, headYaw, bodyYaw;

        public State copy() {
            var s = new State();
            s.position = position;
            s.yaw = yaw;
            s.yawOld = yawOld;
            s.headYaw = headYaw;
            s.bodyYaw = bodyYaw;
            return s;
        }
    }

    public BoatPassengerPhysics() {
    }

    public static float wrap(float degrees) {
        float value = degrees % 360.0F;
        if (value >= 180.0F)
            value -= 360.0F;
        if (value < -180.0F)
            value += 360.0F;
        return value;
    }

    /**
     * vehicleAttachment is the passenger's resolved pose/scale/yaw attachment, not a server seat coordinate.
     */
    public void position(State passenger, Vec3d boatPosition, float boatYaw, float deltaRotation, float boatHeight, boolean raft, boolean chest, int passengerCount, int passengerIndex, Vec3d vehicleAttachment, boolean canTurnInBoats, boolean localAuthority) {
        float offset = chest ? 0.15F : 0.0F;
        if (passengerCount > 1)
            offset = passengerIndex == 0 ? 0.2F : -0.6F;
        double height = raft ? boatHeight * 0.8888889F : boatHeight / 3.0F;
        float angle = -boatYaw * (float) (Math.PI / 180.0), cos = Trig.cos(angle), sin = Trig.sin(angle);
        var seat = new Vec3d(0.0 * cos + (double) offset * sin, height, (double) offset * cos - 0.0 * sin);
        var ridingPosition = boatPosition.add(seat);
        passenger.position = VanillaMath.position(passenger.position, new Vec3d(ridingPosition.x() - vehicleAttachment.x(), ridingPosition.y() - vehicleAttachment.y(), ridingPosition.z() - vehicleAttachment.z()));
        if (!canTurnInBoats) {
            if (localAuthority) {
                passenger.yaw += deltaRotation;
                passenger.headYaw += deltaRotation;
            }
            clamp(passenger, boatYaw);
        }
    }

    private float clamp(State passenger, float boatYaw) {
        passenger.bodyYaw = boatYaw;
        float delta = wrap(passenger.yaw - boatYaw), target = VanillaMath.clamp(delta, -105.0F, 105.0F);
        passenger.yaw = passenger.yaw + target - delta;
        return target - delta;
    }

    /**
     * The yaw {@code positionRider} leaves to a player its client controls ({@code setYRot(getYRot() + deltaRotation)},
     * then {@code clampRotation} against the boat's yaw).
     */
    public static float seatYaw(float yaw, float deltaRotation, float boatYaw) {
        float turned = yaw + deltaRotation;
        float delta = wrap(turned - boatYaw), target = VanillaMath.clamp(delta, -105.0F, 105.0F);
        return turned + target - delta;
    }

    /**
     * The rider's yaw before {@link #seatYaw} turned it, from the yaw after it (26.3 sends the passenger's rotation after
     * the entity ticks), or {@code null} when it is not certain as far as movement reads a yaw ({@code sin}/{@code cos}
     * of its radians). The yaw a tick starts with is within 105° of the boat's (a mouse turn clamps it,
     * {@code onPassengerTurned}, and the previous seat kept it), and the boat and its rider turn alike: the seat's clamp then
     * moves it by rounding at most, so the start lies within a few ulps of {@code endYaw - deltaRotation}. That rounding
     * ({@code (yaw + target) - delta} crossing a power of two) can merge two neighbouring yaws: then it is not certain.
     */
    public static Float riderYawBeforeSeat(float endYaw, float deltaRotation, float boatYaw) {
        float candidate = endYaw - deltaRotation;
        for (int i = 0; i < 8; i++) candidate = Math.nextDown(candidate);
        Float found = null;
        for (int i = 0; i <= 16; i++, candidate = Math.nextUp(candidate)) {
            if (Float.floatToIntBits(seatYaw(candidate, deltaRotation, boatYaw)) != Float.floatToIntBits(endYaw))
                continue;
            if (found == null) {
                found = candidate;
                continue;
            }
            float a = found * (float) (Math.PI / 180.0), b = candidate * (float) (Math.PI / 180.0);
            if (Trig.sin(a) != Trig.sin(b) || Trig.cos(a) != Trig.cos(b))
                return null;
        }
        return found;
    }

    public void onPassengerTurned(State passenger, float boatYaw) {
        float delta = clamp(passenger, boatYaw);
        passenger.yawOld += delta;
    }
}
