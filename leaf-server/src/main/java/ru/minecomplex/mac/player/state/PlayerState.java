package ru.minecomplex.mac.player.state;

import java.util.ArrayList;
import java.util.List;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.entity.effect.ActiveEffects;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.entity.attribute.AttributeMap;
import ru.minecomplex.mac.entity.attribute.AttributeModifier;
import ru.minecomplex.mac.entity.interpolation.EntityInterpolation;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.mechanics.movement.PistonMovement;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * The client-side fields of a player entity that movement reads or writes, named after vanilla's
 * ({@code Entity}, {@code LivingEntity}, {@code Player}). Mutable; {@link #copy()} forks it for hypotheses.
 *
 * <p>The bounding box is not stored: vanilla derives it from the position on every {@code setPos} and from the
 * dimensions cached at the last {@code refreshDimensions} (a pose change, a pose echo, the end of a tick with a changed
 * {@code scale}); {@link #dimensionScale} is the scale of that cache, so {@link #boundingBox()} computes the same value.
 */
public final class PlayerState {
    // Entity
    public Vec3d position = Vec3d.ZERO;
    /** {@code xOld/yOld/zOld}: the position when the tick started ({@code setOldPosAndRot}). */
    public Vec3d oldPosition = Vec3d.ZERO;
    public Vec3d deltaMovement = Vec3d.ZERO;
    /**
     * The local player's own interpolation ({@code getInterpolation()}), started by a {@code teleport_entity} addressed
     * to it; {@code null} until then (other bodies keep theirs elsewhere).
     */
    public EntityInterpolation interpolation;
    /** Resolved EntityDimensions for a non-player body using the shared Entity.move port. */
    public boolean fixedBodyDimensions;
    public float bodyWidth, bodyHeight;
    /** Resolved Strider fluid-standing predicate; ordinary player/boat bodies leave this false. */
    public boolean standsOnLava;
    public float yRot;
    public float xRot;
    public boolean onGround;
    public boolean horizontalCollision;
    public boolean verticalCollision;
    public boolean verticalCollisionBelow;
    public boolean minorHorizontalCollision;
    /** {@code double} from 1.21.5; a float before, kept here at float precision by the engine. */
    public double fallDistance;
    /** Client-global cache; other entities and picking must use this same hypothesis's cache. */
    public FluidShapeCache fluidShapes;
    public Vec3d stuckSpeedMultiplier = Vec3d.ZERO;
    public boolean hasSupportingBlock;
    public int supportingX;
    public int supportingY;
    public int supportingZ;
    public boolean onGroundNoBlocks;
    public boolean isInPowderSnow;
    public boolean wasInPowderSnow;
    /** {@code wasTouchingWater}, i.e. {@code isInWater()}. */
    public boolean wasTouchingWater;
    public boolean isInLava;
    public double waterHeight, lavaHeight;
    public boolean eyeInWater, eyeInLava, wasEyeInWater, wasUnderwater;
    public boolean noPhysics;
    public boolean passenger;
    /** The {@code scale} of the cached dimensions ({@code refreshDimensions}); a new entity computes them at 1. */
    public float dimensionScale = 1.0F;
    /** {@code isNoGravity()}: the entity data flag the server may set; {@code getGravity()} is then 0. */
    public boolean noGravity;
    /** {@code isAlive()}: {@code health > 0} (a client player is never removed). */
    public boolean alive = true;
    /** {@code isDeadOrDying()}: {@code health <= 0}; with a NaN health neither this nor {@link #alive} holds. */
    public boolean deadOrDying;
    public boolean firstTick = true;
    // shared flags
    public boolean shiftKeyDown;
    public boolean sprinting;
    public boolean swimming;
    public boolean fallFlying;
    public Pose pose = Pose.STANDING;

    // LivingEntity
    public int noJumpDelay;
    public boolean jumping;
    public float xxa;
    public float yya;
    public float zza;

    // Player
    public Abilities abilities;
    public AttributeMap attributes;
    public ActiveEffects effects;
    public boolean sleeping;
    /** {@code getSleepingPos()} ({@code SLEEPING_POS_ID}): the bed's block, {@code null} when none. */
    public int[] sleepingPos;
    public boolean spectator;
    public boolean autoSpinAttack;
    /**
     * {@code LivingEntity.autoSpinAttackTicks}: set to 20 by the client's riptide launch ({@code startAutoSpinAttack}),
     * ended (0) by a touched living entity; see {@link #autoSpinMayHaveEnded}.
     */
    public int autoSpinAttackTicks;
    /**
     * Whether the client's spin may already have ended ({@code autoSpinAttackTicks} 0): a horizontal collision with no
     * living entity ends it only when {@code getEntities} lists nothing at all (entities the model does not follow), a
     * touch the model could not decide may have ended it.
     */
    public boolean autoSpinMayHaveEnded;
    /** Diagnostic provenance only; never participates in the movement simulation. */
    public String riptideUnknownCause = "";
    /**
     * {@code LivingEntity.autoSpinAttackDmg}: 8.0F after the client's riptide launch ({@code startAutoSpinAttack}), never
     * reset on the client; {@code Player.attack} uses it instead of the attack damage while the spin flag is set.
     */
    public float autoSpinAttackDamage;
    /** Feet slot holds leather boots ({@code PowderSnowBlock.canEntityWalkOnPowderSnow}). */
    public boolean wearingLeatherBoots;

    /** {@code Entity.movementThisTick}: moves since the last block-effect pass. */
    public final List<Movement> movementThisTick = new ArrayList<>();
    public PistonMovement pistonMovement;
    public long pistonGameTime;

    /** {@code Entity.Movement}. {@code axisDependentOriginalMovement} is null when absent. */
    public record Movement(Vec3d from, Vec3d to, Vec3d axisDependentOriginalMovement) {
    }

    /** A new player entity: default abilities, the player's attribute bases, no effects. */
    public PlayerState() {
        fluidShapes = FluidShapeCache.unresolved();
        abilities = new Abilities();
        attributes = AttributeMap.forPlayer();
        effects = new ActiveEffects();
        pistonMovement = new PistonMovement();
    }

    /** For {@link #copy()}: the per-player objects are copied there, not created here. */
    private PlayerState(PlayerState source) {
        fluidShapes = source.fluidShapes.copy();
        abilities = source.abilities.copy();
        attributes = source.attributes.copy();
        effects = source.effects.copy();
        pistonMovement = source.pistonMovement.copy();
    }

    public double x() {
        return position.x();
    }

    public double y() {
        return position.y();
    }

    public double z() {
        return position.z();
    }

    /** {@code getScale()}: the {@code scale} attribute as a float. */
    public float scale() {
        return (float) attributes.value(Attribute.SCALE);
    }

    /** {@code refreshDimensions()}: the cached dimensions take the current pose and {@code scale}. */
    public void refreshDimensions() {
        dimensionScale = scale();
    }

    /** {@code getBbWidth()}: the cached dimensions. */
    public float width() {
        if (fixedBodyDimensions) return bodyWidth;
        return pose.width(dimensionScale);
    }

    /** {@code getBbHeight()}: the cached dimensions. */
    public float height() {
        if (fixedBodyDimensions) return bodyHeight;
        return pose.height(dimensionScale);
    }

    /** {@code makeBoundingBox(position)}: the cached dimensions; half width and height are floats. */
    public Aabb makeBoundingBox(Vec3d at) {
        return box(fixedBodyDimensions ? bodyWidth : pose.width(dimensionScale),
                fixedBodyDimensions ? bodyHeight : pose.height(dimensionScale), at);
    }

    /** Pose-fit queries: {@code getDimensions(pose)} with the current {@code scale}, without changing the pose. */
    public Aabb makeBoundingBox(Pose requestedPose, Vec3d at) {
        return box(fixedBodyDimensions ? bodyWidth : requestedPose.width(scale()),
                fixedBodyDimensions ? bodyHeight : requestedPose.height(scale()), at);
    }

    private static Aabb box(float width, float height, Vec3d at) {
        float w = width / 2.0F;
        return new Aabb(at.x() - w, at.y(), at.z() - w, at.x() + w, at.y() + height, at.z() + w);
    }

    public Aabb boundingBox() {
        float width = fixedBodyDimensions ? bodyWidth : pose.width(dimensionScale);
        float height = fixedBodyDimensions ? bodyHeight : pose.height(dimensionScale);
        if (box == null || boxPosition != position || Float.floatToRawIntBits(boxWidth) != Float.floatToRawIntBits(width)
                || Float.floatToRawIntBits(boxHeight) != Float.floatToRawIntBits(height)) {
            box = box(width, height, position);
            boxPosition = position;
            boxWidth = width;
            boxHeight = height;
        }
        return box;
    }

    /** The last {@link #boundingBox()} and what it was made from (the position by identity: it is immutable). */
    private Aabb box;
    private Vec3d boxPosition;
    private float boxWidth, boxHeight;

    /** {@code LivingEntity.setSprinting}: the flag and the transient speed modifier. */
    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
        var speed = attributes.get(Attribute.MOVEMENT_SPEED);
        speed.removeModifier(AttributeModifier.SPRINTING.id());
        if (sprinting) {
            speed.addModifier(AttributeModifier.SPRINTING);
        }
    }

    public PlayerState copy() {
        PlayerState copy = new PlayerState(this);
        copy.position = position;
        copy.oldPosition = oldPosition;
        copy.deltaMovement = deltaMovement;
        copy.fixedBodyDimensions = fixedBodyDimensions; copy.bodyWidth = bodyWidth; copy.bodyHeight = bodyHeight;
        copy.standsOnLava=standsOnLava;
        copy.yRot = yRot;
        copy.xRot = xRot;
        copy.onGround = onGround;
        copy.horizontalCollision = horizontalCollision;
        copy.verticalCollision = verticalCollision;
        copy.verticalCollisionBelow = verticalCollisionBelow;
        copy.minorHorizontalCollision = minorHorizontalCollision;
        copy.fallDistance = fallDistance;
        copy.stuckSpeedMultiplier = stuckSpeedMultiplier;
        copy.hasSupportingBlock = hasSupportingBlock;
        copy.supportingX = supportingX;
        copy.supportingY = supportingY;
        copy.supportingZ = supportingZ;
        copy.onGroundNoBlocks = onGroundNoBlocks;
        copy.isInPowderSnow = isInPowderSnow;
        copy.wasInPowderSnow = wasInPowderSnow;
        copy.wasTouchingWater = wasTouchingWater;
        copy.isInLava = isInLava;
        copy.waterHeight = waterHeight; copy.lavaHeight = lavaHeight;
        copy.eyeInWater = eyeInWater; copy.eyeInLava = eyeInLava;
        copy.wasEyeInWater = wasEyeInWater; copy.wasUnderwater = wasUnderwater;
        copy.noPhysics = noPhysics;
        copy.passenger = passenger;
        copy.alive = alive; copy.deadOrDying = deadOrDying; copy.firstTick = firstTick;
        copy.shiftKeyDown = shiftKeyDown;
        copy.sprinting = sprinting;
        copy.swimming = swimming;
        copy.fallFlying = fallFlying;
        copy.noGravity = noGravity;
        copy.dimensionScale = dimensionScale;
        copy.pose = pose;
        copy.noJumpDelay = noJumpDelay;
        copy.jumping = jumping;
        copy.xxa = xxa;
        copy.yya = yya;
        copy.zza = zza;
        copy.sleeping = sleeping;
        copy.sleepingPos = sleepingPos;
        copy.spectator = spectator;
        copy.autoSpinAttack = autoSpinAttack;
        copy.autoSpinAttackTicks = autoSpinAttackTicks;
        copy.autoSpinMayHaveEnded = autoSpinMayHaveEnded;
        copy.riptideUnknownCause = riptideUnknownCause;
        copy.autoSpinAttackDamage = autoSpinAttackDamage;
        copy.wearingLeatherBoots = wearingLeatherBoots;
        copy.movementThisTick.addAll(movementThisTick);
        copy.pistonGameTime = pistonGameTime;
        copy.interpolation = interpolation == null ? null : interpolation.copy();
        return copy;
    }
}
