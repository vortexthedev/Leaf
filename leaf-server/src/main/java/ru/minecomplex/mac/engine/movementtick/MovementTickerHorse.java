package ru.minecomplex.mac.engine.movementtick;

import java.util.EnumSet;
import java.util.Set;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.mechanics.entity.MobBodyRotation;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * The client's tick of a horse-family mob or a camel the local player rides ({@code AbstractHorse}, {@code Camel}): the
 * base tick, {@code LivingEntity.aiStep} with the ridden travel ({@code tickRidden}, {@code getRiddenInput},
 * {@code getRiddenSpeed}, the jump), {@code BodyRotationControl} and the tails of {@code AbstractHorse.tick} and
 * {@code Camel.tick}. The horse is a {@link HorseState}; what it reads of its rider is the rider's {@link PlayerState}
 * ({@code xxa}, {@code zza}, rotation, sprinting).
 */
public class MovementTickerHorse extends MovementTicker {

    private final HorseState horse;

    private final MobBodyRotation rotation = new MobBodyRotation();

    private final MobBodyRotation.Look look = new MobBodyRotation.Look();

    /**
     * The controlling passenger of the current {@link #riddenAiStep}, whose travel is the ridden one.
     */
    private PlayerState rider;

    /**
     * {@code level.getGameTime()} of this tick: the camel's pose clock.
     */
    private long gameTime;

    /**
     * Inside {@link #riddenAiStep}: the overrides of {@code AbstractHorse} apply.
     */
    private boolean riddenStep;

    /**
     * {@code getSpeed()}: the ridden speed of the current travel.
     */
    private float speed;

    public MovementTickerHorse(MovementEnvironment env, HorseState horse) {
        super(env, horse.body, new FluidWorld.EntityFacts(false, horse.eyeHeight, horse.pushedByFluid));
        if (!horse.body.fixedBodyDimensions)
            throw new IllegalArgumentException("Horse body requires resolved dimensions");
        this.horse = horse;
        horse.body.standsOnLava = false;
    }

    public HorseState horse() {
        return horse;
    }

    /**
     * Sets {@code level.getGameTime()} for the phases called outside {@link #tick} (the camel's pose clock).
     */
    public void gameTime(long gameTime) {
        this.gameTime = gameTime;
    }

    // ------------------------------------------------------------------------------------------------------------
    // The whole tick
    /**
     * The root horse's client tick with the local player as its controlling passenger, in {@code LivingEntity.tick}
     * order; current contacts and an exceptional base state are reported, never guessed.
     *
     * @param rider                the controlling player (its last tick's movement fields, this frame's rotation)
     * @param pushableQueryEmpty   no other entity may be in its push box
     * @param exceptionalBaseState movement-changing effects, death or other base state the model does not resolve
     */
    public Set<Unmodeled> tick(PlayerState rider, long gameTime, boolean pushableQueryEmpty, boolean exceptionalBaseState) {
        this.gameTime = gameTime;
        var gaps = EnumSet.noneOf(Unmodeled.class);
        gaps.addAll(horse.tickGaps);
        clearUnmodeled();
        horse.completeTick = false;
        if (!horse.localAuthority) {
            gaps.add(Unmodeled.RIDING);
            horse.tickGaps = Set.copyOf(gaps);
            return horse.tickGaps;
        }
        if (exceptionalBaseState || !p.alive || p.passenger)
            gaps.add(Unmodeled.RIDING);
        if (!pushableQueryEmpty)
            gaps.add(Unmodeled.VEHICLE_ENTITIES);
        // tickNonPassenger: setOldPosAndRot, from 26.3 with the interpolation step (Entity.commonTick)
        p.oldPosition = p.position;
        horse.yawOld = p.yRot;
        commonTickInterpolation();
        baseTick();
        riddenAiStep(rider);
        rotation.tick(horse.bodyRotation, look.set(p.yRot, horse.headYaw, horse.bodyYaw), p.oldPosition, p.position, horse.maxHeadYaw, false, horse.camel() && refuses());
        horse.headYaw = look.headYaw;
        horse.bodyYaw = look.bodyYaw;
        while (p.yRot - horse.yawOld < -180.0F) horse.yawOld -= 360.0F;
        while (p.yRot - horse.yawOld >= 180.0F) horse.yawOld += 360.0F;
        if (tickTail(horse, horse.effectiveAi, p.onGround, p.wasTouchingWater || p.isInLava, p.passenger, p.wasTouchingWater, false, gameTime)) {
            refreshStandingDimensions();
        }
        gaps.addAll(unmodeled());
        horse.tickGaps = Set.copyOf(gaps);
        horse.completeTick = true;
        return horse.tickGaps;
    }

    /**
     * {@code Entity.baseTick} of the horse: powder snow, fluids, swimming, lava, effects.
     */
    public void baseTick() {
        p.wasInPowderSnow = p.isInPowderSnow;
        p.isInPowderSnow = false;
        fluids.refresh(p, true);
        p.swimming = p.swimming ? p.sprinting && p.wasTouchingWater && !p.passenger : p.sprinting && p.wasEyeInWater && p.wasTouchingWater && !p.passenger && fluids.kind(VanillaMath.floor(p.x()), VanillaMath.floor(p.y()), VanillaMath.floor(p.z())) == 1;
        if (p.isInLava && !p.firstTick) {
            p.fallDistance = p.fallDistance * 0.5;
        }
        p.firstTick = false;
        if (!p.effects.timingResolved())
            unmodeled(Unmodeled.EFFECT_TIMING);
        p.effects.tickClient();
    }

    /**
     * {@code LivingEntity.aiStep} of a locally simulated ridden horse.
     */
    public void riddenAiStep(PlayerState rider) {
        this.rider = rider;
        riddenStep = true;
        try {
            p.jumping = horse.jumping;
            livingAiStep();
            horse.jumping = p.jumping;
        } finally {
            riddenStep = false;
            this.rider = null;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Ridden travel: AbstractHorse / Camel
    /**
     * {@code Camel.isInPoseTransition()}.
     */
    public boolean transitioning() {
        return gameTime - Math.abs(horse.lastPoseChangeTick) < (horse.sitting() ? 40 : 52);
    }

    /**
     * {@code Camel.refuseToMove()}: sitting or between poses.
     */
    public boolean refuses() {
        return horse.camel() && (horse.sitting() || transitioning());
    }

    /**
     * {@code LivingEntity.travelRidden} with the controlling {@code rider}: ridden tick, then the body's travel.
     */
    public void travelRidden(PlayerState rider, boolean canSimulateMovement) {
        boolean standsUp = horse.camel() && rider.zza > 0.0F && horse.sitting() && !transitioning();
        Vec3d input = riddenInput(rider);
        tickRidden(rider, input);
        if (canSimulateMovement) {
            travelWithInput(input, riddenSpeed(rider), standsUp);
        } else {
            setMovement(Vec3d.ZERO);
        }
        if (standsUp)
            refreshStandingDimensions();
    }

    /**
     * The body's {@code travel} of a ridden step: {@code setSpeed}, then the living travel.
     */
    protected void travelWithInput(Vec3d input, float riddenSpeed, boolean standsUp) {
        // LivingEntity.setSpeed also writes the forward field.
        p.zza = riddenSpeed;
        if (standsUp)
            refreshStandingDimensions();
        travelBody(input, riddenSpeed, refuses());
    }

    /**
     * {@code AbstractHorse.getRiddenInput}: half the sideways input, a quarter of a backward one.
     */
    public Vec3d riddenInput(PlayerState rider) {
        if (refuses() || p.onGround && horse.pendingJump == 0.0F && horse.standing && !horse.allowStandSliding)
            return Vec3d.ZERO;
        float sideways = rider.xxa * 0.5F, forward = rider.zza;
        if (forward <= 0.0F)
            forward *= 0.25F;
        return new Vec3d(sideways, 0.0, forward);
    }

    /**
     * {@code getRiddenSpeed}: the movement speed attribute; a camel's sprint adds 0.1 while it may dash.
     */
    public float riddenSpeed(PlayerState rider) {
        if (!horse.camel())
            return (float) movementSpeed();
        return (float) movementSpeed() + (rider.sprinting && horse.dashCooldown == 0 ? 0.1F : 0.0F);
    }

    /**
     * {@code AbstractHorse.tickRidden}: rotation from the rider, the charged jump on the ground, the camel standing up.
     */
    public void tickRidden(PlayerState rider, Vec3d input) {
        boolean refuses = refuses();
        float yaw = refuses ? p.yRot : rider.yRot;
        float pitch = refuses ? p.xRot : rider.xRot * 0.5F;
        if (Float.isFinite(yaw))
            p.yRot = yaw % 360.0F;
        if (Float.isFinite(pitch))
            p.xRot = Math.clamp(pitch % 360.0F, -90.0F, 90.0F);
        horse.yawOld = horse.bodyYaw = horse.headYaw = p.yRot;
        if (horse.localAuthority) {
            if (input.z() <= 0.0)
                horse.gallopSoundCounter = 0;
            if (p.onGround) {
                if (horse.pendingJump > 0.0F && !horse.jumping)
                    jump(horse.pendingJump, input);
                horse.pendingJump = 0.0F;
            }
        }
        if (horse.camel() && rider.zza > 0.0F && horse.sitting() && !transitioning())
            horse.lastPoseChangeTick = gameTime;
    }

    /**
     * {@code onPlayerJump} / {@code handleStartJump}: the rider released a charged jump. Called by the client's charge
     * release, never by a marker acknowledgement.
     */
    public void charge(int charge) {
        if (!horse.saddled || horse.camel() && (horse.dashCooldown > 0 || !p.onGround))
            return;
        if (charge < 0) {
            charge = 0;
        } else {
            horse.allowStandSliding = true;
            if (horse.canRear && horse.effectiveAi) {
                horse.standing = true;
                horse.eating = false;
                horse.standCounter = 20;
            }
        }
        horse.pendingJump = charge >= 90 ? 1.0F : 0.4F + 0.4F * charge / 90.0F;
    }

    /**
     * {@code executeRidersJump}: a horse leaps up and forward; a camel dashes along its look.
     */
    public void jump(float amount, Vec3d input) {
        if (horse.camel()) {
            float yAngle = -p.yRot * (float) (Math.PI / 180.0), xAngle = p.xRot * (float) (Math.PI / 180.0);
            float yCos = Trig.cos(yAngle), ySin = Trig.sin(yAngle), xCos = Trig.cos(xAngle), xSin = Trig.sin(xAngle);
            var horizontal = new Vec3d(ySin * xCos, -xSin, yCos * xCos).multiply(1.0, 0.0, 1.0).normalize();
            var impulse = horizontal.scale(22.2222F * amount * movementSpeed() * getBlockSpeedFactor()).add(0.0, 1.4285F * amount * (double) ridingJumpPower(1.0F), 0.0);
            if (impulse.isFinite())
                setMovement(p.deltaMovement.add(impulse));
            horse.dashCooldown = 55;
            horse.dashing = true;
        } else {
            setMovement(new Vec3d(p.deltaMovement.x(), ridingJumpPower(amount), p.deltaMovement.z()));
            if (input.z() > 0.0) {
                float sin = Trig.sin(p.yRot * (float) (Math.PI / 180.0)), cos = Trig.cos(p.yRot * (float) (Math.PI / 180.0));
                setMovement(p.deltaMovement.add(-0.4F * sin * amount, 0.0, 0.4F * cos * amount));
            }
        }
        horse.needsSync = true;
    }

    /**
     * {@code getJumpPower(amount)} of a ridden jump: jump strength by the block's jump factor, plus jump boost.
     */
    private float ridingJumpPower(float amount) {
        return (float) jumpStrength() * amount * getBlockJumpFactor() + getJumpBoostPower();
    }

    /**
     * {@code setDeltaMovement}: from 1.21.11 a non-finite vector is refused.
     */
    private void setMovement(Vec3d movement) {
        if (movement.isFinite())
            p.deltaMovement = movement;
    }

    /**
     * The movement speed attribute ({@code getAttributeValue(MOVEMENT_SPEED)}).
     */
    protected double movementSpeed() {
        return p.attributes.value(Attribute.MOVEMENT_SPEED);
    }

    /**
     * {@code getJumpStrength()}: the jump strength attribute.
     */
    protected double jumpStrength() {
        return p.attributes.value(Attribute.JUMP_STRENGTH);
    }

    /**
     * The living travel of the body at the ridden speed (a camel refusing to move keeps only its fall).
     */
    public void travelBody(Vec3d input, float riddenSpeed, boolean refusesToMove) {
        if (!input.isFinite() || !p.deltaMovement.isFinite() || !Float.isFinite(riddenSpeed)) {
            unmodeled(Unmodeled.RIDING);
            return;
        }
        speed = riddenSpeed;
        // Camel.travel performs this after tickRidden, even when input was already suppressed.
        if (horse.camel() && refusesToMove && p.onGround) {
            p.deltaMovement = p.deltaMovement.multiply(0.0, 1.0, 0.0);
            input = input.multiply(0.0, 1.0, 0.0);
        }
        livingTravel(input);
    }

    /**
     * Client {@code Entity.refreshDimensions} of a camel standing up; the positional fudge is ServerLevel-only.
     */
    public void refreshStandingDimensions() {
        p.bodyWidth = horse.standingWidth;
        p.bodyHeight = horse.standingHeight;
        fluids.refreshEntityEyeHeight(horse.standingEyeHeight);
    }

    // ------------------------------------------------------------------------------------------------------------
    // The tails of AbstractHorse.tick and Camel.tick (also for remote horses' seat animation)
    /**
     * {@code Camel.onSyncedDataUpdated(DASH)}: a dash the server ended restarts the cooldown.
     */
    public static void dashSynced(HorseState horse, boolean firstTick) {
        if (!firstTick && horse.dashCooldown == 0)
            horse.dashCooldown = 55;
    }

    /**
     * The movement- and seat-relevant tails of {@code AbstractHorse.tick} and {@code Camel.tick}, after their superclass
     * tick: the standing countdown, the stand animation, the camel's dash cooldown, head turn and standing up in water.
     *
     * @return whether {@code standUpInstantly} requests an immediate dimension refresh
     */
    public static boolean tickTail(HorseState horse, boolean effectiveAi, boolean onGround, boolean inLiquid, boolean passenger, boolean inWater, boolean firstTick, long gameTime) {
        {
            if (horse.standCounter > 0 && --horse.standCounter <= 0) {
                horse.standing = false;
                horse.standCounter = 0;
            }
        }
        horse.oldStandAnimation = horse.standAnimation;
        if (!horse.standing)
            horse.allowStandSliding = false;
        horse.standAnimation = nextStandAnimation(horse.standing, horse.standAnimation);
        if (!horse.camel())
            return false;
        if (horse.dashing && horse.dashCooldown < 50 && (onGround || inLiquid || passenger)) {
            horse.dashing = false;
            dashSynced(horse, firstTick);
        }
        if (horse.dashCooldown > 0)
            horse.dashCooldown--;
        long age = gameTime - Math.abs(horse.lastPoseChangeTick);
        boolean sitting = horse.sitting();
        if (sitting || age < (sitting ? 40 : 52)) {
            float delta = BoatPassengerPhysics.wrap(horse.bodyYaw - horse.headYaw);
            horse.headYaw = horse.headYaw + delta - VanillaMath.clamp(delta, -30.0F, 30.0F);
        }
        if (sitting && inWater) {
            horse.pose = HorseState.CamelPose.STANDING;
            horse.lastPoseChangeTick = Math.max(0L, gameTime - 52L - 1L);
            return true;
        }
        return false;
    }

    /**
     * {@code AbstractHorse.tick}: the stand animation rises while standing and falls back otherwise.
     */
    public static float nextStandAnimation(boolean standing, float animation) {
        if (standing) {
            animation = animation + ((1.0F - animation) * 0.4F + 0.05F);
            return animation > 1.0F ? 1.0F : animation;
        }
        animation = animation + ((0.8F * animation * animation * animation - animation) * 0.6F - 0.05F);
        return animation < 0.0F ? 0.0F : animation;
    }

    // ------------------------------------------------------------------------------------------------------------
    // AbstractHorse / LivingEntity overrides of the body
    @Override
    protected boolean playerMotionThreshold() {
        return false;
    }

    @Override
    protected void applyInput() {
        {
            p.xxa *= 0.98F;
            p.zza *= 0.98F;
        }
    }

    @Override
    protected boolean isImmobile() {
        if (!riddenStep)
            return super.isImmobile();
        return !p.alive && horse.saddled || horse.eating || horse.standing;
    }

    /**
     * {@code AbstractHorse.travel} of a ridden step: {@code travelRidden} with the controlling player.
     */
    @Override
    protected void travel(Vec3d input) {
        if (!riddenStep) {
            unmodeled(Unmodeled.RIDING);
            return;
        }
        horse.jumping = p.jumping;
        travelRidden(rider, true);
        p.jumping = horse.jumping;
    }

    @Override
    protected float getJumpPower() {
        return riddenStep ? (float) jumpStrength() * 1.0F * getBlockJumpFactor() + getJumpBoostPower() : super.getJumpPower();
    }

    @Override
    protected void jumpInLiquid() {
        if (!riddenStep) {
            unmodeled(Unmodeled.RIDING);
            return;
        }
        if (horse.navigationCanFloat)
            super.jumpInLiquid();
        else
            p.deltaMovement = p.deltaMovement.add(0.0, 0.3, 0.0);
    }

    @Override
    protected void jumpFromGround() {
        float power = getJumpPower();
        super.jumpFromGround();
        if (riddenStep && !(power <= 1.0E-5F))
            horse.needsSync = true;
    }

    @Override
    protected float getWaterSlowDown() {
        return horse.kind == HorseState.Kind.SKELETON_HORSE ? 0.96F : super.getWaterSlowDown();
    }

    @Override
    protected float getSpeed() {
        return speed;
    }

    @Override
    protected float getFlyingSpeed() {
        return horse.playerController ? speed * 0.1F : 0.02F;
    }

    @Override
    protected float maxUpStep() {
        return horse.playerController ? Math.max(super.maxUpStep(), 1.0F) : super.maxUpStep();
    }

    @Override
    protected Vec3d maybeBackOffFromEdge(Vec3d delta, MoverType type) {
        return delta;
    }

    @Override
    protected boolean isSuppressingSlidingDownLadder() {
        return false;
    }

    @Override
    protected boolean fallResettingPortalShapes() {
        return false;
    }

    @Override
    protected boolean tracksClientFallDamage() {
        return true;
    }

    @Override
    protected void checkFallDamage(double dy, boolean grounded, int state, BlockPos pos) {
        {
            super.checkFallDamage(dy, grounded, state, pos);
            return;
        }
    }

    @Override
    protected double getEffectiveGravity() {
        return horse.noGravity ? 0.0 : super.getEffectiveGravity();
    }

    @Override
    protected void moveRelative(float speed, Vec3d input) {
        var next = p.deltaMovement.add(getInputVector(input, speed, p.yRot));
        if (next.isFinite())
            p.deltaMovement = next;
    }

}
