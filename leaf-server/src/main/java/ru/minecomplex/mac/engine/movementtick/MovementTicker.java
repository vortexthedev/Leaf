package ru.minecomplex.mac.engine.movementtick;

import ru.minecomplex.mac.util.Tri;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.collision.Axis;
import ru.minecomplex.mac.collision.Shapes;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.entity.effect.MobEffect;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.util.collection.LongMap;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTag;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.engine.blockeffects.InsideBlocks;
import ru.minecomplex.mac.engine.blockeffects.MovementBlocks;
import ru.minecomplex.mac.mechanics.collision.EntityCollision;
import ru.minecomplex.mac.mechanics.collision.EntityShapeContext;
import ru.minecomplex.mac.mechanics.fluid.FluidMovement;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.mechanics.movement.FallFlyingMovement;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Port of the movement part of a client-side player tick at the {@code Player} level: {@code Player.aiStep} →
 * {@code LivingEntity.aiStep} → {@code travel} → {@code travelInAir} → {@code Entity.move}, and the block effects that
 * follow ({@code applyEffectsFromBlocks}). Source: 26.3 ({@code ../Client/src/main/java}); method and variable names follow
 * vanilla so each block can be compared with the source. This branch targets only the native 26.3 client.
 *
 * <p>The oracle for this class is {@code tools/datagen} {@code MovementSamples}: vanilla {@code Player.aiStep} in a
 * headless level. {@code LocalPlayer} behaviour (input, sprint rules, auto-jump, minor horizontal collision) belongs
 * to a subclass, like in vanilla.
 *
 * <p>What is not simulated is reported through {@link #unmodeled()} instead of being approximated.
 */
public class MovementTicker {

    protected final MovementEnvironment env;

    protected final BlockRegistry registry;

    protected final MovementBlocks blocks;

    protected final PlayerState p;

    protected final FluidWorld fluids;

    protected ru.minecomplex.mac.world.BlockDirection pistonNoClip;

    private final EnumSet<Unmodeled> unmodeled = EnumSet.noneOf(Unmodeled.class);

    /**
     * {@code Entity.checkInsideBlocks}' visited blocks; created on first use (most tickers never walk blocks).
     */
    private LongMap<Boolean> visitedBlocks;

    /**
     * The block walk's own visited set ({@code InsideBlocks}), reused between walks.
     */
    private LongMap<Boolean> walkVisited;

    public MovementTicker(MovementEnvironment env, PlayerState state) {
        this(env, state, null);
    }

    /**
     * Resolved non-player eye/current facts for bodies sharing Entity.move and LivingEntity travel.
     */
    protected MovementTicker(MovementEnvironment env, PlayerState state, FluidWorld.EntityFacts entityFacts) {
        this.env = env;
        this.registry = env.registry();
        this.blocks = MovementBlocks.of(registry);
        this.p = state;
        this.fluids = new FluidWorld(env, this::unmodeled, entityFacts);
        if (env.blocks() instanceof CompensatedWorld world && world.dynamicApplicationUnresolved()) {
            unmodeled(Unmodeled.DYNAMIC_BLOCK_APPLICATION);
        }
        if (env.blocks() instanceof CompensatedWorld world)
            world.dynamicGaps().forEach(this::unmodeled);
    }

    public PlayerState state() {
        return p;
    }

    /**
     * Situations met since the last {@link #clearUnmodeled()} that this port does not simulate.
     */
    public Set<Unmodeled> unmodeled() {
        return unmodeled;
    }

    public void clearUnmodeled() {
        unmodeled.clear();
        if (env.blocks() instanceof CompensatedWorld world && world.dynamicApplicationUnresolved()) {
            unmodeled(Unmodeled.DYNAMIC_BLOCK_APPLICATION);
        }
        if (env.blocks() instanceof CompensatedWorld world)
            world.dynamicGaps().forEach(this::unmodeled);
    }

    protected final void unmodeled(Unmodeled reason) {
        unmodeled.add(reason);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Player.aiStep / LivingEntity.aiStep
    /**
     * {@code Player.aiStep}. {@code xxa}, {@code zza} and {@code jumping} must hold what {@code applyInput} left in
     * them (for {@code LocalPlayer}: the modified move vector and the jump key).
     */
    public void aiStep() {
        // jumpTriggerTime, regeneration, inventory tick: no movement effect
        if (p.abilities.flying && !p.passenger) {
            resetFallDistance();
        }
        livingAiStep();
        // yHeadRot, setSpeed (Player.getSpeed reads the attribute), item pickup, shoulder entities: no movement effect
    }

    /**
     * {@code LivingEntity.aiStep}.
     */
    protected void livingAiStep() {
        if (p.noJumpDelay > 0) {
            p.noJumpDelay--;
        }
        Vec3d movement = p.deltaMovement;
        double dx = movement.x();
        double dy = movement.y();
        double dz = movement.z();
        if (playerMotionThreshold()) {
            if (movement.horizontalDistanceSqr() < 9.0E-6) {
                dx = 0.0;
                dz = 0.0;
            }
        } else {
            if (Math.abs(movement.x()) < 0.003) {
                dx = 0.0;
            }
            if (Math.abs(movement.z()) < 0.003) {
                dz = 0.0;
            }
        }
        if (Math.abs(movement.y()) < 0.003) {
            dy = 0.0;
        }
        p.deltaMovement = new Vec3d(dx, dy, dz);
        applyInput();
        if (isImmobile()) {
            p.jumping = false;
            p.xxa = 0.0F;
            p.zza = 0.0F;
        }
        // Legacy LocalPlayer.serverAiStep input is represented by the hook above; newer server AI is skipped.
        if (p.jumping && !p.abilities.flying) {
            // Player.isAffectedByFluids() is false during creative flight.
            double fluidHeight = p.isInLava ? p.lavaHeight : p.waterHeight;
            boolean water = p.wasTouchingWater && fluidHeight > 0.0;
            double threshold = fluids.jumpThreshold(p);
            if (!water || p.onGround && !(fluidHeight > threshold)) {
                if (!p.isInLava || p.onGround && !(fluidHeight > threshold)) {
                    if ((p.onGround || water && fluidHeight <= threshold) && p.noJumpDelay == 0) {
                        jumpFromGround();
                        p.noJumpDelay = 10;
                    }
                } else
                    jumpInLiquid();
            } else
                jumpInLiquid();
        } else {
            p.noJumpDelay = 0;
        }
        if (p.fallFlying) {
            // Client updateFallFlying only clamps accumulated fall distance; equipment checks are server-side.
            if (p.deltaMovement.y() > -0.5 && p.fallDistance > 1.0)
                p.fallDistance = 1.0;
        }
        Aabb boxBeforeTravel = p.boundingBox();
        Vec3d input = new Vec3d(p.xxa, p.yya, p.zza);
        if (p.effects.has(MobEffect.SLOW_FALLING) || p.effects.has(MobEffect.LEVITATION)) {
            resetFallDistance();
        }
        if (p.passenger && !mountedTravelResolved()) {
            unmodeled(Unmodeled.RIDING);
        } else {
            // canSimulateMovement() && isEffectiveAi(): true for the local player
            travel(input);
        }
        // !isClientSide() || isLocalInstanceAuthoritative()
        applyEffectsFromBlocks();
        // calculateEntityAnimation: visual; freezing damage: server
        if (p.autoSpinAttackTicks > 0) {
            p.autoSpinAttackTicks--;
            checkAutoSpinAttack(boxBeforeTravel.minmax(p.boundingBox()));
        }
    }

    /**
     * {@code LivingEntity.checkAutoSpinAttack(old, current)} over the swept box: the first living entity
     * {@code level.getEntities(this, minmax)} lists is attacked ({@code Player.attack}: nothing on the client but against
     * another player, see {@link MovementEnvironment#livingIn}), the spin ends and the velocity turns ×−0.2; with no
     * entity at all a horizontal collision ends the spin. A spin that may have ended, or a touch not decided, leaves the
     * velocity unknown ({@link Unmodeled#RIPTIDE}).
     */
    private void checkAutoSpinAttack(Aabb swept) {
        var living = env.livingIn(swept);
        if (living == ru.minecomplex.mac.util.Tri.TRUE && !p.autoSpinMayHaveEnded) {
            p.autoSpinAttackTicks = 0;
            p.deltaMovement = p.deltaMovement.scale(-0.2);
        } else if (living != ru.minecomplex.mac.util.Tri.FALSE) {
            p.riptideUnknownCause = p.autoSpinMayHaveEnded ? "контакт с живой сущностью после возможного окончания рывка" : "неизвестен контакт рывка с живой сущностью";
            unmodeled(Unmodeled.RIPTIDE);
            p.autoSpinMayHaveEnded = true;
        } else if (p.horizontalCollision) {
            // an entity the model does not follow (an item, an arrow …) keeps the spin going
            p.autoSpinMayHaveEnded = true;
        }
        if (p.autoSpinAttackTicks == 0)
            p.autoSpinMayHaveEnded = false;
    }

    /**
     * The local player's own interpolation step ({@code getInterpolation().interpolate()}, 1.21.4
     * {@code lerpPositionAndRotationStep}): towards a {@code teleport_entity} target, adjusted by its own displacement where
     * that is free ({@code level.noCollision(this, box)}). In {@code LivingEntity.aiStep} through 26.2, in
     * {@code Entity.commonTick} from 26.3 (where an inactive stepped handler resets). Bodies without one do nothing.
     */
    /**
     * 26.3 {@code Entity.commonTick}'s interpolation step, before {@code tick}; earlier releases step in {@code aiStep}.
     */
    public void commonTickInterpolation() {
        interpolateSelf();
    }

    protected void interpolateSelf() {
        if (p.interpolation != null)
            p.interpolation.tick(p, this::noCollision);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Player pose
    /**
     * Vanilla Player.canPlayerFitWithinBlocksAndEntitiesWhen.
     */
    public boolean canFit(Pose requestedPose) {
        Aabb box = p.makeBoundingBox(requestedPose, p.position);
        return noCollision(new Aabb(box.minX() + 1.0E-7, box.minY() + 1.0E-7, box.minZ() + 1.0E-7, box.maxX() - 1.0E-7, box.maxY() - 1.0E-7, box.maxZ() - 1.0E-7));
    }

    /**
     * Vanilla Player.updatePlayerPose; {@code setPose} refreshes the dimensions only when the pose changes.
     */
    public void updatePlayerPose() {
        if (canFit(Pose.SWIMMING)) {
            Pose desired = desiredPose();
            Pose actual;
            if (p.spectator || p.passenger || canFit(desired)) {
                actual = desired;
            } else if (canFit(Pose.CROUCHING)) {
                actual = Pose.CROUCHING;
            } else {
                actual = Pose.SWIMMING;
            }
            if (actual != p.pose) {
                p.pose = actual;
                p.refreshDimensions();
            }
        }
    }

    private Pose desiredPose() {
        if (p.sleeping)
            return Pose.SLEEPING;
        if (p.swimming)
            return Pose.SWIMMING;
        if (p.fallFlying)
            return Pose.FALL_FLYING;
        if (p.autoSpinAttack)
            return Pose.SPIN_ATTACK;
        return p.shiftKeyDown && !p.abilities.flying ? Pose.CROUCHING : Pose.STANDING;
    }

    /**
     * {@code LivingEntity.isPushable()} on a client: alive, not a spectator, not on a climbable block.
     */
    public boolean isPushable() {
        return p.alive && !p.spectator && !onClimbable();
    }

    /**
     * {@code Player.isImmobile}: {@code isDeadOrDying() || isSleeping()}.
     */
    protected boolean isImmobile() {
        return p.deadOrDying || p.sleeping;
    }

    /**
     * Base Player oracle supplies input directly; LocalPlayer refreshes it in this vanilla phase.
     */
    protected void applyInput() {
    }

    /**
     * LivingEntity applies the horizontal-length cutoff only to players.
     */
    protected boolean playerMotionThreshold() {
        return true;
    }

    /**
     * A LocalPlayer ride tick may resolve the passenger path from the same client hypothesis.
     */
    protected boolean mountedTravelResolved() {
        return false;
    }

    /**
     * Fall-damage movement rechecks must use the same vehicle fluid facts as baseTick.
     */
    protected void refreshFluidContact(boolean eyes) {
        fluids.refresh(p, eyes);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Travel
    /**
     * {@code Player.travel}.
     */
    protected void travel(Vec3d input) {
        if (p.passenger) {
            if (mountedTravelResolved())
                livingTravel(input);
            else
                unmodeled(Unmodeled.RIDING);
            return;
        }
        if (p.swimming) {
            double lookY = -Trig.sin(p.xRot * (float) (Math.PI / 180.0));
            double multiplier = lookY < -0.2 ? 0.085 : 0.06;
            if (lookY <= 0.0 || p.jumping || fluids.kind(VanillaMath.floor(p.x()), VanillaMath.floor(p.y() + 1.0 - 0.1), VanillaMath.floor(p.z())) != 0) {
                p.deltaMovement = p.deltaMovement.add(0.0, (lookY - p.deltaMovement.y()) * multiplier, 0.0);
            }
        }
        if (p.abilities.flying) {
            double originalMovementY = p.deltaMovement.y();
            livingTravel(input);
            p.deltaMovement = p.deltaMovement.with(Axis.Y, originalMovementY * 0.6);
        } else {
            livingTravel(input);
        }
    }

    /**
     * {@code LivingEntity.travel}.
     */
    protected void livingTravel(Vec3d input) {
        if ((p.wasTouchingWater || p.isInLava) && !p.abilities.flying) {
            if (!env.fluidsResolved()) {
                unmodeled(Unmodeled.FLUID);
                travelInAir(input);
                return;
            }
            travelInFluid(input);
            return;
        }
        if (p.fallFlying) {
            travelFallFlying(input);
            return;
        }
        travelInAir(input);
    }

    /**
     * LivingEntity.travelInFluid; its 1.21.11 split preserves the player's arithmetic.
     */
    protected void travelInFluid(Vec3d input) {
        boolean falling = p.deltaMovement.y() <= 0.0;
        double oldY = p.y(), gravity = getEffectiveGravity();
        if (p.wasTouchingWater) {
            float slowDown = p.sprinting ? 0.9F : getWaterSlowDown(), speed = 0.02F;
            float efficiency = (float) p.attributes.value(Attribute.WATER_MOVEMENT_EFFICIENCY);
            if (!p.onGround)
                efficiency *= 0.5F;
            if (efficiency > 0.0F) {
                slowDown += (0.54600006F - slowDown) * efficiency;
                speed += (getSpeed() - speed) * efficiency;
            }
            if (p.effects.has(MobEffect.DOLPHINS_GRACE))
                slowDown = 0.96F;
            moveRelative(speed, input);
            move(MoverType.SELF, p.deltaMovement);
            Vec3d movement = p.deltaMovement;
            if (p.horizontalCollision && onClimbable())
                movement = new Vec3d(movement.x(), 0.2, movement.z());
            movement = movement.multiply(slowDown, 0.8F, slowDown);
            p.deltaMovement = FluidMovement.fallingAdjusted(movement, gravity, falling, p.sprinting);
        } else {
            moveRelative(0.02F, input);
            move(MoverType.SELF, p.deltaMovement);
            if (p.lavaHeight <= fluids.jumpThreshold(p)) {
                p.deltaMovement = FluidMovement.fallingAdjusted(p.deltaMovement.multiply(0.5, 0.8F, 0.5), gravity, falling, p.sprinting);
            } else
                p.deltaMovement = p.deltaMovement.scale(0.5);
            if (gravity != 0.0)
                p.deltaMovement = p.deltaMovement.add(0.0, -gravity / 4.0, 0.0);
        }
        Vec3d movement = p.deltaMovement;
        if (p.horizontalCollision && isFree(movement.x(), movement.y() + 0.6F - p.y() + oldY, movement.z()))
            p.deltaMovement = new Vec3d(movement.x(), 0.3F, movement.z());
    }

    private boolean isFree(double x, double y, double z) {
        Aabb box = p.boundingBox().move(x, y, z);
        VoxelShape shape = Shapes.create(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        VoxelShape border = env.borderShape(box);
        if (border != null) {
            if (Shapes.intersect(border, shape))
                return false;
        }
        for (var entity : env.entityCollisions(box)) if (Shapes.intersect(entity, shape))
            return false;
        boolean[] hit = { false };
        BlockCollisions.collect(env.blocks(), registry, shapeContext(), box, (bx, by, bz, block) -> {
            if (Shapes.intersect(block, shape))
                hit[0] = true;
        });
        if (hit[0])
            return false;
        for (int bx = VanillaMath.floor(box.minX()); bx < Math.ceil(box.maxX()); bx++) for (int by = VanillaMath.floor(box.minY()); by < Math.ceil(box.maxY()); by++) for (int bz = VanillaMath.floor(box.minZ()); bz < Math.ceil(box.maxZ()); bz++) if (fluids.kind(bx, by, bz) != 0)
            return false;
        return true;
    }

    /**
     * LivingEntity.travelFallFlying: client does not perform the server's wall-impact damage check.
     */
    protected void travelFallFlying(Vec3d input) {
        if (onClimbable()) {
            travelInAir(input);
            p.fallFlying = false;
        } else {
            p.deltaMovement = new FallFlyingMovement().update(p.deltaMovement, p.yRot, p.xRot, getEffectiveGravity());
            move(MoverType.SELF, p.deltaMovement);
        }
    }

    /**
     * {@code LivingEntity.travelInAir}.
     */
    protected void travelInAir(Vec3d input) {
        BlockPos posBelow = getBlockPosBelowThatAffectsMyMovement();
        float blockFriction = p.onGround ? computeModifiedFriction(registry.block(getBlockState(posBelow)).friction(), (float) p.attributes.value(Attribute.FRICTION_MODIFIER)) : 1.0F;
        Vec3d movement = handleRelativeFrictionAndCalculateMovement(input, blockFriction);
        double movementY = movement.y();
        if (p.effects.has(MobEffect.LEVITATION)) {
            movementY += (0.05 * (p.effects.amplifier(MobEffect.LEVITATION) + 1) - movement.y()) * 0.2;
        } else if (env.hasChunkAt(posBelow.x(), posBelow.z())) {
            movementY -= getEffectiveGravity();
        } else if (p.y() > env.minY()) {
            movementY = -0.1;
        } else {
            movementY = 0.0;
        }
        // shouldDiscardFriction() is false for players; omnidirectionalAirMover() too
        float entityAirDragModifier = (float) p.attributes.value(Attribute.AIR_DRAG_MODIFIER);
        float airDrag = computeModifiedFriction(0.91F, entityAirDragModifier);
        float friction = blockFriction * airDrag;
        float verticalFriction = computeModifiedFriction(0.98F, entityAirDragModifier);
        p.deltaMovement = new Vec3d(movement.x() * friction, movementY * verticalFriction, movement.z() * friction);
    }

    /**
     * {@code LivingEntity.computeModifiedFriction}.
     */
    static float computeModifiedFriction(float friction, float modifier) {
        return VanillaMath.clamp(1.0F - (1.0F - friction) * modifier, 0.0F, 1.0F);
    }

    /**
     * {@code LivingEntity.handleRelativeFrictionAndCalculateMovement}.
     */
    private Vec3d handleRelativeFrictionAndCalculateMovement(Vec3d input, float friction) {
        moveRelative(getFrictionInfluencedSpeed(friction), input);
        p.deltaMovement = handleOnClimbable(p.deltaMovement);
        move(MoverType.SELF, p.deltaMovement);
        Vec3d movement = p.deltaMovement;
        boolean inPowderSnow = p.wasInPowderSnow;
        if ((p.horizontalCollision || p.jumping) && (onClimbable() || inPowderSnow && canEntityWalkOnPowderSnow(p))) {
            movement = new Vec3d(movement.x(), 0.2, movement.z());
        }
        return movement;
    }

    /**
     * {@code LivingEntity.getFrictionInfluencedSpeed}.
     */
    private float getFrictionInfluencedSpeed(float blockFriction) {
        if (p.onGround) {
            return blockFriction > 0.6 ? getSpeed() * (0.21600002F / (blockFriction * blockFriction * blockFriction)) : getSpeed();
        }
        return getFlyingSpeed();
    }

    /**
     * {@code LivingEntity.getWaterSlowDown}, overridden by swimming SkeletonHorse.
     */
    protected float getWaterSlowDown() {
        return 0.8F;
    }

    /**
     * {@code Player.getSpeed}: the movement speed attribute.
     */
    protected float getSpeed() {
        return (float) p.attributes.value(Attribute.MOVEMENT_SPEED);
    }

    /**
     * {@code Player.getFlyingSpeed}.
     */
    protected float getFlyingSpeed() {
        if (p.abilities.flying && !p.passenger) {
            return p.sprinting ? p.abilities.flyingSpeed * 2.0F : p.abilities.flyingSpeed;
        }
        return p.sprinting ? 0.025999999F : 0.02F;
    }

    /**
     * {@code Entity.moveRelative}.
     */
    protected void moveRelative(float speed, Vec3d input) {
        p.deltaMovement = p.deltaMovement.add(getInputVector(input, speed, p.yRot));
    }

    /**
     * {@code Entity.getInputVector}.
     */
    protected Vec3d getInputVector(Vec3d input, float speed, float yRot) {
        double length = input.lengthSqr();
        if (length < 1.0E-7) {
            return Vec3d.ZERO;
        }
        Vec3d movement = (length > 1.0 ? input.normalize() : input).scale(speed);
        float sin = Trig.sin(yRot * (float) (Math.PI / 180.0));
        float cos = Trig.cos(yRot * (float) (Math.PI / 180.0));
        return new Vec3d(movement.x() * cos - movement.z() * sin, movement.y(), movement.z() * cos + movement.x() * sin);
    }

    /**
     * {@code LivingEntity.handleOnClimbable}.
     */
    private Vec3d handleOnClimbable(Vec3d delta) {
        if (onClimbable()) {
            resetFallDistance();
            double xd = VanillaMath.clamp(delta.x(), -0.15F, 0.15F);
            double zd = VanillaMath.clamp(delta.z(), -0.15F, 0.15F);
            double yd = Math.max(delta.y(), -0.15F);
            // LocalPlayer additionally suppresses this rule while abilities.flying.
            if (yd < 0.0 && !blocks.isScaffolding(getInBlockState()) && isSuppressingSlidingDownLadder()) {
                yd = 0.0;
            }
            delta = new Vec3d(xd, yd, zd);
        }
        return delta;
    }

    protected boolean isSuppressingSlidingDownLadder() {
        return p.shiftKeyDown;
    }

    /**
     * Player's flying gate followed by LivingEntity's spectator, tag and trapdoor checks.
     */
    protected boolean onClimbable() {
        if (p.spectator || p.abilities.flying)
            return false;
        BlockPos ladderCheckPos = blockPosition();
        int state = getInBlockState();
        if (p.fallFlying && is(state, BlockTag.CAN_GLIDE_THROUGH)) {
            return false;
        }
        if (is(state, BlockTag.CLIMBABLE)) {
            return true;
        }
        return blocks.isTrapdoor(state) && trapdoorUsableAsLadder(ladderCheckPos, state);
    }

    /**
     * {@code LivingEntity.trapdoorUsableAsLadder}.
     */
    private boolean trapdoorUsableAsLadder(BlockPos pos, int state) {
        if (!blocks.isOpen(state)) {
            return false;
        }
        int below = getBlockState(new BlockPos(pos.x(), pos.y() - 1, pos.z()));
        return blocks.isLadder(below) && blocks.facing(below).equals(blocks.facing(state));
    }

    /**
     * {@code LivingEntity.jumpFromGround}.
     */
    protected void jumpFromGround() {
        float jumpPower = getJumpPower();
        if (!(jumpPower <= 1.0E-5F)) {
            Vec3d movement = p.deltaMovement;
            p.deltaMovement = new Vec3d(movement.x(), Math.max(jumpPower, movement.y()), movement.z());
            if (p.sprinting) {
                float angle = p.yRot * (float) (Math.PI / 180.0);
                p.deltaMovement = p.deltaMovement.add(-Trig.sin(angle) * 0.2, 0.0, Trig.cos(angle) * 0.2);
            }
        }
    }

    /**
     * {@code LivingEntity.jumpInLiquid}; Mob overrides this using its navigation's canFloat flag.
     */
    protected void jumpInLiquid() {
        p.deltaMovement = FluidMovement.jump(p.deltaMovement);
    }

    /**
     * {@code LivingEntity.getJumpPower(1.0F)}.
     */
    protected float getJumpPower() {
        return (float) p.attributes.value(Attribute.JUMP_STRENGTH) * 1.0F * getBlockJumpFactor() + getJumpBoostPower();
    }

    /**
     * {@code LivingEntity.getJumpBoostPower}.
     */
    protected float getJumpBoostPower() {
        return p.effects.has(MobEffect.JUMP_BOOST) ? 0.1F * (p.effects.amplifier(MobEffect.JUMP_BOOST) + 1.0F) : 0.0F;
    }

    /**
     * {@code LivingEntity.getEffectiveGravity}.
     */
    protected double getEffectiveGravity() {
        boolean isFalling = p.deltaMovement.y() <= 0.0;
        return isFalling && p.effects.has(MobEffect.SLOW_FALLING) ? Math.min(getGravity(), 0.01) : getGravity();
    }

    /**
     * {@code Entity.getGravity}: {@code isNoGravity() ? 0.0 : getDefaultGravity()} (the gravity attribute).
     */
    private double getGravity() {
        return p.noGravity ? 0.0 : p.attributes.value(Attribute.GRAVITY);
    }

    /**
     * {@code LivingEntity.getAirDrag}.
     */
    protected float getAirDrag() {
        return computeModifiedFriction(0.98F, (float) p.attributes.value(Attribute.AIR_DRAG_MODIFIER));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Entity.move
    enum MoverType {

        SELF, PLAYER, PISTON, SHULKER_BOX, SHULKER
    }

    /**
     * {@code Entity.move}.
     */
    protected void move(MoverType moverType, Vec3d delta) {
        if (p.noPhysics) {
            p.position = p.position.add(delta);
            {
                p.horizontalCollision = false;
                p.verticalCollision = false;
                p.verticalCollisionBelow = false;
                p.minorHorizontalCollision = false;
            }
            return;
        }
        if (moverType == MoverType.PISTON) {
            delta = p.pistonMovement.limit(delta, p.pistonGameTime);
            if (delta.equals(Vec3d.ZERO))
                return;
        }
        if (p.stuckSpeedMultiplier.lengthSqr() > 1.0E-7) {
            if (moverType != MoverType.PISTON) {
                delta = delta.multiply(p.stuckSpeedMultiplier);
            }
            p.stuckSpeedMultiplier = Vec3d.ZERO;
            p.deltaMovement = Vec3d.ZERO;
        }
        delta = maybeBackOffFromEdge(delta, moverType);
        Vec3d movement = collide(delta);
        double movementLength = movement.lengthSqr();
        if (movementLength > 1.0E-7 || delta.lengthSqr() - movementLength < 1.0E-7) {
            if (p.fallDistance != 0.0 && movementLength >= 1.0) {
                Vec3d checkTo;
                {
                    double checkDistance = Math.min(movement.length(), 8.0);
                    checkTo = p.position.add(movement.normalize().scale(checkDistance));
                }
                if (fallResetClipHits(p.position, checkTo)) {
                    resetFallDistance();
                }
            }
            Vec3d pos = p.position;
            Vec3d newPosition = pos.add(movement);
            // the move before collision orders the axis split of the block walk
            addMovementThisTick(new PlayerState.Movement(pos, newPosition, delta));
            p.position = newPosition;
        }
        boolean xCollision = !VanillaMath.equal(delta.x(), movement.x());
        boolean zCollision = !VanillaMath.equal(delta.z(), movement.z());
        p.horizontalCollision = xCollision || zCollision;
        boolean movedVertically = Math.abs(delta.y()) > 0.0;
        // || isLocalInstanceAuthoritative(): always true for the local player
        p.verticalCollision = delta.y() != movement.y();
        p.verticalCollisionBelow = p.verticalCollision && delta.y() < 0.0;
        setOnGroundWithMovement(p.verticalCollisionBelow, p.horizontalCollision, movement);
        p.minorHorizontalCollision = p.horizontalCollision && isHorizontalCollisionMinor(movement);
        BlockPos effectPos = getOnPosLegacy();
        int effectState = getBlockState(effectPos);
        if (tracksClientFallDamage()) {
            checkFallDamage(movement.y(), p.onGround, effectState, effectPos);
        }
        {
            if (movedVertically && p.verticalCollision || p.horizontalCollision) {
                restituteMovementAfterCollisions(effectState, xCollision, zCollision, movement);
            }
        }
        // movement emission: sounds and game events only
        float blockSpeedFactor = getBlockSpeedFactor();
        p.deltaMovement = p.deltaMovement.multiply(blockSpeedFactor, 1.0, blockSpeedFactor);
    }

    /**
     * {@code Entity.addMovementThisTick}: at most 100 entries, the first two merge when full.
     */
    private void addMovementThisTick(PlayerState.Movement movement) {
        List<PlayerState.Movement> list = p.movementThisTick;
        if (list.size() >= 100) {
            PlayerState.Movement first = list.removeFirst();
            PlayerState.Movement second = list.removeFirst();
            list.addFirst(new PlayerState.Movement(first.from(), second.to(), null));
        }
        list.add(movement);
    }

    /**
     * {@code Entity.isHorizontalCollisionMinor}; {@code LocalPlayer} overrides it.
     */
    protected boolean isHorizontalCollisionMinor(Vec3d movement) {
        return false;
    }

    /**
     * {@code Player.maybeBackOffFromEdge}.
     */
    protected Vec3d maybeBackOffFromEdge(Vec3d delta, MoverType moverType) {
        float maxDownStep = maxUpStep();
        if (!p.abilities.flying && !(delta.y() > 0.0) && (moverType == MoverType.SELF || moverType == MoverType.PLAYER) && p.shiftKeyDown && isAboveGround(maxDownStep)) {
            double deltaX = delta.x();
            double deltaZ = delta.z();
            double stepX = Math.signum(deltaX) * 0.05;
            double stepZ = Math.signum(deltaZ) * 0.05;
            while (deltaX != 0.0 && canFallAtLeast(deltaX, 0.0, maxDownStep)) {
                if (Math.abs(deltaX) <= 0.05) {
                    deltaX = 0.0;
                    break;
                }
                deltaX -= stepX;
            }
            while (deltaZ != 0.0 && canFallAtLeast(0.0, deltaZ, maxDownStep)) {
                if (Math.abs(deltaZ) <= 0.05) {
                    deltaZ = 0.0;
                    break;
                }
                deltaZ -= stepZ;
            }
            while (deltaX != 0.0 && deltaZ != 0.0 && canFallAtLeast(deltaX, deltaZ, maxDownStep)) {
                if (Math.abs(deltaX) <= 0.05) {
                    deltaX = 0.0;
                } else {
                    deltaX -= stepX;
                }
                if (Math.abs(deltaZ) <= 0.05) {
                    deltaZ = 0.0;
                } else {
                    deltaZ -= stepZ;
                }
            }
            return new Vec3d(deltaX, delta.y(), deltaZ);
        }
        return delta;
    }

    /**
     * {@code Player.isAboveGround}; the fall distance is a float before 1.21.5.
     */
    private boolean isAboveGround(float maxDownStep) {
        return p.onGround || p.fallDistance < maxDownStep && !canFallAtLeast(0.0, 0.0, maxDownStep - p.fallDistance);
    }

    /**
     * {@code Player.canFallAtLeast}: from 1.21.5 the box shrinks by 1e-7 sideways and reaches 1e-7 deeper.
     */
    private boolean canFallAtLeast(double deltaX, double deltaZ, double minHeight) {
        Aabb box = p.boundingBox();
        return noCollision(new Aabb(box.minX() + 1.0E-7 + deltaX, box.minY() - minHeight - 1.0E-7, box.minZ() + 1.0E-7 + deltaZ, box.maxX() - 1.0E-7 + deltaX, box.minY(), box.maxZ() - 1.0E-7 + deltaZ));
    }

    /**
     * {@code LivingEntity.maxUpStep}: the step height attribute (the player controls no vehicle here).
     */
    protected float maxUpStep() {
        return (float) p.attributes.value(Attribute.STEP_HEIGHT);
    }

    /**
     * {@code CollisionGetter.noCollision(entity, box)}: blocks, entities, world border.
     */
    protected final boolean noCollision(Aabb box) {
        boolean[] hit = { false };
        BlockCollisions.collect(env.blocks(), registry, shapeContext(), box, (x, y, z, shape) -> hit[0] = true);
        if (hit[0]) {
            return false;
        }
        for (VoxelShape shape : env.entityCollisions(box)) {
            if (!shape.isEmpty()) {
                return false;
            }
        }
        VoxelShape border = env.borderShape(box);
        return border == null || !Shapes.intersect(border, Shapes.create(box));
    }

    /**
     * {@code Entity.collide}.
     */
    private Vec3d collide(Vec3d movement) {
        return EntityCollision.collide(collisionEnvironment(), p.boundingBox(), movement, p.onGround, maxUpStep());
    }

    private EntityCollision.Environment collisionEnvironment() {
        EntityShapeContext shapes = shapeContext();
        return new EntityCollision.Environment() {

            @Override
            public BlockSource blocks() {
                return env.blocks();
            }

            @Override
            public BlockRegistry registry() {
                return registry;
            }

            @Override
            public BlockCollisions.ShapeContext shapes() {
                return shapes;
            }

            @Override
            public List<VoxelShape> entityCollisions(Aabb box) {
                return env.entityCollisions(box);
            }

            @Override
            public VoxelShape borderShape(Aabb box) {
                return env.borderShape(box);
            }
        };
    }

    /**
     * {@code CollisionContext.of(this)}, taken now.
     */
    protected final EntityShapeContext shapeContext() {
        return new EntityShapeContext(blocks, p, this::unmodeled, env.blocks() instanceof CompensatedWorld world ? world : null, pistonNoClip, env.blocks());
    }

    /**
     * {@code Entity.setOnGroundWithMovement}.
     */
    private void setOnGroundWithMovement(boolean onGround, boolean horizontalCollision, Vec3d movement) {
        p.onGround = onGround;
        p.horizontalCollision = horizontalCollision;
        checkSupportingBlock(onGround, movement);
    }

    /**
     * {@code Entity.checkSupportingBlock}.
     */
    protected final void setOnGround(boolean onGround) {
        p.onGround = onGround;
        checkSupportingBlock(onGround, null);
    }

    private void checkSupportingBlock(boolean onGround, Vec3d movement) {
        if (onGround) {
            Aabb box = p.boundingBox();
            Aabb testArea = new Aabb(box.minX(), box.minY() - 1.0E-6, box.minZ(), box.maxX(), box.minY(), box.maxZ());
            BlockPos supportingBlock = findSupportingBlock(testArea);
            if (supportingBlock != null || p.onGroundNoBlocks) {
                setSupportingBlock(supportingBlock);
            } else if (movement != null) {
                supportingBlock = findSupportingBlock(testArea.move(-movement.x(), 0.0, -movement.z()));
                setSupportingBlock(supportingBlock);
            }
            p.onGroundNoBlocks = supportingBlock == null;
        } else {
            p.onGroundNoBlocks = false;
            p.hasSupportingBlock = false;
        }
    }

    private void setSupportingBlock(BlockPos pos) {
        p.hasSupportingBlock = pos != null;
        if (pos != null) {
            p.supportingX = pos.x();
            p.supportingY = pos.y();
            p.supportingZ = pos.z();
        }
    }

    /**
     * {@code CollisionGetter.findSupportingBlock}: the colliding block closest to the entity position, ties to the
     * greatest position in {@code Vec3i.compareTo} order (y, then z, then x).
     */
    private BlockPos findSupportingBlock(Aabb box) {
        int[] best = new int[3];
        double[] bestDistance = { Double.MAX_VALUE };
        boolean[] found = { false };
        Vec3d position = p.position;
        BlockCollisions.collect(env.blocks(), registry, shapeContext(), box, (x, y, z, shape) -> {
            double dx = x + 0.5 - position.x();
            double dy = y + 0.5 - position.y();
            double dz = z + 0.5 - position.z();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance[0] || distance == bestDistance[0] && (!found[0] || compare(best[0], best[1], best[2], x, y, z) < 0)) {
                best[0] = x;
                best[1] = y;
                best[2] = z;
                bestDistance[0] = distance;
                found[0] = true;
            }
        });
        return found[0] ? new BlockPos(best[0], best[1], best[2]) : null;
    }

    /**
     * {@code Vec3i.compareTo}.
     */
    private static int compare(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (y1 == y2) {
            return z1 == z2 ? x1 - x2 : z1 - z2;
        }
        return y1 - y2;
    }

    /**
     * {@code LivingEntity.checkFallDamage} + {@code Entity.checkFallDamage} (client side).
     */
    protected boolean tracksClientFallDamage() {
        return true;
    }

    protected void checkFallDamage(double ya, boolean onGround, int onState, BlockPos pos) {
        if (!p.wasTouchingWater) {
            refreshFluidContact(false);
        }
        if (!p.wasTouchingWater && ya < 0.0) {
            p.fallDistance -= (float) ya;
        }
        if (onGround) {
            // fallOn → causeFallDamage: damage and sounds only
            resetFallDistance();
        }
    }

    /**
     * {@code Entity.restituteMovementAfterCollisions} (26.2+): bounces off colliding blocks by the bounciness
     * attribute and the block's restitution; without bounce the colliding components become (signed) zero.
     */
    private void restituteMovementAfterCollisions(int effectState, boolean xCollision, boolean zCollision, Vec3d movement) {
        double restitution = p.shiftKeyDown ? 0.0 : p.attributes.value(Attribute.BOUNCINESS);
        Vec3d currentMovement = p.deltaMovement;
        Vec3d movementAfterBounce = currentMovement;
        if (xCollision) {
            movementAfterBounce = movementAfterBounce.with(Axis.X, -currentMovement.x() * restitution);
        }
        if (zCollision) {
            movementAfterBounce = movementAfterBounce.with(Axis.Z, -currentMovement.z() * restitution);
        }
        if (p.verticalCollision) {
            if (p.verticalCollisionBelow) {
                boolean belowThreshold = -currentMovement.y() <= getEffectiveGravity();
                restitution = !belowThreshold && !p.shiftKeyDown && !is(effectState, BlockTag.SUPPRESSES_BOUNCE) ? Math.max(restitution, blockBounceRestitution(effectState)) : 0.0;
            }
            double gravityCompensation;
            double effectiveDrag;
            if (restitution > 0.0) {
                double portionWithMovement = movement.y() / currentMovement.y();
                gravityCompensation = portionWithMovement * getEffectiveGravity();
                effectiveDrag = VanillaMath.lerp(portionWithMovement, 1.0, getAirDrag());
            } else {
                gravityCompensation = 0.0;
                effectiveDrag = 1.0;
            }
            movementAfterBounce = movementAfterBounce.with(Axis.Y, (gravityCompensation - currentMovement.y()) * effectiveDrag * restitution);
        }
        // bounceOn: only ShelfMushroomBlock overrides it (sound); game event and position sync: server-facing
        p.deltaMovement = movementAfterBounce;
    }

    protected double blockBounceRestitution(int state) {
        return registry.block(state).bounceRestitution();
    }

    protected float getBlockSpeedFactor() {
        if (p.abilities.flying || p.fallFlying) {
            return 1.0F;
        }
        return VanillaMath.lerp((float) p.attributes.value(Attribute.MOVEMENT_EFFICIENCY), entityBlockSpeedFactor(), 1.0F);
    }

    /**
     * {@code Entity.getBlockSpeedFactor}.
     */
    protected final float entityBlockSpeedFactor() {
        int state = getBlockState(blockPosition());
        float speedFactorHere = registry.block(state).speedFactor();
        String name = registry.block(state).name();
        if (!name.equals("minecraft:water") && !name.equals("minecraft:bubble_column")) {
            return speedFactorHere == 1.0 ? registry.block(getBlockState(getBlockPosBelowThatAffectsMyMovement())).speedFactor() : speedFactorHere;
        }
        return speedFactorHere;
    }

    /**
     * {@code Entity.getBlockJumpFactor}.
     */
    protected float getBlockJumpFactor() {
        float jumpFactorHere = registry.block(getBlockState(blockPosition())).jumpFactor();
        float jumpFactorBelow = registry.block(getBlockState(getBlockPosBelowThatAffectsMyMovement())).jumpFactor();
        return jumpFactorHere == 1.0 ? jumpFactorBelow : jumpFactorHere;
    }

    /**
     * The clip of {@code Entity.move} that resets fall distance after a move of at least one block: {@code
     * ClipContext.Block.FALLDAMAGE_RESETTING} (the tag, and end portals/gateways for players) with water picked.
     */
    private boolean fallResetClipHits(Vec3d from, Vec3d to) {
        boolean hit = traverseBlocks(from, to, (x, y, z) -> {
            int state = getBlockState(new BlockPos(x, y, z));
            String name = registry.block(state).name();
            boolean resetting = is(state, BlockTag.FALL_DAMAGE_RESETTING) || fallResettingPortalShapes() && (name.equals("minecraft:end_gateway") || name.equals("minecraft:end_portal"));
            boolean blockHit = resetting && clipUnitCube(from, to, x, y, z);
            boolean fluidHit = false;
            // BlockGetter.clip evaluates both shapes even when the block already hits: cache writes matter.
            if (blocks.hasFluid(state) && !env.fluidsResolved())
                unmodeled(Unmodeled.FLUID);
            else if (fluids.kind(state) == 1) {
                float ownHeight = ru.minecomplex.mac.world.FluidProperties.get().ownHeight(state);
                Float height = registry.fluidAmount(state) == 9 && fluids.kind(x, y + 1, z) == 1 ? Float.valueOf(1.0F) : p.fluidShapes.height(FluidShapeCache.key(registry, state), fluids.height(x, y, z));
                if (height != null)
                    fluidHit = clipFluidBox(from, to, x, y, z, height);
                else {
                    boolean low = clipFluidBox(from, to, x, y, z, ownHeight);
                    boolean full = clipFluidBox(from, to, x, y, z, 1.0F);
                    if (low != full && !blockHit)
                        unmodeled(Unmodeled.FLUID_SHAPE_CACHE);
                    fluidHit = low && full;
                }
            }
            return blockHit || fluidHit;
        });
        return hit;
    }

    @FunctionalInterface
    private interface CellTest {

        boolean hit(int x, int y, int z);
    }

    /**
     * {@code BlockGetter.traverseBlocks}: true at the first cell whose test hits.
     */
    private static boolean traverseBlocks(Vec3d from, Vec3d to, CellTest test) {
        if (from.equals(to)) {
            return false;
        }
        double toX = VanillaMath.lerp(-1.0E-7, to.x(), from.x());
        double toY = VanillaMath.lerp(-1.0E-7, to.y(), from.y());
        double toZ = VanillaMath.lerp(-1.0E-7, to.z(), from.z());
        double fromX = VanillaMath.lerp(-1.0E-7, from.x(), to.x());
        double fromY = VanillaMath.lerp(-1.0E-7, from.y(), to.y());
        double fromZ = VanillaMath.lerp(-1.0E-7, from.z(), to.z());
        int x = VanillaMath.floor(fromX);
        int y = VanillaMath.floor(fromY);
        int z = VanillaMath.floor(fromZ);
        if (test.hit(x, y, z)) {
            return true;
        }
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        int signX = VanillaMath.sign(dx);
        int signY = VanillaMath.sign(dy);
        int signZ = VanillaMath.sign(dz);
        double tDeltaX = signX == 0 ? Double.MAX_VALUE : signX / dx;
        double tDeltaY = signY == 0 ? Double.MAX_VALUE : signY / dy;
        double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / dz;
        double tX = tDeltaX * (signX > 0 ? 1.0 - VanillaMath.frac(fromX) : VanillaMath.frac(fromX));
        double tY = tDeltaY * (signY > 0 ? 1.0 - VanillaMath.frac(fromY) : VanillaMath.frac(fromY));
        double tZ = tDeltaZ * (signZ > 0 ? 1.0 - VanillaMath.frac(fromZ) : VanillaMath.frac(fromZ));
        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    x += signX;
                    tX += tDeltaX;
                } else {
                    z += signZ;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                y += signY;
                tY += tDeltaY;
            } else {
                z += signZ;
                tZ += tDeltaZ;
            }
            if (test.hit(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code Shapes.block().clip(from, to, pos) != null}: the test point just after {@code from}, else the box.
     */
    private static boolean clipUnitCube(Vec3d from, Vec3d to, int x, int y, int z) {
        return clipFluidBox(from, to, x, y, z, 1.0F);
    }

    /**
     * PistonMovingBlockEntity.moveEntityByPiston, for a chosen local-player game-time hypothesis.
     */
    public void moveByPiston(ru.minecomplex.mac.world.BlockDirection direction, Vec3d delta, long gameTime) {
        var previousNoClip = pistonNoClip;
        pistonNoClip = direction;
        p.pistonGameTime = gameTime;
        Vec3d previous = p.position;
        try {
            move(MoverType.PISTON, delta);
            {
                applyEffectsFromBlocks(List.of(new PlayerState.Movement(previous, p.position, null)));
                if (!p.movementThisTick.isEmpty())
                    p.movementThisTick.removeLast();
            }
        } finally {
            pistonNoClip = previousNoClip;
        }
    }

    /**
     * The unmounted local player in a PistonPush query; other entities are supplied by the entity timeline.
     */
    public ru.minecomplex.mac.world.piston.PistonPush.Entity pistonEntity(long gameTime) {
        return new ru.minecomplex.mac.world.piston.PistonPush.Entity() {

            public Aabb box() {
                return p.boundingBox();
            }

            public Vec3d position() {
                return p.position;
            }

            public Vec3d velocity() {
                return p.deltaMovement;
            }

            public void velocity(Vec3d value) {
                p.deltaMovement = value;
            }

            public boolean ignored() {
                return false;
            }

            public boolean normalReaction() {
                return true;
            }

            public boolean onGround() {
                return p.onGround;
            }

            public boolean supportedBy(ru.minecomplex.mac.world.BlockPosition pos) {
                return p.hasSupportingBlock && p.supportingX == pos.x() && p.supportingY == pos.y() && p.supportingZ == pos.z();
            }

            public boolean serverPlayer() {
                return false;
            }

            public boolean canSimulateMovement() {
                return true;
            }

            public void move(ru.minecomplex.mac.world.BlockDirection direction, Vec3d delta) {
                if (p.passenger)
                    unmodeled(Unmodeled.RIDING);
                moveByPiston(direction, delta, gameTime);
            }
        };
    }

    /**
     * {@code Entity.isColliding(pos, state)}: {@code Shapes.joinIsNotEmpty(state.getCollisionShape(level, pos,
     * CollisionContext.of(this)).move(pos), Shapes.create(getBoundingBox()), AND)}.
     */
    public boolean isColliding(int x, int y, int z, int state) {
        return ru.minecomplex.mac.collision.Shapes.intersect(shapeContext().shape(state, x, y, z).move(x, y, z), ru.minecomplex.mac.collision.Shapes.create(p.boundingBox()));
    }

    /**
     * {@code move(MoverType.SHULKER, delta)}: a shulker entity's opening moves the player.
     */
    public void moveByShulkerEntity(Vec3d delta) {
        if (p.passenger)
            unmodeled(Unmodeled.RIDING);
        move(MoverType.SHULKER, delta);
    }

    /**
     * Shulker box displacement has neither piston NOCLIP nor piston movement limiting.
     */
    public ru.minecomplex.mac.world.shulker.ShulkerPush.Entity shulkerEntity() {
        return new ru.minecomplex.mac.world.shulker.ShulkerPush.Entity() {

            public boolean ignored() {
                return false;
            }

            public void move(Vec3d delta) {
                if (p.passenger)
                    unmodeled(Unmodeled.RIDING);
                MovementTicker.this.move(MoverType.SHULKER_BOX, delta);
            }
        };
    }

    private static boolean clipFluidBox(Vec3d from, Vec3d to, int x, int y, int z, float height) {
        Vec3d diff = to.subtract(from);
        if (diff.lengthSqr() < 1.0E-7) {
            return false;
        }
        Vec3d testPoint = from.add(diff.scale(0.001));
        double tx = testPoint.x() - x;
        double ty = testPoint.y() - y;
        double tz = testPoint.z() - z;
        // findIndex on the coordinates {0, 1}: inside iff 0 <= t < 1 on every axis
        if (tx >= 0.0 && tx < 1.0 && ty >= 0.0 && ty < (double) height && tz >= 0.0 && tz < 1.0) {
            return true;
        }
        return InsideBlocks.clip(x, y, z, x + 1.0, y + (double) height, z + 1.0, from, to) != null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Block effects
    /**
     * {@code Entity.applyEffectsFromBlocks()}.
     */
    protected void applyEffectsFromBlocks() {
        List<PlayerState.Movement> finalMovements = new ArrayList<>(p.movementThisTick);
        p.movementThisTick.clear();
        if (finalMovements.isEmpty()) {
            finalMovements.add(new PlayerState.Movement(p.oldPosition, p.position, null));
        } else if (finalMovements.getLast().to().distanceToSqr(p.position) > 9.9999994E-11F) {
            finalMovements.add(new PlayerState.Movement(finalMovements.getLast().to(), p.position, null));
        }
        applyEffectsFromBlocks(finalMovements);
    }

    private void applyEffectsFromBlocks(List<PlayerState.Movement> finalMovements) {
        // isAffectedByBlocks(): !isRemoved() && !noPhysics
        if (p.noPhysics) {
            return;
        }
        if (p.onGround) {
            BlockPos effectPos = getOnPosLegacy();
            stepOn(getBlockState(effectPos));
        }
        boolean freeze = checkInsideBlocks(finalMovements);
        // InsideBlockEffectType.FREEZE, applied by the collector after the walk
        if (freeze) {
            p.isInPowderSnow = true;
        }
    }

    /**
     * {@code Block.stepOn} overrides that move the entity: {@code SlimeBlock}.
     */
    private void stepOn(int state) {
        if (blocks.isSlime(state)) {
            double absDeltaY = Math.abs(p.deltaMovement.y());
            if (absDeltaY < 0.1 && !p.shiftKeyDown) {
                double scale = 0.4 + absDeltaY * 0.2;
                p.deltaMovement = p.deltaMovement.multiply(scale, 1.0, scale);
            }
        }
    }

    /**
     * {@code Entity.checkInsideBlocks(List, collector)}: true if powder snow applied FREEZE.
     */
    private boolean checkInsideBlocks(List<PlayerState.Movement> movements) {
        boolean[] freeze = { false };
        for (PlayerState.Movement movement : movements) {
            Vec3d pos = movement.from();
            Vec3d delta = movement.to().subtract(movement.from());
            int maxMovementIterations = 16;
            if (movement.axisDependentOriginalMovement() != null && delta.lengthSqr() > 0.0) {
                // the axis order of the move before collision
                for (Axis axis : axisStepOrder(movement.axisDependentOriginalMovement())) {
                    double axisMove = delta.get(axis);
                    if (axisMove != 0.0) {
                        Vec3d to = relative(pos, axis, axisMove);
                        maxMovementIterations -= checkInsideBlocks(pos, to, maxMovementIterations, freeze);
                        pos = to;
                    }
                }
            } else {
                maxMovementIterations -= checkInsideBlocks(movement.from(), movement.to(), 16, freeze);
            }
            if (maxMovementIterations <= 0) {
                checkInsideBlocks(movement.to(), movement.to(), 1, freeze);
            }
        }
        if (visitedBlocks != null)
            visitedBlocks.clear();
        return freeze[0];
    }

    private static final Axis[] YZX = { Axis.Y, Axis.Z, Axis.X }, YXZ = { Axis.Y, Axis.X, Axis.Z };

    /**
     * {@code Direction.axisStepOrder} (earlier {@code Entity.axisStepOrder}): YZX if |x| < |z|, else YXZ. Do not modify.
     */
    private static Axis[] axisStepOrder(Vec3d movement) {
        return Math.abs(movement.x()) < Math.abs(movement.z()) ? YZX : YXZ;
    }

    private LongMap<Boolean> visitedBlocks() {
        if (visitedBlocks == null)
            visitedBlocks = new LongMap<>();
        return visitedBlocks;
    }

    private LongMap<Boolean> walkVisited() {
        if (walkVisited == null)
            walkVisited = new LongMap<>();
        return walkVisited;
    }

    /**
     * {@code Vec3.relative(axis.getPositive(), distance)}.
     */
    private static Vec3d relative(Vec3d pos, Axis axis, double distance) {
        return new Vec3d(pos.x() + distance * (axis == Axis.X ? 1 : 0), pos.y() + distance * (axis == Axis.Y ? 1 : 0), pos.z() + distance * (axis == Axis.Z ? 1 : 0));
    }

    /**
     * {@code Entity.checkInsideBlocks(from, to, collector, visited, max)}: iterations used.
     */
    private int checkInsideBlocks(Vec3d from, Vec3d to, int maxMovementIterations, boolean[] freeze) {
        Aabb target = p.makeBoundingBox(to);
        double deflate = 1.0E-5F;
        Aabb deflatedBoundingBoxAtTarget = new Aabb(target.minX() + deflate, target.minY() + deflate, target.minZ() + deflate, target.maxX() - deflate, target.maxY() - deflate, target.maxZ() - deflate);
        boolean movedFar = from.distanceToSqr(to) > VanillaMath.square(0.9999900000002526);
        int[] iterations = { 0 };
        InsideBlocks.forEachBlockIntersectedBetween(from, to, deflatedBoundingBoxAtTarget, (x, y, z, iteration) -> {
            if (!p.alive) {
                return false;
            }
            if (iteration >= maxMovementIterations) {
                return false;
            }
            iterations[0] = iteration;
            int state = getBlockState(new BlockPos(x, y, z));
            if (registry.isAir(state)) {
                return true;
            }
            boolean insideBlock = insideBlock(state, x, y, z, from, to);
            // Fluid contact adds only fire/extinguish effects; it matters here as a way into visitedBlocks, which the
            // blocks with a custom inside shape (powder snow, portals, cauldrons, tripwire) cannot be combined with
            if (insideBlock && visitedBlocks().put(InsideBlocks.asLong(x, y, z), Boolean.TRUE) == null) {
                boolean isPrecise = movedFar || deflatedBoundingBoxAtTarget.intersects(x, y, z, x + 1, y + 1, z + 1);
                entityInside(state, x, y, z, isPrecise, freeze);
            }
            return true;
        }, walkVisited());
        return iterations[0] + 1;
    }

    /**
     * {@code getEntityInsideCollisionShape} is the {@code Shapes.block()} instance, or the swept box touches it.
     */
    private boolean insideBlock(int state, int x, int y, int z, Vec3d from, Vec3d to) {
        if (!blocks.isPowderSnow(state)) {
            // the other overrides (end portal, cauldrons, tripwire) have no movement effect; only the visited set
            // they fill matters, and they are never combined with a movement-affecting block at one position
            return true;
        }
        // PowderSnowBlock: the collision shape for this entity, or the whole block when that is empty
        VoxelShape shape = shapeContext().shape(state, x, y, z);
        if (shape.isEmpty() || shape == VoxelShape.BLOCK) {
            return true;
        }
        // FALLING_COLLISION_SHAPE is one box
        Aabb box = new Aabb(shape.min(Axis.X) + x, shape.min(Axis.Y) + y, shape.min(Axis.Z) + z, shape.max(Axis.X) + x, shape.max(Axis.Y) + y, shape.max(Axis.Z) + z);
        return InsideBlocks.collidedAlongVector(p.makeBoundingBox(from), to.subtract(from), List.of(box));
    }

    /**
     * {@code BlockBehaviour.entityInside} overrides that move a player.
     */
    protected boolean fallResettingPortalShapes() {
        return true;
    }

    protected boolean livingBody() {
        return true;
    }

    private void entityInside(int state, int x, int y, int z, boolean isPrecise, boolean[] freeze) {
        switch(blocks.inside(state)) {
            case COBWEB ->
                makeStuckInBlock(new Vec3d(0.25, 0.05F, 0.25), p.effects.has(MobEffect.WEAVING) ? new Vec3d(0.5, 0.25, 0.5) : null);
            case HONEY ->
                {
                    if (isSlidingDown(x, y, z)) {
                        doSlideMovement();
                    }
                }
            case POWDER_SNOW ->
                {
                    if (!livingBody() || blocks.isPowderSnow(getInBlockState())) {
                        makeStuckInBlock(new Vec3d(0.9F, 1.5, 0.9F), null);
                    }
                    freeze[0] = true;
                }
            case SWEET_BERRY_BUSH ->
                {
                    if (livingBody())
                        makeStuckInBlock(new Vec3d(0.8F, 0.75, 0.8F), null);
                }
            case BUBBLE_COLUMN ->
                {
                    if (!isPrecise || p.abilities.flying)
                        break;
                    int above = env.blocks().getBlock(x, y + 1, z);
                    if (registry.block(above).name().equals("minecraft:moving_piston"))
                        unmodeled(Unmodeled.CONTEXT_SHAPE);
                    boolean nothingAbove = registry.collisionShape(above).isEmpty() && fluids.kind(above) == 0;
                    boolean down = registry.properties(state).contains("drag=true");
                    bubbleColumn(nothingAbove, down);
                }
            case NONE ->
                {
                }
        }
    }

    /**
     * {@code Player.makeStuckInBlock}; {@code weaving} replaces the multiplier when the effect is active.
     */
    private void makeStuckInBlock(Vec3d speedMultiplier, Vec3d weaving) {
        if (!p.abilities.flying) {
            resetFallDistance();
            p.stuckSpeedMultiplier = weaving != null ? weaving : speedMultiplier;
        }
    }

    /**
     * {@code HoneyBlock.isSlidingDown}.
     */
    private boolean isSlidingDown(int x, int y, int z) {
        if (p.onGround) {
            return false;
        }
        if (p.y() > y + 0.9375 - 1.0E-7) {
            return false;
        }
        if (honeyOldDeltaY(p.deltaMovement.y()) >= -0.08) {
            return false;
        }
        double dx = Math.abs(x + 0.5 - p.x());
        double dz = Math.abs(z + 0.5 - p.z());
        double overlapDistance = 0.4375 + p.width() / 2.0F;
        return dx + 1.0E-7 > overlapDistance || dz + 1.0E-7 > overlapDistance;
    }

    /**
     * {@code HoneyBlock.doSlideMovement}.
     */
    private void doSlideMovement() {
        Vec3d deltaMovement = p.deltaMovement;
        if (honeyOldDeltaY(p.deltaMovement.y()) < -0.13) {
            double horizontalReductionFactor = -0.05 / honeyOldDeltaY(p.deltaMovement.y());
            p.deltaMovement = new Vec3d(deltaMovement.x() * horizontalReductionFactor, honeyNewDeltaY(-0.05), deltaMovement.z() * horizontalReductionFactor);
        } else {
            p.deltaMovement = new Vec3d(deltaMovement.x(), honeyNewDeltaY(-0.05), deltaMovement.z());
        }
        resetFallDistance();
    }

    private static double honeyOldDeltaY(double deltaY) {
        return deltaY / 0.98F + 0.08;
    }

    private static double honeyNewDeltaY(double deltaY) {
        return (deltaY - 0.08) * 0.98F;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Positions and world access
    /**
     * Block coordinates.
     */
    protected record BlockPos(int x, int y, int z) {
    }

    /**
     * {@code Entity.blockPosition()}.
     */
    protected BlockPos blockPosition() {
        return new BlockPos(VanillaMath.floor(p.x()), VanillaMath.floor(p.y()), VanillaMath.floor(p.z()));
    }

    /**
     * {@code Entity.getOnPosLegacy}: {@code getOnPos(0.2F)}.
     */
    protected BlockPos getOnPosLegacy() {
        return getOnPos(0.2F);
    }

    /**
     * {@code Entity.getBlockPosBelowThatAffectsMyMovement}: {@code getOnPos(0.500001F)}.
     */
    protected BlockPos getBlockPosBelowThatAffectsMyMovement() {
        return getOnPos(0.500001F);
    }

    /**
     * {@code Entity.getOnPos(float)}.
     */
    private BlockPos getOnPos(float offset) {
        if (p.hasSupportingBlock) {
            BlockPos support = new BlockPos(p.supportingX, p.supportingY, p.supportingZ);
            if (!(offset > 1.0E-5F)) {
                return support;
            }
            int belowState = getBlockState(support);
            return (!(offset <= 0.5) || !is(belowState, BlockTag.FENCES)) && !is(belowState, BlockTag.WALLS) && !blocks.isFenceGate(belowState) ? new BlockPos(support.x(), VanillaMath.floor(p.y() - offset), support.z()) : support;
        }
        return new BlockPos(VanillaMath.floor(p.x()), VanillaMath.floor(p.y() - offset), VanillaMath.floor(p.z()));
    }

    /**
     * {@code Entity.getInBlockState()}: the state at the block position.
     */
    protected int getInBlockState() {
        return getBlockState(blockPosition());
    }

    /**
     * {@code level.getBlockState(pos)}: void air where the client has no chunk (and outside the build height).
     */
    protected int getBlockState(BlockPos pos) {
        int state = env.blocks().getBlock(pos.x(), pos.y(), pos.z());
        return state == CompensatedWorld.UNLOADED ? blocks.voidAir : state;
    }

    protected boolean is(int state, BlockTag tag) {
        return env.tags().contains(tag, registry.block(state));
    }

    /**
     * {@code Entity.resetFallDistance}.
     */
    protected void resetFallDistance() {
        p.fallDistance = 0.0;
    }

    /**
     * {@code PowderSnowBlock.canEntityWalkOnPowderSnow} for a player: leather boots (players are not in the tag).
     */
    public static boolean canEntityWalkOnPowderSnow(PlayerState player) {
        return player.wearingLeatherBoots;
    }

    protected void bubbleColumn(boolean above, boolean down) {
        double current = p.deltaMovement.y();
        double result = down ? Math.max(above ? -0.9 : -0.3, current - 0.03) : Math.min(above ? 1.8 : 0.7, current + (above ? 0.1 : 0.06));
        p.deltaMovement = new Vec3d(p.deltaMovement.x(), result, p.deltaMovement.z());
        if (!above)
            resetFallDistance();
    }
}
