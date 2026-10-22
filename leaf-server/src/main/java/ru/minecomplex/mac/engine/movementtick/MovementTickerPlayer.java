package ru.minecomplex.mac.engine.movementtick;
import ru.minecomplex.mac.util.math.Trig;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.Axis;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.entity.effect.MobEffect;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.mechanics.collision.EntityShapeContext;
import ru.minecomplex.mac.mechanics.fluid.FluidMovement;
import ru.minecomplex.mac.packet.MoveKind;
import ru.minecomplex.mac.packet.Packets;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.player.state.SendState;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec2f;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.util.Tri;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;

/**
 * The local player's tick: vanilla {@code LocalPlayer} on top of {@link MovementTicker}. {@code LocalPlayer.tick} and
 * {@code aiStep} (keyboard input, sprinting, creative flight, the elytra start, the riding jump), the push out of blocks,
 * auto-jump, {@code rideTick} and {@code sendPosition}.
 *
 * <p>What vanilla reads from elsewhere is read where the model keeps it: food, item use and equipment from
 * {@link ClientPlayerData}, client options from {@link LocalPlayerState}, the vehicle from the {@link LocalPlayerRide} of
 * a ride tick. A tick's serverbound packets collect in {@link #packets()} in client order; through 26.2 they are sent
 * inside {@link #simulateTick}, from 26.3 by {@link #sendAfterEntityTicks} once the other entities ticked.
 */
public final class MovementTickerPlayer extends MovementTicker {

    /**
     * {@code UseEffects.DEFAULT}: an item without the {@code use_effects} component.
     */
    private static final UseEffects DEFAULT_USE_EFFECTS = new UseEffects(false, true, 0.2F);

    private final LocalPlayerState local;

    private final ClientPlayerData data;

    /**
     * The vehicle phases of the ride tick in progress; {@code null} outside {@link #simulateRideTick}.
     */
    LocalPlayerRide ride;

    /**
     * Serverbound packets of the current tick, in client order.
     */
    private final List<Object> packets = new ArrayList<>();

    /**
     * {@code tryToStartFallFlying}'s conditions as they were when the tick started. Vanilla checks them in {@code aiStep}
     * after the fluid update, and 26.3 checks {@code isInLiquid()} instead of {@code isInWater()}: not modelled yet
     * ({@code docs/research/status.md}).
     */
    private boolean glideCanStart;

    /**
     * See {@link #rotationAfterTick}.
     */
    private Float rotationAfterTickYRot, rotationAfterTickXRot;

    public MovementTickerPlayer(MovementEnvironment env, PlayerState player, LocalPlayerState local, ClientPlayerData data) {
        super(env, player);
        this.local = local;
        this.data = data;
    }

    /**
     * The serverbound packets of the current tick so far, in client order.
     */
    public List<Object> packets() {
        return packets;
    }

    // ------------------------------------------------------------------------------------------------------------
    // The tick
    /**
     * The whole tick, for a world with no entity ticks between its phases.
     */
    public Set<Unmodeled> tick(PlayerInput keys) {
        Set<Unmodeled> gaps = simulateTick(keys);
        if (data.clientLoaded)
            gaps = sendAfterEntityTicks();
        return gaps;
    }

    /**
     * {@code LocalPlayer.tick} in the entity tick phase, with the effective keys (after key mappings). From 26.3 the
     * caller must call {@link #sendAfterEntityTicks} after the other entities ticked.
     *
     * @return what the tick could not simulate
     */
    public Set<Unmodeled> simulateTick(PlayerInput keys) {
        packets.clear();
        glideCanStart = data.inventory.equipmentCanGlide() && !p.onGround && !p.passenger && !p.fallFlying && !p.effects.has(MobEffect.LEVITATION) && !p.wasTouchingWater;
        // a ride's caller has run the passenger's commonTick
        if (ride == null) {
            clearUnmodeled();
            commonTick();
        }
        if (!data.clientLoaded)
            return Set.of();
        if (ride != null)
            clearUnmodeled();
        if (!p.effects.timingResolved())
            unmodeled(Unmodeled.EFFECT_TIMING);
        if (ride != null) {
            p.oldPosition = p.position;
            // the passenger's own interpolation runs in the caller's commonTick (26.3) or before the seat moves it
            if (p.interpolation != null && p.interpolation.active())
                unmodeled(Unmodeled.ENTITY_MOTION);
        }
        p.noPhysics = p.spectator;
        if (p.spectator || p.passenger)
            p.onGround = false;
        p.wasInPowderSnow = p.isInPowderSnow;
        p.isInPowderSnow = false;
        p.wasUnderwater = p.eyeInWater;
        refreshFluidContact(true);
        if (ride != null)
            ride.unmodeled().forEach(this::unmodeled);
        p.swimming = !p.abilities.flying && (p.swimming ? p.sprinting && p.wasTouchingWater && !p.passenger : p.sprinting && p.wasUnderwater && !p.passenger && fluids.kind(VanillaMath.floor(p.x()), VanillaMath.floor(p.y()), VanillaMath.floor(p.z())) == 1);
        if (p.isInLava && !p.firstTick)
            p.fallDistance *= 0.5;
        p.firstTick = false;
        p.effects.tickClient();
        if (local.sprintTriggerTime > 0)
            local.sprintTriggerTime--;
        aiStep(keys);
        return Set.copyOf(unmodeled());
    }

    /**
     * {@code LocalPlayer.aiStep}, then the end of {@code LivingEntity.tick} and {@code Player.tick}.
     */
    private void aiStep(PlayerInput keys) {
        boolean oldJump = local.input.jump(), oldShift = local.input.shift();
        boolean oldForward = local.vector.y() > 1.0E-5F;
        boolean oldEnough = p.wasUnderwater ? oldForward : local.vector.y() >= 0.8;
        local.crouching = !p.abilities.flying && !p.swimming && !p.passenger && canFit(Pose.CROUCHING) && (oldShift || !p.sleeping && !canFit(Pose.STANDING));
        local.input = keys;
        local.vector = keyboardVector(keys);
        p.shiftKeyDown = keys.shift();
        boolean autoJump = local.autoJumpTime > 0;
        if (autoJump) {
            local.autoJumpTime--;
            local.input = local.input.withJump();
        }
        pushOutOfBlocks();
        boolean slowItem = data.usingItem && (!itemAllowsSprint());
        if (oldShift || (slowItem && !p.passenger || keys.backward())) {
            local.sprintTriggerTime = 0;
        }
        if (canStartSprinting()) {
            {
                if (!oldForward) {
                    if (local.sprintTriggerTime > 0)
                        p.setSprinting(true);
                    else
                        local.sprintTriggerTime = local.sprintWindow;
                }
                if (keys.sprint())
                    p.setSprinting(true);
            }
        }
        if (p.sprinting) {
            boolean stop;
            {
                stop = p.swimming ? shouldStopSwimSprinting() : shouldStopRunSprinting();
            }
            if (stop)
                p.setSprinting(false);
        }
        boolean toggledFlight = false;
        if (p.abilities.mayfly) {
            if (p.spectator) {
                if (!p.abilities.flying) {
                    p.abilities.flying = true;
                    toggledFlight = true;
                    sendAbilities();
                }
            } else if (!oldJump && local.input.jump() && !autoJump) {
                if (local.jumpTriggerTime == 0) {
                    local.jumpTriggerTime = 7;
                } else if (!p.swimming && (!p.passenger || jumpableVehicle())) {
                    p.abilities.flying = !p.abilities.flying;
                    if (p.abilities.flying && p.onGround)
                        jumpFromGround();
                    toggledFlight = true;
                    sendAbilities();
                    local.jumpTriggerTime = 0;
                }
            }
        }
        if (local.input.jump() && !toggledFlight && !oldJump && !onClimbable() && glideCanStart) {
            p.fallFlying = true;
            packets.add(Packets.command(ServerboundPlayerCommandPacket.Action.START_FALL_FLYING, 0));
        }
        local.wasFallFlying = p.fallFlying;
        if (p.wasTouchingWater && local.input.shift() && !p.abilities.flying) {
            p.deltaMovement = FluidMovement.descend(p.deltaMovement);
        }
        if (p.eyeInWater)
            local.waterVisionTime = Math.clamp(local.waterVisionTime + (p.spectator ? 10 : 1), 0, 600);
        else if (local.waterVisionTime > 0)
            local.waterVisionTime = Math.clamp(local.waterVisionTime - 10, 0, 600);
        if (p.abilities.flying && local.controlledCamera) {
            int vertical = (local.input.jump() ? 1 : 0) - (local.input.shift() ? 1 : 0);
            if (vertical != 0)
                p.deltaMovement = p.deltaMovement.add(0.0, vertical * p.abilities.flyingSpeed * 3.0F, 0.0);
        }
        int ridingCharge = updateRidingJump(oldJump, local.input.jump());
        if (ridingCharge != NO_RIDING_JUMP) {
            if (ride == null || !ride.onPlayerJump(ridingCharge))
                unmodeled(Unmodeled.RIDING);
            packets.add(Packets.command(ServerboundPlayerCommandPacket.Action.START_RIDING_JUMP, ridingCharge));
        }
        if (ride == null && (p.passenger || jumpableVehicle()))
            unmodeled(Unmodeled.RIDING);
        if (local.jumpTriggerTime > 0)
            local.jumpTriggerTime--;
        super.aiStep();
        if (p.onGround && p.abilities.flying && !p.spectator) {
            p.abilities.flying = false;
            sendAbilities();
        }
        // LivingEntity.tick after aiStep: a sleeper looks straight ahead (setXRot(0.0F))
        if (p.sleeping)
            p.xRot = 0.0F;
        // the end of LivingEntity.tick: refreshDirtyAttributes -> onAttributeUpdated(SCALE) -> refreshDimensions (a scale
        // that did not change refreshes to the same dimensions)
        p.refreshDimensions();
        double x = Math.clamp(p.x(), -2.9999999E7, 2.9999999E7), z = Math.clamp(p.z(), -2.9999999E7, 2.9999999E7);
        if (x != p.x() || z != p.z())
            p.position = new Vec3d(x, p.y(), z);
        updatePlayerPose();
    }

    /**
     * From 26.3 {@code sendPosition} runs after every entity ticked. Call after {@link #simulateTick} of the tick.
     */
    public Set<Unmodeled> sendAfterEntityTicks() {
        sendPosition();
        return Set.copyOf(unmodeled());
    }

    /**
     * The late send of a ride tick: the root vehicle is sampled again after the unrelated entity ticks.
     */
    public Set<Unmodeled> sendAfterEntityTicks(LocalPlayerRide context) {
        java.util.Objects.requireNonNull(context);
        local.rootVehicle = context.rootVehicle();
        context.unmodeled().forEach(this::unmodeled);
        return sendAfterEntityTicks();
    }

    /**
     * Entity -> LivingEntity -> Player -> LocalPlayer.rideTick, after the vehicle's entity tick. The caller has already
     * run commonTick (including saving the old yaw) and must send the late packets separately.
     */
    public Set<Unmodeled> simulateRideTick(PlayerInput keys, LocalPlayerRide context) {
        if (!p.passenger)
            throw new IllegalStateException("rideTick requires a mounted client player");
        java.util.Objects.requireNonNull(context);
        if (ride != null)
            throw new IllegalStateException("Reentrant ride tick");
        ride = context;
        try {
            if (!data.clientLoaded)
                clearUnmodeled();
            p.deltaMovement = Vec3d.ZERO;
            local.rootVehicle = context.rootVehicle();
            Set<Unmodeled> tickGaps = simulateTick(keys);
            if (data.clientLoaded) {
                // Player.aiStep, before seat rotation.
                local.headYaw = p.yRot;
                turnBody();
            }
            if (p.passenger)
                context.positionRider(p, local);
            resetFallDistance();
            local.handsBusy = false;
            if (context.controlsBoat()) {
                context.setBoatInput(local.input);
                local.handsBusy |= local.input.left() || local.input.right() || local.input.forward() || local.input.backward();
            }
            local.rootVehicle = context.rootVehicle();
            context.unmodeled().forEach(this::unmodeled);
            var gaps = EnumSet.noneOf(Unmodeled.class);
            gaps.addAll(tickGaps);
            gaps.addAll(unmodeled());
            return Set.copyOf(gaps);
        } finally {
            ride = null;
        }
    }

    /**
     * The end of {@code LivingEntity.tick} after {@code aiStep}: the body turns towards the horizontal walking direction
     * ({@code Mth.atan2}) and {@code tickHeadTurn} keeps it within 50° of the yaw. A seat may set it afterwards (boats,
     * horses). Not modelled, as nothing moves by the body yaw: 15° while blocking with a shield, and the body turned to
     * the yaw during an arm swing.
     */
    private void turnBody() {
        double xd = p.x() - p.oldPosition.x();
        double zd = p.z() - p.oldPosition.z();
        float sideDist = (float) (xd * xd + zd * zd);
        float target = local.bodyYaw;
        if (sideDist > 0.0025000002F) {
            float walk = (float) VanillaMath.atan2(zd, xd) * (180.0F / (float) Math.PI) - 90.0F;
            float diff = Math.abs(BoatPassengerPhysics.wrap(p.yRot) - walk);
            target = 95.0F < diff && diff < 265.0F ? walk - 180.0F : walk;
        }
        local.bodyYaw += BoatPassengerPhysics.wrap(target - local.bodyYaw) * 0.3F;
        float head = BoatPassengerPhysics.wrap(p.yRot - local.bodyYaw);
        if (Math.abs(head) > 50.0F)
            local.bodyYaw = local.bodyYaw + (head - Integer.signum(Float.compare(head, 0.0F)) * 50.0F);
    }

    /**
     * {@code ClientLevel.tickNonPassenger} before {@code LocalPlayer.tick}, whether or not the client has loaded: the old
     * position ({@code setOldPosAndRot}), then from 26.3 {@code Entity.commonTick}'s own interpolation step.
     */
    private void commonTick() {
        p.oldPosition = p.position;
        interpolateSelf();
    }

    /**
     * The rotation the client had once its tick ran, when the caller knows it from the client's packet but not the
     * rotation it started with (the camera turns between ticks): an own interpolation step moves the player as vanilla
     * does and leaves this rotation, which nothing after the step changes. {@code null} restores the vanilla step.
     */
    public void rotationAfterTick(Float yRot, Float xRot) {
        this.rotationAfterTickYRot = yRot;
        this.rotationAfterTickXRot = xRot;
    }

    @Override
    protected void interpolateSelf() {
        boolean active = p.interpolation != null && p.interpolation.active();
        super.interpolateSelf();
        if (active && rotationAfterTickYRot != null) {
            p.yRot = rotationAfterTickYRot;
            p.xRot = rotationAfterTickXRot;
        }
    }

    @Override
    protected boolean mountedTravelResolved() {
        return ride != null;
    }

    @Override
    protected void refreshFluidContact(boolean eyes) {
        fluids.refresh(p, eyes, ride == null ? null : ride.boatFluidFacts(), ride != null && ride.nonBoatVehicleResolved());
    }

    @Override
    protected boolean isSuppressingSlidingDownLadder() {
        return !p.abilities.flying && super.isSuppressingSlidingDownLadder();
    }

    // ------------------------------------------------------------------------------------------------------------
    // What LocalPlayer reads elsewhere
    /**
     * {@code Player.isMobilityRestricted()}: blindness.
     */
    private boolean isMobilityRestricted() {
        return p.effects.has(MobEffect.BLINDNESS);
    }

    /**
     * The used item's {@code use_effects} ({@code UseEffects.DEFAULT} without the component).
     */
    private UseEffects useEffects() {
        var effects = data.usedStack().getOrDefault(DataComponents.USE_EFFECTS, null);
        return effects == null ? DEFAULT_USE_EFFECTS : effects;
    }

    private boolean itemAllowsSprint() {
        return useEffects().canSprint();
    }

    /**
     * {@code LocalPlayer.vehicleCanSprint(getVehicle())}.
     */
    private boolean vehicleCanSprint() {
        return ride != null && ride.vehicleCanSprint();
    }

    /**
     * {@code jumpableVehicle() != null}.
     */
    private boolean jumpableVehicle() {
        return ride != null && ride.jumpableVehicle();
    }

    private boolean vehicleOnGround() {
        return ride != null && ride.vehicleOnGround();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Input
    /**
     * {@code KeyboardInput.tick}: the effective keys as a move vector. {@code Input.makeJump} does not change it.
     */
    Vec2f keyboardVector(PlayerInput keys) {
        float forward = impulse(keys.forward(), keys.backward());
        float left = impulse(keys.left(), keys.right());
        Vec2f raw = new Vec2f(left, forward);
        return raw.normalized();
    }

    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }

    /**
     * {@code LocalPlayer.modifyInput}: item use and sneaking slow the input; from 1.21.5 the vector is scaled by 0.98 and
     * squared to the unit square here (1.21.4 mutates the keyboard input before the sprint and jump decisions, and
     * {@code LivingEntity} scales by 0.98 later).
     */
    private Vec2f modifyInput(Vec2f input) {
        return modifyInput(input, data.usingItem, p.passenger, useEffects().speedMultiplier(), isMovingSlowly(), p.attributes.value(Attribute.SNEAKING_SPEED));
    }

    /**
     * {@link #modifyInput(Vec2f)} with what it reads given.
     */
    Vec2f modifyInput(Vec2f input, boolean usingItem, boolean passenger, float itemUseSpeedMultiplier, boolean movingSlowly, double sneakingSpeed) {
        if (input.lengthSquared() == 0.0F)
            return input;
        Vec2f modified = input.scale(0.98F);
        if (usingItem && !passenger) {
            modified = modified.scale(itemUseSpeedMultiplier);
        }
        if (movingSlowly)
            modified = modified.scale((float) sneakingSpeed);
        return squareMovement(modified);
    }

    private static Vec2f squareMovement(Vec2f input) {
        float length = input.length();
        if (length <= 0.0F)
            return input;
        Vec2f direction = input.scale(1.0F / length);
        float x = Math.abs(direction.x());
        float y = Math.abs(direction.y());
        float tan = y > x ? x / y : y / x;
        float distanceToUnitSquare = (float) Math.sqrt(1.0F + tan * tan);
        float modifiedLength = Math.min(length * distanceToUnitSquare, 1.0F);
        return direction.scale(modifiedLength);
    }

    @Override
    protected void applyInput() {
        if (!local.controlledCamera) {
            {
                p.xxa *= 0.98F;
                p.zza *= 0.98F;
            }
            return;
        }
        Vec2f vector = modifyInput(local.vector);
        p.xxa = vector.x();
        p.zza = vector.y();
        p.jumping = local.input.jump();
    }

    /**
     * {@code LocalPlayer.isMovingSlowly}: crouching, or {@code isVisuallyCrawling} (visually swimming out of water:
     * {@code Entity.isVisuallySwimming}'s swimming pose, or {@code LivingEntity}'s fall-flying pose kept after the glide
     * ended, until the player's tick updates the pose).
     */
    private boolean isMovingSlowly() {
        return local.crouching || (p.pose == Pose.SWIMMING || !p.fallFlying && p.pose == Pose.FALL_FLYING) && !p.wasTouchingWater;
    }

    private boolean isMoving() {
        return local.vector.lengthSquared() > 0.0F;
    }

    // ------------------------------------------------------------------------------------------------------------
    /**
     * {@code LocalPlayer.canStartSprinting}.
     */
    boolean canStartSprinting() {
        float forwardImpulse = local.vector.y();
        boolean underWater = p.wasUnderwater;
        boolean forward = hasForwardImpulse(forwardImpulse);
        if (p.sprinting || !forward)
            return false;
        {
            if (!isSprintingPossible(p.abilities.flying))
                return false;
        }
        if (data.usingItem && (!itemAllowsSprint()))
            return false;
        if (p.fallFlying && (!underWater))
            return false;
        return !isMovingSlowly() || underWater;
    }

    /**
     * {@code LocalPlayer.isSprintingPossible}, the common gate from 1.21.9.
     */
    boolean isSprintingPossible(boolean allowedInShallowWater) {
        boolean resources = p.passenger ? vehicleCanSprint() : hasEnoughFoodToSprint();
        return !isMobilityRestricted() && resources && (allowedInShallowWater || !p.wasTouchingWater || p.wasUnderwater);
    }

    /**
     * {@code LocalPlayer.shouldStopRunSprinting}.
     */
    boolean shouldStopRunSprinting() {
        boolean blocked = !isSprintingPossible(p.abilities.flying);
        return blocked || !hasForwardImpulse(local.vector.y()) || p.horizontalCollision && !p.minorHorizontalCollision;
    }

    /**
     * {@code LocalPlayer.shouldStopSwimSprinting}.
     */
    boolean shouldStopSwimSprinting() {
        boolean blocked = !isSprintingPossible(true);
        return blocked || !p.wasTouchingWater || !hasForwardImpulse(local.vector.y()) && !p.onGround && !local.input.shift();
    }

    /**
     * {@code hasEnoughFoodToDoExhaustiveManoeuvres}, with a passenger's food never checked.
     */
    private boolean hasEnoughFoodToSprint() {
        return p.passenger || data.food > 6.0F || p.abilities.mayfly;
    }

    private static boolean hasForwardImpulse(float impulse) {
        return impulse > 1.0E-5F;
    }

    // ------------------------------------------------------------------------------------------------------------
    // The riding jump
    /**
     * No charge released this tick (a released charge may be negative: the scale counts up from the cooldown).
     */
    static final int NO_RIDING_JUMP = Integer.MIN_VALUE;

    /**
     * The riding-jump charge of {@code LocalPlayer.aiStep}: counts while the jump key is held on a jumpable vehicle.
     *
     * @return the charge to release ({@code onPlayerJump}, then {@code START_JUMPING_WITH_HORSE}), or
     *         {@link #NO_RIDING_JUMP}
     */
    private int updateRidingJump(boolean oldJump, boolean jump) {
        return updateRidingJump(oldJump, jump, jumpableVehicle(), ride == null ? 0 : ride.vehicleJumpCooldown());
    }

    /**
     * {@link #updateRidingJump(boolean, boolean)} with the vehicle's reads given.
     */
    int updateRidingJump(boolean oldJump, boolean jump, boolean jumpableVehicle, int cooldown) {
        if (!jumpableVehicle || cooldown != 0) {
            local.jumpRidingScale = 0.0F;
            return NO_RIDING_JUMP;
        }
        if (local.jumpRidingTicks < 0) {
            local.jumpRidingTicks++;
            if (local.jumpRidingTicks == 0)
                local.jumpRidingScale = 0.0F;
        }
        if (oldJump && !jump) {
            local.jumpRidingTicks = -10;
            return VanillaMath.floor(local.jumpRidingScale * 100.0F);
        } else if (!oldJump && jump) {
            local.jumpRidingTicks = 0;
            local.jumpRidingScale = 0.0F;
        } else if (oldJump) {
            local.jumpRidingTicks++;
            local.jumpRidingScale = local.jumpRidingTicks < 10 ? local.jumpRidingTicks * 0.1F : 0.8F + 2.0F / (local.jumpRidingTicks - 9) * 0.1F;
        }
        return NO_RIDING_JUMP;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Push out of blocks and minor collisions
    /**
     * The four calls in LocalPlayer.aiStep, in vanilla order. Does not move the position.
     */
    public void pushOutOfBlocks() {
        if (p.noPhysics || p.passenger)
            return;
        moveTowardsClosestSpace(p.x() - p.width() * 0.35, p.z() + p.width() * 0.35);
        moveTowardsClosestSpace(p.x() - p.width() * 0.35, p.z() - p.width() * 0.35);
        moveTowardsClosestSpace(p.x() + p.width() * 0.35, p.z() - p.width() * 0.35);
        moveTowardsClosestSpace(p.x() + p.width() * 0.35, p.z() + p.width() * 0.35);
    }

    /**
     * Vanilla LocalPlayer.moveTowardsClosestSpace: nearest free side, strict ties in W/E/N/S order.
     */
    public void moveTowardsClosestSpace(double x, double z) {
        int bx = VanillaMath.floor(x), by = VanillaMath.floor(p.y()), bz = VanillaMath.floor(z);
        if (!suffocatesAt(bx, by, bz))
            return;
        double xd = x - bx, zd = z - bz;
        double closest = Double.MAX_VALUE;
        int selected = -1;
        for (int side = 0; side < 4; side++) {
            double axisDistance = side < 2 ? xd : zd;
            double distanceToEdge = (side == 1 || side == 3) ? 1.0 - axisDistance : axisDistance;
            if (distanceToEdge < closest && !suffocatesAt(bx + SIDE_X[side], by, bz + SIDE_Z[side])) {
                closest = distanceToEdge;
                selected = side;
            }
        }
        if (selected != -1) {
            Vec3d old = p.deltaMovement;
            p.deltaMovement = selected < 2 ? new Vec3d(0.1 * SIDE_X[selected], old.y(), old.z()) : new Vec3d(old.x(), old.y(), 0.1 * SIDE_Z[selected]);
        }
    }

    /**
     * West, east, north, south.
     */
    private static final int[] SIDE_X = { -1, 1, 0, 0 }, SIDE_Z = { 0, 0, -1, 1 };

    /**
     * Vanilla tests an entire block column over the current box's height, deflated on every axis.
     */
    public boolean suffocatesAt(int x, int y, int z) {
        Aabb box = p.boundingBox();
        Aabb area = new Aabb(x + 1.0E-7, box.minY() + 1.0E-7, z + 1.0E-7, x + 1.0 - 1.0E-7, box.maxY() - 1.0E-7, z + 1.0 - 1.0E-7);
        boolean[] hit = { false };
        BlockCollisions.collect(env.blocks(), registry, shapeContext(), area, true, (state, bx, by, bz) -> {
            Tri result = env.suffocation(state, bx, by, bz);
            if (result == Tri.EITHER)
                unmodeled(Unmodeled.BLOCK_ENTITY_SUFFOCATION);
            return result == Tri.TRUE;
        }, (bx, by, bz, shape) -> hit[0] = true);
        return hit[0];
    }

    /**
     * LocalPlayer's 8-degree rule; its two thresholds are double and float respectively in bytecode.
     */
    @Override
    public boolean isHorizontalCollisionMinor(Vec3d movement) {
        float radians = p.yRot * (float) (Math.PI / 180.0);
        double sin = Trig.sin(radians), cos = Trig.cos(radians);
        double globalX = p.xxa * cos - p.zza * sin;
        double globalZ = p.zza * cos + p.xxa * sin;
        double inputLength = globalX * globalX + globalZ * globalZ;
        double movementLength = movement.x() * movement.x() + movement.z() * movement.z();
        if (inputLength < (double) 1.0E-5F || movementLength < (double) 1.0E-5F)
            return false;
        double dot = globalX * movement.x() + globalZ * movement.z();
        double angle = Math.acos(dot / Math.sqrt(inputLength * movementLength));
        return angle < 0.13962634F;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Moves outside the tick, auto-jump
    /**
     * {@code move(MoverType.SELF, delta)} outside the tick (a riptide's lift off the ground on release).
     */
    public void moveSelf(Vec3d delta) {
        move(MoverType.SELF, delta);
    }

    @Override
    protected void move(MoverType type, Vec3d delta) {
        double x = p.x(), z = p.z();
        super.move(type, delta);
        updateAutoJump((float) (p.x() - x), (float) (p.z() - z));
    }

    private VoxelShape shapeAt(EntityShapeContext context, int x, int y, int z) {
        return context.shape(env.blocks().getBlock(x, y, z), x, y, z);
    }

    private static boolean intersects(Aabb box, Vec3d a, Vec3d b) {
        return box.intersects(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()), Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    /**
     * Vanilla LocalPlayer.updateAutoJump. Collision order is entities first, then block cursor order.
     */
    void updateAutoJump(float xa, float za) {
        if (!local.send.autoJumpEnabled || local.autoJumpTime > 0 || !p.onGround || local.input.shift() || p.passenger || !isMoving() || getBlockJumpFactor() < 1.0)
            return;
        Vec3d begin = p.position, end = begin.add(xa, 0.0, za), diff = new Vec3d(xa, 0.0, za);
        float currentSpeed = (float) p.attributes.value(Attribute.MOVEMENT_SPEED);
        float distance = (float) diff.lengthSqr();
        if (distance <= 0.001F) {
            float ix = currentSpeed * local.vector.x(), iz = currentSpeed * local.vector.y();
            float sin = Trig.sin(p.yRot * (float) (Math.PI / 180.0)), cos = Trig.cos(p.yRot * (float) (Math.PI / 180.0));
            diff = new Vec3d(ix * cos - iz * sin, diff.y(), iz * cos + ix * sin);
            distance = (float) diff.lengthSqr();
            if (distance <= 0.001F)
                return;
        }
        float inverse = 1.0F / (float) Math.sqrt(distance);
        Vec3d direction = diff.scale(inverse);
        float yr = -p.yRot * (float) (Math.PI / 180.0) - (float) Math.PI;
        float xc = -Trig.cos(-p.xRot * (float) (Math.PI / 180.0));
        Vec3d facing = new Vec3d(Trig.sin(yr) * xc, Trig.sin(-p.xRot * (float) (Math.PI / 180.0)), Trig.cos(yr) * xc);
        if ((float) (facing.x() * direction.x() + facing.z() * direction.z()) < -0.15F)
            return;
        var context = shapeContext();
        int cx = VanillaMath.floor(p.x()), cy = VanillaMath.floor(p.boundingBox().maxY()), cz = VanillaMath.floor(p.z());
        if (!shapeAt(context, cx, cy, cz).isEmpty() || !shapeAt(context, cx, ++cy, cz).isEmpty())
            return;
        float height = 1.2F;
        if (p.effects.has(MobEffect.JUMP_BOOST))
            height += (p.effects.amplifier(MobEffect.JUMP_BOOST) + 1) * 0.75F;
        float ahead = Math.max(currentSpeed * 7.0F, 1.0F / inverse), width = p.width();
        Vec3d segEnd = end.add(direction.scale(ahead)), topEnd = segEnd.add(0.0, p.height(), 0.0);
        Aabb query = new Aabb(Math.min(begin.x(), topEnd.x()) - width, Math.min(begin.y(), topEnd.y()), Math.min(begin.z(), topEnd.z()) - width, Math.max(begin.x(), topEnd.x()) + width, Math.max(begin.y(), topEnd.y()), Math.max(begin.z(), topEnd.z()) + width);
        Vec3d segBegin = begin.add(0.0, 0.5099999904632568, 0.0);
        segEnd = segEnd.add(0.0, 0.5099999904632568, 0.0);
        Vec3d offset = new Vec3d(direction.y() * 0.0 - direction.z() * 1.0, direction.z() * 0.0 - direction.x() * 0.0, direction.x() * 1.0 - direction.y() * 0.0).scale(width * 0.5F);
        Vec3d lb = segBegin.subtract(offset), le = segEnd.subtract(offset), rb = segBegin.add(offset), re = segEnd.add(offset);
        List<VoxelShape> shapes = new ArrayList<>(env.entityCollisions(query));
        BlockCollisions.collect(env.blocks(), registry, context, query, (x, y, z, shape) -> shapes.add(shape));
        float obstacleHeight = Float.MIN_VALUE;
        outer: for (VoxelShape shape : shapes) {
            for (Aabb box : shape.toAabbs()) {
                if (!intersects(box, lb, le) && !intersects(box, rb, re))
                    continue;
                obstacleHeight = (float) box.maxY();
                int bx = VanillaMath.floor((box.minX() + box.maxX()) * 0.5), by = VanillaMath.floor((box.minY() + box.maxY()) * 0.5), bz = VanillaMath.floor((box.minZ() + box.maxZ()) * 0.5);
                for (int steps = 1; steps < height; steps++) {
                    VoxelShape above = shapeAt(context, bx, by + steps, bz);
                    if (!above.isEmpty()) {
                        obstacleHeight = (float) above.max(Axis.Y) + (by + steps);
                        if (obstacleHeight - p.y() > height)
                            return;
                    }
                    if (steps > 1 && !shapeAt(context, cx, ++cy, cz).isEmpty())
                        return;
                }
                break outer;
            }
        }
        if (obstacleHeight != Float.MIN_VALUE) {
            float difference = (float) (obstacleHeight - p.y());
            if (!(difference <= 0.5F) && !(difference > height))
                local.autoJumpTime = 1;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // sendPosition
    private void sendAbilities() {
        packets.add(Packets.abilities(p.abilities.flying));
    }

    /**
     * {@code LocalPlayer.sendPosition} with the shift ({@code sendShiftKeyState}, through 1.21.5) and input packets before
     * it: a passenger sends its rotation and, while it drives the root vehicle, the vehicle's move.
     */
    void sendPosition() {
        if (!data.clientLoaded)
            return;
        SendState sent = local.send;
        PlayerInput input = local.input;
        if (!sent.lastSentInput.equals(input)) {
            packets.add(Packets.input(input.mask()));
            sent.lastSentInput = input;
        }
        if (p.passenger) {
            packets.add(Packets.movement(MoveKind.ROT, 0.0, 0.0, 0.0, p.yRot, p.xRot, p.onGround, p.horizontalCollision));
            VehiclePacketState rootVehicle = local.rootVehicle;
            if (rootVehicle == null || rootVehicle.localAuthority() == Tri.EITHER) {
                unmodeled(Unmodeled.RIDING);
                return;
            }
            if (rootVehicle.localAuthority() == Tri.TRUE) {
                packets.add(rootVehicle.packet());
                sendIsSprintingIfNeeded();
            }
            return;
        }
        sendIsSprintingIfNeeded();
        if (!local.controlledCamera)
            return;
        double dx = p.x() - sent.xLast;
        double dy = p.y() - sent.yLast;
        double dz = p.z() - sent.zLast;
        // Vanilla subtracts floats before widening to double.
        double deltaYaw = p.yRot - sent.yRotLast;
        double deltaPitch = p.xRot - sent.xRotLast;
        sent.positionReminder++;
        boolean move = dx * dx + dy * dy + dz * dz > 2.0E-4 * 2.0E-4 || sent.positionReminder >= 20;
        boolean rot = deltaYaw != 0.0 || deltaPitch != 0.0;
        if (move || rot || sent.lastOnGround != p.onGround || sent.lastHorizontalCollision != p.horizontalCollision) {
            packets.add(Packets.movement(MoveKind.of(move, rot), move ? p.x() : 0.0, move ? p.y() : 0.0, move ? p.z() : 0.0, rot ? p.yRot : 0.0F, rot ? p.xRot : 0.0F, p.onGround, p.horizontalCollision));
        }
        if (move) {
            sent.xLast = p.x();
            sent.yLast = p.y();
            sent.zLast = p.z();
            sent.positionReminder = 0;
        }
        if (rot) {
            sent.yRotLast = p.yRot;
            sent.xRotLast = p.xRot;
        }
        sent.lastOnGround = p.onGround;
        sent.lastHorizontalCollision = p.horizontalCollision;
        sent.autoJumpEnabled = local.autoJumpOption;
    }

    private void sendIsSprintingIfNeeded() {
        if (p.sprinting != local.send.wasSprinting) {
            packets.add(Packets.command(p.sprinting ? ServerboundPlayerCommandPacket.Action.START_SPRINTING : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING, 0));
            local.send.wasSprinting = p.sprinting;
        }
    }
}
