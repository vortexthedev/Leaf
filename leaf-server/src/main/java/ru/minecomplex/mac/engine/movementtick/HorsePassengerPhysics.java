package ru.minecomplex.mac.engine.movementtick;

import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Actual horse/Camel passenger attachment and Entity.positionRider, using resolved pose attachments.
 */
public final class HorsePassengerPhysics {

    public record Facts(HorseState.Kind kind, Vec3d position, float yaw, float bodyYaw, float height, float seatScale, boolean baby, boolean removed, int passengerIndex, int passengerCount, boolean animalPassenger, long gameTime, long lastPoseChangeTick, float oldStandAnimation, Vec3d defaultAttachment) {
    }

    public HorsePassengerPhysics() {
    }

    private Vec3d rotate(Vec3d v, float yaw) {
        float angle = -yaw * (float) (Math.PI / 180.0), cos = Trig.cos(angle), sin = Trig.sin(angle);
        return new Vec3d(v.x() * cos + v.z() * sin, v.y(), v.z() * cos - v.x() * sin);
    }

    public Vec3d attachment(Facts f, float scale) {
        if (f.kind() != HorseState.Kind.CAMEL) {
            return f.defaultAttachment().add(rotate(new Vec3d(0.0, 0.15 * f.oldStandAnimation() * scale, -0.7 * f.oldStandAnimation() * scale), f.yaw()));
        }
        boolean front = Math.max(f.passengerIndex(), 0) == 0;
        float offset = 0.5F;
        float height = (float) (f.removed() ? 0.01F : anchor(f, front, 0.0F, scale));
        if (f.passengerCount() > 1) {
            if (!front)
                offset = -0.7F;
            if (f.animalPassenger())
                offset += 0.2F;
        }
        return rotate(new Vec3d(0.0, height, offset * scale), f.yaw());
    }

    public double anchor(Facts f, boolean front, float partialTicks, float scale) {
        double height = (double) f.height() - (f.baby() ? 0.09375 : 0.375);
        float sittingHeight = scale * 1.43F, drop = sittingHeight - scale * 0.2F, bottom = sittingHeight - drop;
        boolean sitting = f.lastPoseChangeTick() < 0;
        long age = f.gameTime() - Math.abs(f.lastPoseChangeTick());
        boolean transition = age < (sitting ? 40 : 52);
        if (transition) {
            int duration = sitting ? 40 : 52, half = sitting ? 28 : front ? 24 : 32;
            float flex = sitting ? (front ? 0.5F : 0.1F) : (front ? 0.6F : 0.35F);
            float time = VanillaMath.clamp((float) age + partialTicks, 0.0F, (float) duration);
            boolean first = time < half;
            float part = first ? time / half : (time - half) / (duration - half), middle = sittingHeight - flex * drop;
            float from = sitting ? (first ? sittingHeight : middle) : (first ? bottom - sittingHeight : bottom - middle);
            float to = sitting ? (first ? middle : bottom) : (first ? bottom - middle : 0.0F);
            height += from + part * (to - from);
        }
        if (sitting && !transition)
            height += bottom;
        return height;
    }

    /**
     * Position a LivingEntity passenger; AbstractHorse also copies the vehicle's body yaw.
     */
    public void position(BoatPassengerPhysics.State passenger, Facts f, Vec3d vehicleAttachment, boolean isPassenger) {
        if (!isPassenger)
            return;
        var point = f.position().add(attachment(f, f.seatScale()));
        passenger.position = VanillaMath.position(passenger.position, new Vec3d(point.x() - vehicleAttachment.x(), point.y() - vehicleAttachment.y(), point.z() - vehicleAttachment.z()));
        passenger.bodyYaw = f.bodyYaw();
    }
}
