package ru.minecomplex.mac.entity;

import java.util.Set;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.mechanics.entity.MobBodyRotation;
import ru.minecomplex.mac.player.state.PlayerState;

/**
 * The client's {@code AbstractHorse} / {@code Camel} of one entity generation in one client tick hypothesis: the body
 * ({@code Entity}/{@code LivingEntity} fields of {@link #body}), the horse's own fields and what the client knows about
 * the mob (its saddle, AI, type constants), which the model sets before a tick.
 */
public final class HorseState {
    public enum Kind { HORSE, DONKEY, MULE, SKELETON_HORSE, ZOMBIE_HORSE, CAMEL }

    /** {@code Camel}'s pose as far as its tick reads it. */
    public enum CamelPose { STANDING, SITTING }

    public final Object generation;
    public final Kind kind;
    public PlayerState body;

    // Entity / LivingEntity rotations the horse keeps beside the body
    /** {@code yRotO}. */
    public float yawOld;
    public float headYaw, bodyYaw;

    // AbstractHorse
    /** {@code playerJumpPendingScale}. */
    public float pendingJump;
    public boolean allowStandSliding, jumping, standing, eating, needsSync;
    public int standCounter, gallopSoundCounter;
    public float standAnimation, oldStandAnimation;

    // Camel
    public boolean dashing;
    public int dashCooldown;
    /** {@code lastPoseChangeTick}: negative while sitting. */
    public long lastPoseChangeTick;
    public CamelPose pose = CamelPose.STANDING;

    public MobBodyRotation.State bodyRotation = new MobBodyRotation.State();

    // What the client knows about the mob (set by the model before a tick)
    /** {@code isSaddled()}. */
    public boolean saddled = true;
    /** {@code isLocalInstanceAuthoritative()}: the local player controls it. */
    public boolean localAuthority = true;
    /** {@code isControlledByLocalInstance()}. */
    public boolean controlledByClient = true;
    /** {@code getControllingPassenger() instanceof Player}. */
    public boolean playerController = true;
    /** {@code isEffectiveAi()} on the client. */
    public boolean effectiveAi;
    public boolean noGravity;
    /** {@code canPerformRearing()} of the type. */
    public boolean canRear;
    /** {@code isPushedByFluid()} of the type. */
    public boolean pushedByFluid = true;
    /** {@code getNavigation().canFloat()}: whether a jump in liquid is the living one. */
    public boolean navigationCanFloat = true;
    /** {@code getMaxHeadYRot()}. */
    public float maxHeadYaw = 75.0F;
    public float eyeHeight;
    /** The standing dimensions a sitting camel takes when it stands up ({@code refreshDimensions}). */
    public float standingWidth, standingHeight, standingEyeHeight;

    public boolean completeTick;
    /** Historical unresolved movement persists until the owner supplies a newly resolved body baseline. */
    public Set<Unmodeled> tickGaps = Set.of();
    /** Partial phase solvers retain this gap; only a whole tick certifies a resolved tick. */
    public final Set<Unmodeled> baseGaps = Set.of(Unmodeled.RIDING);

    public HorseState(Object generation, Kind kind, PlayerState body) {
        this.generation = java.util.Objects.requireNonNull(generation);
        this.kind = java.util.Objects.requireNonNull(kind);
        this.body = java.util.Objects.requireNonNull(body);
    }

    public boolean camel() {
        return kind == Kind.CAMEL;
    }

    /** {@code Camel.isCamelSitting()}. */
    public boolean sitting() {
        return lastPoseChangeTick < 0L;
    }

    /** The owner installed a newly resolved body baseline: earlier unresolved movement no longer affects it. */
    public void resolvedBaseline() {
        tickGaps = Set.of();
    }

    public HorseState copy() {
        var copy = new HorseState(generation, kind, body.copy());
        copy.yawOld = yawOld;
        copy.headYaw = headYaw;
        copy.bodyYaw = bodyYaw;
        copy.pendingJump = pendingJump;
        copy.allowStandSliding = allowStandSliding;
        copy.jumping = jumping;
        copy.standing = standing;
        copy.eating = eating;
        copy.needsSync = needsSync;
        copy.standCounter = standCounter;
        copy.gallopSoundCounter = gallopSoundCounter;
        copy.standAnimation = standAnimation;
        copy.oldStandAnimation = oldStandAnimation;
        copy.dashing = dashing;
        copy.dashCooldown = dashCooldown;
        copy.lastPoseChangeTick = lastPoseChangeTick;
        copy.pose = pose;
        copy.bodyRotation = bodyRotation.copy();
        copy.saddled = saddled;
        copy.localAuthority = localAuthority;
        copy.controlledByClient = controlledByClient;
        copy.playerController = playerController;
        copy.effectiveAi = effectiveAi;
        copy.noGravity = noGravity;
        copy.canRear = canRear;
        copy.pushedByFluid = pushedByFluid;
        copy.navigationCanFloat = navigationCanFloat;
        copy.maxHeadYaw = maxHeadYaw;
        copy.eyeHeight = eyeHeight;
        copy.standingWidth = standingWidth;
        copy.standingHeight = standingHeight;
        copy.standingEyeHeight = standingEyeHeight;
        copy.completeTick = completeTick;
        copy.tickGaps = tickGaps;
        return copy;
    }
}
