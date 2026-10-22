package ru.minecomplex.mac.engine.movementtick;

import java.util.function.Consumer;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Client pig/strider, happy-ghast and both nautilus ridden phases. Facts belong to one client hypothesis.
 */
public final class OtherVehicleRiding {

    public enum Kind {

        PIG, STRIDER, HAPPY_GHAST, NAUTILUS, ZOMBIE_NAUTILUS
    }

    public static final class State {

        public Vec3d movement = Vec3d.ZERO;

        public float yaw, pitch, yawOld, headYaw, bodyYaw, speed, pendingJump;

        public boolean boosting, dashing, jumping, needsSync, onGround;

        public int boostTime, boostDuration, dashCooldown;

        public State copy() {
            var s = new State();
            s.movement = movement;
            s.yaw = yaw;
            s.pitch = pitch;
            s.yawOld = yawOld;
            s.headYaw = headYaw;
            s.bodyYaw = bodyYaw;
            s.speed = speed;
            s.pendingJump = pendingJump;
            s.boosting = boosting;
            s.dashing = dashing;
            s.jumping = jumping;
            s.needsSync = needsSync;
            s.onGround = onGround;
            s.boostTime = boostTime;
            s.boostDuration = boostDuration;
            s.dashCooldown = dashCooldown;
            return s;
        }
    }

    public record Facts(boolean localAuthority, boolean saddled, boolean inWater, boolean suffocating, double movementSpeed, double flyingSpeed, float blockSpeedFactor) {
    }

    public record Rider(float sideways, float forward, float yaw, float pitch, boolean jumping) {
    }

    @FunctionalInterface
    public interface BodyTravel {

        void travel(State state, Vec3d input, float speed);
    }

    private final Kind kind;

    public OtherVehicleRiding(Kind kind) {
        this.kind = kind;
    }

    private boolean nautilus() {
        return kind == Kind.NAUTILUS || kind == Kind.ZOMBIE_NAUTILUS;
    }

    /**
     * Actual boost metadata callback, including repeated callbacks; applying it does not advance a tick.
     */
    public void boostSynced(State s, int duration) {
        s.boostDuration = duration;
        s.boosting = true;
        s.boostTime = 0;
    }

    public void tickBoost(State s) {
        if (s.boosting && s.boostTime++ > s.boostDuration)
            s.boosting = false;
    }

    public float boostFactor(State s) {
        return s.boosting ? 1.0F + 1.15F * Trig.sin((float) s.boostTime / s.boostDuration * (float) Math.PI) : 1.0F;
    }

    /**
     * DASH callback is dispatched for either flag value, not only a rising edge.
     */
    public void dashSynced(State s, boolean firstTick) {
        if (!firstTick && s.dashCooldown == 0)
            s.dashCooldown = 40;
    }

    /**
     * AbstractNautilus.tick tail, after its superclass; metadata callback runs before decrement.
     */
    public void advanceNautilusDash(State s, boolean firstTick) {
        if (!nautilus())
            throw new IllegalStateException("Not a nautilus");
        if (s.dashing && s.dashCooldown < 35) {
            s.dashing = false;
            dashSynced(s, firstTick);
        }
        if (s.dashCooldown > 0)
            s.dashCooldown--;
    }

    public void charge(State s, Facts f, int amount) {
        if (nautilus() && f.saddled() && s.dashCooldown <= 0)
            s.pendingJump = amount >= 90 ? 1.0F : 0.4F + 0.4F * amount / 90.0F;
    }

    public Vec3d input(Facts f, Rider rider) {
        if (kind == Kind.PIG || kind == Kind.STRIDER)
            return new Vec3d(0.0, 0.0, 1.0);
        float forward = 0.0F, up = 0.0F;
        if (rider.forward() != 0.0F) {
            float angle = rider.pitch() * (float) (Math.PI / 180.0);
            forward = Trig.cos(angle);
            up = -Trig.sin(angle);
            if (rider.forward() < 0.0F) {
                forward *= -0.5F;
                up *= -0.5F;
            }
        }
        if (kind == Kind.HAPPY_GHAST && rider.jumping())
            up += 0.5F;
        var input = new Vec3d(rider.sideways(), up, forward);
        return kind == Kind.HAPPY_GHAST ? input.scale(3.9F * f.flyingSpeed()) : input;
    }

    public float speed(State s, Facts f) {
        return switch(kind) {
            case PIG ->
                (float) (f.movementSpeed() * 0.225 * boostFactor(s));
            case STRIDER ->
                (float) (f.movementSpeed() * (f.suffocating() ? 0.35F : 0.55F) * boostFactor(s));
            case HAPPY_GHAST ->
                s.speed;
            case NAUTILUS, ZOMBIE_NAUTILUS ->
                (f.inWater() ? 0.0325F : 0.02F) * (float) f.movementSpeed();
        };
    }

    public void riddenTick(State s, Facts f, Rider rider) {
        float yaw = rider.yaw();
        if (kind == Kind.HAPPY_GHAST || nautilus())
            yaw = s.yaw + wrap(rider.yaw() - s.yaw) * (kind == Kind.HAPPY_GHAST ? 0.08F : 0.5F);
        if (Float.isFinite(yaw))
            s.yaw = yaw % 360.0F;
        float pitch = rider.pitch() * 0.5F;
        if (Float.isFinite(pitch))
            s.pitch = Math.clamp(pitch % 360.0F, -90.0F, 90.0F);
        // These fields receive the unwrapped intermediate yaw in both flying mounts.
        s.yawOld = s.headYaw = s.bodyYaw = (kind == Kind.HAPPY_GHAST || nautilus()) ? yaw : s.yaw;
        if (kind == Kind.PIG || kind == Kind.STRIDER)
            tickBoost(s);
        if (nautilus() && f.localAuthority()) {
            if (s.pendingJump > 0.0F && !s.jumping)
                jump(s, f, rider, s.pendingJump);
            s.pendingJump = 0.0F;
        }
    }

    private static float wrap(float angle) {
        float v = angle % 360.0F;
        if (v >= 180.0F)
            v -= 360.0F;
        if (v < -180.0F)
            v += 360.0F;
        return v;
    }

    private void movement(State s, Vec3d v) {
        if (v.isFinite())
            s.movement = v;
    }

    public void jump(State s, Facts f, Rider rider, float amount) {
        if (!nautilus())
            throw new IllegalStateException("Not a jumping mount: " + kind);
        float xAngle = rider.pitch() * (float) (Math.PI / 180.0), yAngle = -rider.yaw() * (float) (Math.PI / 180.0);
        float cos = Trig.cos(xAngle), sin = Trig.sin(xAngle);
        var look = new Vec3d(Trig.sin(yAngle) * cos, -sin, Trig.cos(yAngle) * cos);
        var impulse = look.scale((f.inWater() ? 1.2F : 0.5F) * amount * f.movementSpeed() * f.blockSpeedFactor());
        if (impulse.isFinite())
            movement(s, s.movement.add(impulse));
        s.dashCooldown = 40;
        s.dashing = true;
        s.needsSync = true;
    }

    /**
     * Strider.floatStrider after super.tick; the collision/fluid queries are resolved in the same hypothesis.
     */
    public boolean floatStrider(State s, boolean inLava, boolean aboveSurface, boolean lavaAbove) {
        if (kind != Kind.STRIDER)
            throw new IllegalStateException("Not a strider");
        if (inLava) {
            if (aboveSurface && !lavaAbove) {
                s.onGround = true;
                return true;
            } else
                movement(s, s.movement.scale(0.5).add(0.0, 0.05, 0.0));
        }
        return false;
    }

    public void travelRidden(State s, Facts f, Rider rider, boolean canSimulateMovement, BodyTravel body, Consumer<Unmodeled> uncertainty) {
        var input = input(f, rider);
        riddenTick(s, f, rider);
        if (canSimulateMovement) {
            s.speed = speed(s, f);
            if (body == null)
                uncertainty.accept(Unmodeled.RIDING);
            else
                body.travel(s, input, s.speed);
        } else
            movement(s, Vec3d.ZERO);
    }
}
