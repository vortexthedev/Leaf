package ru.minecomplex.mac.engine.movementtick;

import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Actual living transport travel/move phases; base/entity/passenger ticks remain ordered by the owner
 * ({@link LivingVehicleTickPhysics} orders a whole tick).
 */
public final class LivingVehicleBodyPhysics extends MovementTicker {

    private final OtherVehicleRiding.Kind kind;

    private final boolean controlledByClient, playerController, noGravity;

    private final double flyingSpeed;

    private float speed;

    private OtherVehicleRiding tickControl;

    private OtherVehicleRiding.State tickState;

    private java.util.function.Supplier<OtherVehicleRiding.Facts> tickFacts;

    private OtherVehicleRiding.Rider tickRider;

    private boolean tickNavigationCanFloat;

    public LivingVehicleBodyPhysics(MovementEnvironment env, PlayerState body, OtherVehicleRiding.Kind kind, float eyeHeight, boolean pushedByFluid, boolean controlledByClient, boolean playerController, boolean noGravity, double flyingSpeed) {
        super(env, body, new FluidWorld.EntityFacts(false, eyeHeight, pushedByFluid));
        if (!body.fixedBodyDimensions)
            throw new IllegalArgumentException("Living vehicle needs resolved dimensions");
        // Reject types that do not exist in this client.
        new OtherVehicleRiding(kind);
        this.kind = kind;
        this.controlledByClient = controlledByClient;
        this.playerController = playerController;
        this.noGravity = noGravity;
        this.flyingSpeed = flyingSpeed;
        body.standsOnLava = kind == OtherVehicleRiding.Kind.STRIDER;
    }

    public void travelBody(Vec3d input, float riddenSpeed) {
        if (!input.isFinite() || !p.deltaMovement.isFinite() || !Float.isFinite(riddenSpeed)) {
            unmodeled(Unmodeled.RIDING);
            return;
        }
        speed = riddenSpeed;
        if (kind == OtherVehicleRiding.Kind.HAPPY_GHAST) {
            float acceleration = (float) flyingSpeed * 5.0F / 3.0F;
            moveRelative(acceleration, input);
            move(MoverType.SELF, p.deltaMovement);
            velocity(p.deltaMovement.scale(p.wasTouchingWater ? 0.8F : p.isInLava ? 0.5 : 0.91F));
        } else if ((kind == OtherVehicleRiding.Kind.NAUTILUS || kind == OtherVehicleRiding.Kind.ZOMBIE_NAUTILUS) && p.wasTouchingWater) {
            moveRelative(speed, input);
            move(MoverType.SELF, p.deltaMovement);
            velocity(p.deltaMovement.scale(0.9));
        } else if (kind == OtherVehicleRiding.Kind.STRIDER && p.isInLava) {
            int current = fluids.kind(VanillaMath.floor(p.x()), VanillaMath.floor(p.y()), VanillaMath.floor(p.z()));
            if (!env.fluidsResolved())
                unmodeled(Unmodeled.FLUID);
            if (current == 2)
                travelInAir(input);
            else
                livingTravel(input);
        } else
            livingTravel(input);
    }

    /**
     * Connects the control phases with the actual mutable owning body.
     */
    public void travelRidden(OtherVehicleRiding control, OtherVehicleRiding.State state, OtherVehicleRiding.Facts facts, OtherVehicleRiding.Rider rider, boolean simulate) {
        control.travelRidden(state, facts, rider, simulate, (s, input, riddenSpeed) -> {
            // LivingEntity.setSpeed also writes the forward field.
            p.zza = riddenSpeed;
            p.deltaMovement = s.movement;
            p.yRot = s.yaw;
            p.xRot = s.pitch;
            p.onGround = s.onGround;
            travelBody(input, riddenSpeed);
            s.movement = p.deltaMovement;
            s.onGround = p.onGround;
        }, this::unmodeled);
        p.deltaMovement = state.movement;
        p.yRot = state.yaw;
        p.xRot = state.pitch;
        p.onGround = state.onGround;
    }

    public void floatStrider(OtherVehicleRiding control, OtherVehicleRiding.State state) {
        if (!env.fluidsResolved()) {
            unmodeled(Unmodeled.FLUID);
            return;
        }
        int x = VanillaMath.floor(p.x()), y = VanillaMath.floor(p.y()), z = VanillaMath.floor(p.z());
        state.movement = p.deltaMovement;
        state.onGround = p.onGround;
        boolean grounded = control.floatStrider(state, p.isInLava, p.y() > y + 0.5 - 1.0E-5F, fluids.kind(x, y + 1, z) == 2);
        p.deltaMovement = state.movement;
        p.onGround = state.onGround;
        if (grounded)
            setOnGround(true);
    }

    public void blockEffects() {
        applyEffectsFromBlocks();
    }

    /**
     * Entity.baseTick/LivingEntity.baseTick phases that move or decide movement, as for the horse family.
     */
    public void baseTick() {
        p.wasInPowderSnow = p.isInPowderSnow;
        p.isInPowderSnow = false;
        fluids.refresh(p, true);
        p.swimming = p.swimming ? p.sprinting && p.wasTouchingWater && !p.passenger : p.sprinting && p.wasEyeInWater && p.wasTouchingWater && !p.passenger && fluids.kind(VanillaMath.floor(p.x()), VanillaMath.floor(p.y()), VanillaMath.floor(p.z())) == 1;
        if (p.isInLava && !p.firstTick)
            p.fallDistance = p.fallDistance * 0.5;
        p.firstTick = false;
        if (!p.effects.timingResolved())
            unmodeled(Unmodeled.EFFECT_TIMING);
        p.effects.tickClient();
    }

    /**
     * Complete LivingEntity.aiStep movement path for a locally simulated ridden mob; the control facts are read
     * when travel reads them (after the base tick refreshed the fluids).
     */
    public void riddenAiStep(OtherVehicleRiding control, OtherVehicleRiding.State state, java.util.function.Supplier<OtherVehicleRiding.Facts> facts, OtherVehicleRiding.Rider rider, boolean effectiveAi, boolean navigationCanFloat) {
        tickControl = control;
        tickState = state;
        tickFacts = facts;
        tickRider = rider;
        tickNavigationCanFloat = navigationCanFloat;
        try {
            livingAiStep();
        } finally {
            tickControl = null;
            tickState = null;
            tickFacts = null;
            tickRider = null;
        }
    }

    private boolean interpolating() {
        return p.interpolation != null && p.interpolation.active();
    }

    /**
     * {@code Strider.tick}: warm in or on {@code #strider_warm_blocks}, or with any lava height from the last base tick.
     */
    public boolean striderWarm() {
        return is(getBlockState(blockPosition()), ru.minecomplex.mac.world.BlockTag.STRIDER_WARM_BLOCKS) || is(getBlockState(getOnPosLegacy()), ru.minecomplex.mac.world.BlockTag.STRIDER_WARM_BLOCKS) || p.lavaHeight > 0.0;
    }

    /**
     * {@code getBlockSpeedFactor()} of the body (the nautilus dash reads it).
     */
    public float blockSpeedFactor() {
        return getBlockSpeedFactor();
    }

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
    protected void travel(Vec3d input) {
        if (tickControl == null) {
            unmodeled(Unmodeled.RIDING);
            return;
        }
        // the control reads the body as aiStep left it (small motions zeroed, input applied)
        tickState.movement = p.deltaMovement;
        tickState.yaw = p.yRot;
        tickState.pitch = p.xRot;
        tickState.onGround = p.onGround;
        travelRidden(tickControl, tickState, tickFacts.get(), tickRider, true);
    }

    /**
     * Mob.jumpInLiquid: without a floating navigation the mob rises by a double 0.3.
     */
    @Override
    protected void jumpInLiquid() {
        if (tickControl == null) {
            unmodeled(Unmodeled.RIDING);
            return;
        }
        if (tickNavigationCanFloat)
            super.jumpInLiquid();
        else
            p.deltaMovement = p.deltaMovement.add(0.0, 0.3, 0.0);
    }

    private void velocity(Vec3d v) {
        if (v.isFinite())
            p.deltaMovement = v;
    }

    @Override
    protected void moveRelative(float speed, Vec3d input) {
        velocity(p.deltaMovement.add(getInputVector(input, speed, p.yRot)));
    }

    @Override
    protected float getSpeed() {
        return speed;
    }

    @Override
    protected float getFlyingSpeed() {
        return playerController ? speed * 0.1F : 0.02F;
    }

    @Override
    protected float maxUpStep() {
        float step = super.maxUpStep();
        return playerController ? Math.max(step, 1.0F) : step;
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
        if (kind == OtherVehicleRiding.Kind.HAPPY_GHAST)
            return;
        if (kind == OtherVehicleRiding.Kind.STRIDER && p.isInLava) {
            resetFallDistance();
            return;
        }
        {
            super.checkFallDamage(dy, grounded, state, pos);
            return;
        }
    }

    @Override
    protected boolean onClimbable() {
        return kind != OtherVehicleRiding.Kind.HAPPY_GHAST && super.onClimbable();
    }

    @Override
    protected double getEffectiveGravity() {
        return noGravity ? 0.0 : super.getEffectiveGravity();
    }
}
