package ru.minecomplex.mac.listener;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.entity.EntityDataItems;
import ru.minecomplex.mac.player.connection.PlayerConnectionContext;
import ru.minecomplex.mac.packet.MoveKind;
import ru.minecomplex.mac.packet.Packets;
import net.minecraft.world.entity.Relative;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.collision.Axis;
import ru.minecomplex.mac.entity.effect.EffectInstance;
import ru.minecomplex.mac.entity.effect.MobEffect;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.replica.CompensatedInventory;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.inventory.menu.MenuLayouts;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.entity.attribute.AttributeInstance;
import ru.minecomplex.mac.entity.attribute.AttributeModifier;
import ru.minecomplex.mac.entity.attribute.Identifier;
import ru.minecomplex.mac.entity.interpolation.EntityInterpolation;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.mechanics.movement.RotationUpdate;
import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.player.ClientItemUse;
import ru.minecomplex.mac.player.PlayerRecreation;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * The local-player part of {@code ClientPacketListener}: how the client applies a clientbound packet to its own
 * player, operation for operation, per release ({@code docs/research/architecture.md}). Only packets that concern the
 * local player reach these methods ({@link PlayerModel#isPlayerChange}).
 *
 * <p>Handlers run between client ticks, on the hypothesis that processed the packet. They return the packets the
 * handler itself sends (teleport and rotation replies); those are not part of any tick frame.
 */
public final class ClientPlayerHandlers {

    private final ClientItemUse itemUse;

    /**
     * {@code EntityType.PLAYER.updateInterval()}: the steps of a 26.3 stepped interpolation of the player.
     */
    private final int playerUpdateInterval;

    /**
     * The connection's profile: which {@code player_info} entry is the local player's own.
     */
    private final PlayerConnectionContext facts;

    public ClientPlayerHandlers() {
        this(new PlayerConnectionContext(null, null));
    }

    public ClientPlayerHandlers(PlayerConnectionContext facts) {
        this.facts = facts;
        this.itemUse = new ClientItemUse();
        this.playerUpdateInterval = ru.minecomplex.mac.entity.EntityTypes.get().type("minecraft:player").updateInterval();
    }

    /**
     * What applying one packet produced.
     *
     * @param replies      packets the handler sends, in order
     * @param mayAbortDestroy 26.3 teleport: {@code MultiPlayerGameMode.stopDestroyBlock()} additionally sends
     *                     {@code PlayerAction(ABORT_DESTROY_BLOCK)} when the client was mining (state not modelled here)
     * @param gaps         facts this packet made unresolved for the hypothesis
     * @param resolves     earlier gaps this packet resolved (removed before {@code gaps} are added)
     * @param player       the new LocalPlayer's state after {@code login}/{@code respawn}, otherwise {@code null}
     * @param local        the new LocalPlayer's local state, with {@code player}
     */
    public record Result(List<Object> replies, boolean mayAbortDestroy, Set<Unmodeled> gaps, Set<Unmodeled> resolves, PlayerState player, LocalPlayerState local) {

        static final Result NONE = new Result(List.of(), false, Set.of(), Set.of());

        public Result(List<Object> replies, boolean mayAbortDestroy, Set<Unmodeled> gaps) {
            this(replies, mayAbortDestroy, gaps, Set.of());
        }

        public Result(List<Object> replies, boolean mayAbortDestroy, Set<Unmodeled> gaps, Set<Unmodeled> resolves) {
            this(replies, mayAbortDestroy, gaps, resolves, null, null);
        }

        static Result recreated(PlayerRecreation.Recreated recreated) {
            return new Result(List.of(), false, recreated.gaps(), recreated.resolves(), recreated.player(), recreated.local());
        }

        static Result gap(Unmodeled gap) {
            return new Result(List.of(), false, Set.of(gap));
        }
    }

    /**
     * Applies one change to the own player. After {@code login}/{@code respawn} the result carries the new player's
     * states, which replace {@code p} and {@code local}.
     */
    public Result apply(PlayerState p, LocalPlayerState local, ClientPlayerData data, Object event) {
        return apply(p, local, data, event, ClientPlayerActions.Target.UNKNOWN);
    }

    /**
     * {@link #apply} with the client world's entities ({@code mount_screen_open} names one).
     */
    public Result apply(PlayerState p, LocalPlayerState local, ClientPlayerData data, Object event, ClientPlayerActions.Target target) {
        Result result = change(p, local, data, event, target);
        Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class), resolves = EnumSet.noneOf(Unmodeled.class);
        gaps.addAll(result.gaps());
        resolves.addAll(result.resolves());
        if (data.infoIdentityUncertain)
            gaps.add(Unmodeled.PLAYER_INFO_IDENTITY);
        ClientItemUse.ambiguity(data, gaps);
        ClientItemUse.useGaps(data, gaps, resolves);
        ClientPlayerActions.inventory(result.player() != null ? result.player() : p, data, gaps, resolves);
        return new Result(result.replies(), result.mayAbortDestroy(), gaps, resolves, result.player(), result.local());
    }

    /**
     * The entity types of {@code AbstractHorse} (camel husk from 1.21.11; types a release lacks never appear).
     */
    private static final Set<String> HORSES = Set.of("minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:skeleton_horse", "minecraft:zombie_horse", "minecraft:llama", "minecraft:trader_llama", "minecraft:camel", "minecraft:camel_husk");

    private static Set<Unmodeled> with(Set<Unmodeled> set, Unmodeled more) {
        EnumSet<Unmodeled> result = EnumSet.of(more);
        result.addAll(set);
        return result;
    }

    private Result change(PlayerState p, LocalPlayerState local, ClientPlayerData data, Object event, ClientPlayerActions.Target target) {
        return switch(event) {
            case ClientboundPlayerPositionPacket teleport ->
                teleport(p, teleport);
            case ClientboundTeleportEntityPacket teleport ->
                entityTeleport(p, data, teleport, target);
            case ClientboundPlayerRotationPacket rotation ->
                rotate(p, rotation);
            case ClientboundSetEntityMotionPacket velocity ->
                {
                    // lerpMotion(Vec3) / lerpMotion(x, y, z): setDeltaMovement
                    var motion = velocity.movement();
                    setDeltaMovement(p, new Vec3d(motion.x(), motion.y(), motion.z()));
                    yield Result.NONE;
                }
            case ClientboundExplodePacket explosion ->
                {
                    explosion(p, explosion);
                    yield Result.NONE;
                }
            case ClientboundPlayerAbilitiesPacket abilities ->
                {
                    p.abilities.flying = abilities.isFlying();
                    p.abilities.instabuild = abilities.canInstabuild();
                    p.abilities.invulnerable = abilities.isInvulnerable();
                    p.abilities.mayfly = abilities.canFly();
                    p.abilities.flyingSpeed = abilities.getFlyingSpeed();
                    p.abilities.walkingSpeed = abilities.getWalkingSpeed();
                    yield Result.NONE;
                }
            case ClientboundGameEventPacket game ->
                {
                    if (game.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE) {
                        GameType mode = GameType.byId(floor(game.getParam() + 0.5F));
                        data.localMode = mode;
                        p.abilities.updateGameMode(mode);
                    }
                    yield Result.NONE;
                }
            case ClientboundPlayerInfoUpdatePacket info ->
                playerInfo(p, data, info);
            case ClientboundPlayerInfoRemovePacket ignored ->
                {
                    data.removePlayerInfo();
                    p.spectator = data.peekPlayerInfoMode() == GameType.SPECTATOR;
                    yield Result.NONE;
                }
            case ClientboundUpdateMobEffectPacket update ->
                {
                    MobEffect effect = effect(update.getEffect().unwrapKey().orElseThrow().identifier().toString());
                    if (effect != null) {
                        // new MobEffectInstance(effect, duration, amplifier, ambient, visible, showIcon, null); forceAddEffect
                        p.effects.replace(effect, new EffectInstance(update.getEffectDurationTicks(), update.getEffectAmplifier(), update.isEffectAmbient(), update.isEffectVisible(), update.effectShowsIcon()));
                    }
                    yield Result.NONE;
                }
            case ClientboundRemoveMobEffectPacket remove ->
                {
                    MobEffect effect = effect(remove.effect().unwrapKey().orElseThrow().identifier().toString());
                    if (effect != null) {
                        p.effects.remove(effect);
                    }
                    yield Result.NONE;
                }
            case ClientboundUpdateAttributesPacket attributes ->
                {
                    // a new scale reaches the cached dimensions at the end of the next tick (refreshDirtyAttributes)
                    attributes(p, attributes);
                    yield Result.NONE;
                }
            case ClientboundSetHealthPacket health ->
                {
                    // the food level as the server sets it: an undecided use's food is known again
                    data.useOutcomeFood = false;
                    Result result = health(p, data, health);
                    yield data.useOutcomeOpen() ? result : new Result(result.replies(), result.mayAbortDestroy(), result.gaps(), with(result.resolves(), Unmodeled.ITEM_USE_OUTCOME));
                }
            case ClientboundSetEntityDataPacket synced ->
                syncedData(p, data, synced, target);
            case ClientboundAnimatePacket animation ->
                animate(p, data, animation, target);
            case ClientboundContainerSetContentPacket content ->
                inventory(p, data, containerContent(data, content));
            case ClientboundContainerSetSlotPacket slot ->
                inventory(p, data, containerSlot(data.inventory, slot));
            case ClientboundSetPlayerInventoryPacket slot ->
                {
                    data.inventory.setItem(slot.slot(), Stacks.of(slot.contents()));
                    yield inventory(p, data, Result.NONE);
                }
            case ClientboundSetHeldSlotPacket held ->
                {
                    if (CompensatedInventory.isHotbarSlot(held.slot())) {
                        data.inventory.setSelected(held.slot());
                    }
                    yield inventory(p, data, Result.NONE);
                }
            case ClientboundSetCursorItemPacket cursor ->
                {
                    // the open menu's carried stack; ignored while the creative inventory screen is open (only in creative)
                    data.inventory.setCarriedClaimed(Stacks.of(cursor.contents()), data.localMode == GameType.CREATIVE);
                    // after an uncertain mount screen either menu may receive it
                    yield data.inventory.mountPending() ? Result.gap(Unmodeled.INVENTORY_CONTAINER) : Result.NONE;
                }
            case ClientboundOpenScreenPacket open ->
                {
                    MenuLayouts.Layout layout = MenuLayouts.layout(net.minecraft.core.registries.BuiltInRegistries.MENU.getId(open.getType()));
                    if (layout == null) {
                        // MenuType.STREAM_CODEC cannot decode an unregistered id: the client disconnects
                        throw new IllegalArgumentException("Unknown menu type " + open.getType());
                    }
                    data.inventory.openContainer(open.getContainerId(), layout.slots(), layout.key());
                    yield Result.NONE;
                }
            case ClientboundMountScreenOpenPacket mount ->
                {
                    // menu becomes the open menu; another entity changes nothing
                    String type = target.entityType(mount.getEntityId());
                    if (type == null) {
                        data.inventory.mountScreenOpen(mount.getContainerId());
                        yield Result.gap(Unmodeled.INVENTORY_CONTAINER);
                    }
                    boolean horse = HORSES.contains(type), nautilus = type.equals("minecraft:nautilus") || type.equals("minecraft:zombie_nautilus");
                    if (horse || nautilus) {
                        data.inventory.openContainer(mount.getContainerId(), MenuLayouts.mountLayout(horse, mount.getInventoryColumns()), horse ? "mount:horse" : "mount:nautilus");
                    }
                    yield Result.NONE;
                }
            case ClientboundContainerClosePacket ignored ->
                {
                    data.inventory.closeContainer();
                    yield Result.NONE;
                }
            case ClientboundEntityEventPacket entityEvent ->
                {
                    // Player.handleEntityEvent(9): completeUsingItem; other events change nothing the model reads
                    yield entityEvent.getEventId() == Packets.FINISHED_USING_ITEM ? inventory(p, data, new Result(List.of(), false, itemUse.complete(p, data))) : Result.NONE;
                }
            case ClientboundCooldownPacket cooldown ->
                {
                    ClientItemUse.addCooldown(data, cooldown.cooldownGroup().toString(), cooldown.duration());
                    yield data.useOutcomeOpen() ? Result.NONE : new Result(List.of(), false, Set.of(), Set.of(Unmodeled.ITEM_USE_OUTCOME));
                }
            case ClientboundLoginPacket join ->
                Result.recreated(PlayerRecreation.login(p, local, data, join));
            case ClientboundRespawnPacket respawn ->
                respawn(p, local, data, respawn);
            default ->
                throw new IllegalArgumentException("Not a local player change: " + event);
        };
    }

    /**
     * {@code handleMovePlayer} → {@code setValuesFromPositionPacket(change, relatives, player, false)} unless the player
     * is a passenger, then the replies.
     */
    private Result teleport(PlayerState p, ClientboundPlayerPositionPacket change) {
        int relatives = net.minecraft.world.entity.Relative.pack(change.relatives());
        var delta = change.change().deltaMovement();
        if (!p.passenger) {
            setValuesFromPositionPacket(p, change.change().position().x, change.change().position().y(), change.change().position().z, delta.x(), delta.y(), delta.z(), change.change().yRot(), change.change().xRot(), relatives, false);
        }
        // an applied teleport with absolute x, y and z fixes the position whatever it was
        boolean absolute = !p.passenger && !ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.X) && !ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Y) && !ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Z);
        Set<Unmodeled> resolves = absolute ? Set.of(Unmodeled.RESPAWN_POSITION, Unmodeled.BED_STAND_UP) : Set.of();
        {
            return new Result(List.of(new ServerboundAcceptTeleportationPacket(change.id(), p.x(), p.y(), p.z(), p.yRot, p.xRot)), true, Set.of(), resolves);
        }
    }

    /**
     * {@code handleTeleportEntity} as far as it reaches the own player.
     * <ul>
     *     <li>its own id: {@code setValuesFromPositionPacket(change, relatives, player, interpolate)} with interpolate =
     *     {@code isTickingEntity(player)}, always true for a player in the level (an interpolation within 64 blocks of the
     *     current values, a snap beyond), then {@code setOnGround};</li>
     *     <li>no entity with the id, which is {@code removedPlayerVehicleId}: the same without interpolation and without
     *     {@code setOnGround}, then (through 26.2) a {@code PosRot} reply with the transform of the player;</li>
     *     <li>any other entity: not the player's.</li>
     * </ul>
     */
    private Result entityTeleport(PlayerState p, ClientPlayerData data, ClientboundTeleportEntityPacket teleport, ClientPlayerActions.Target target) {
        var values = teleport.change();
        var position = values.position();
        var delta = values.deltaMovement();
        int relatives = net.minecraft.world.entity.Relative.pack(teleport.relatives());
        if (teleport.id() == data.entityId) {
            setValuesFromPositionPacket(p, position.x(), position.y(), position.z(), delta.x(), delta.y(), delta.z(), values.yRot(), values.xRot(), relatives, true);
            p.onGround = teleport.onGround();
            return Result.NONE;
        }
        Tri former = target.formerVehicle(teleport.id());
        if (former == Tri.FALSE)
            return Result.NONE;
        // the client knows whether this id names its former vehicle; this hypothesis does not
        if (former == Tri.EITHER)
            return Result.gap(Unmodeled.ENTITY_MOTION);
        setValuesFromPositionPacket(p, position.x(), position.y(), position.z(), delta.x(), delta.y(), delta.z(), values.yRot(), values.xRot(), relatives, false);
        return Result.NONE;
    }

    /**
     * {@code ClientPacketListener.setValuesFromPositionPacket(change, relatives, player, interpolate)}. The current values
     * are {@code PositionMoveRotation.of(player)} (1.21.4: {@code ofEntityUsingLerpTarget}): the transform of the player,
     * or its own interpolation target while a {@code teleport_entity} interpolates it
     * ({@link EntityInterpolation#clientValues}), and its known movement (its delta movement). Within 64 blocks of them an
     * interpolating call starts an interpolation ({@code lerpTo(…, 3)}, {@code moveOrInterpolateTo}); otherwise the
     * transform is set and the old position moved alike.
     *
     * @return whether it interpolated
     */
    private boolean setValuesFromPositionPacket(PlayerState p, double x, double y, double z, double deltaX, double deltaY, double deltaZ, float yRot, float xRot, int relatives, boolean interpolate) {
        EntityInterpolation.Values current = p.interpolation != null ? p.interpolation.clientValues(p) : new EntityInterpolation.Values(p.position, p.yRot, p.xRot);
        Vec3d absolutePosition = absolutePosition(current.position(), x, y, z, relatives);
        float absoluteYRot = RotationUpdate.absoluteYRot(current.yRot(), yRot, ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Y_ROT));
        float absoluteXRot = RotationUpdate.absoluteXRot(current.xRot(), xRot, ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.X_ROT));
        Vec3d rotatedCurrentMovement = p.deltaMovement;
        if (ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.ROTATE_DELTA)) {
            float diffYRot = current.yRot() - absoluteYRot;
            float diffXRot = current.xRot() - absoluteXRot;
            rotatedCurrentMovement = xRot(rotatedCurrentMovement, (float) Math.toRadians(diffXRot));
            rotatedCurrentMovement = yRot(rotatedCurrentMovement, (float) Math.toRadians(diffYRot));
        }
        Vec3d absoluteDeltaMovement = new Vec3d(delta(rotatedCurrentMovement.x(), deltaX, ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.DELTA_X)), delta(rotatedCurrentMovement.y(), deltaY, ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.DELTA_Y)), delta(rotatedCurrentMovement.z(), deltaZ, ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.DELTA_Z)));
        if (interpolate && !(current.position().distanceToSqr(absolutePosition) > 4096.0)) {
            if (p.interpolation == null)
                p.interpolation = new EntityInterpolation(true, playerUpdateInterval);
            p.interpolation.interpolateTo(p, absolutePosition, null, absoluteYRot, absoluteXRot);
            setDeltaMovement(p, absoluteDeltaMovement);
            return true;
        }
        // Entity.setPos → setPosRaw keeps the old vector when every coordinate is numerically equal
        p.position = VanillaMath.position(p.position, absolutePosition);
        setDeltaMovement(p, absoluteDeltaMovement);
        p.yRot = RotationUpdate.storeYRot(p.yRot, absoluteYRot);
        p.xRot = RotationUpdate.storeXRot(p.xRot, absoluteXRot);
        // setOldPosAndRot(calculateAbsolute(new PositionMoveRotation(oldPosition(), ZERO, yRotO, xRotO), ...)):
        // the position part does not depend on the rotation; old rotations are not read by movement
        p.oldPosition = absolutePosition(p.oldPosition, x, y, z, relatives);
        return false;
    }

    /**
     * Position part of {@code PositionMoveRotation.calculateAbsolute}: {@code offset + change}, offset 0.0 when absolute.
     */
    private static Vec3d absolutePosition(Vec3d source, double x, double y, double z, int relatives) {
        double offsetX = ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.X) ? source.x() : 0.0;
        double offsetY = ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Y) ? source.y() : 0.0;
        double offsetZ = ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Z) ? source.z() : 0.0;
        return new Vec3d(offsetX + x, offsetY + y, offsetZ + z);
    }

    /**
     * {@code PositionMoveRotation.calculateDelta}.
     */
    private static double delta(double currentDelta, double deltaChange, boolean relative) {
        return relative ? currentDelta + deltaChange : deltaChange;
    }

    /**
     * {@code handleRotatePlayer}. Up to 1.21.8 the packet is absolute and goes straight to {@code setYRot/setXRot};
     * from 1.21.9 it goes through {@code calculateAbsolute} with the packet's relative flags (which clamps the pitch).
     */
    private Result rotate(PlayerState p, ClientboundPlayerRotationPacket rotation) {
        float yRot = RotationUpdate.rotationPacketYRot(p.yRot, rotation.yRot(), rotation.relativeY());
        float xRot = RotationUpdate.rotationPacketXRot(p.xRot, rotation.xRot(), rotation.relativeX());
        p.yRot = RotationUpdate.storeYRot(p.yRot, yRot);
        p.xRot = RotationUpdate.storeXRot(p.xRot, xRot);
        return new Result(List.of(Packets.movement(MoveKind.ROT, 0.0, 0.0, 0.0, p.yRot, p.xRot, false, false)), false, Set.of());
    }

    /**
     * {@code handleExplosion}: {@code addDeltaMovement(knockback)} up to 26.2, {@code pushFromExplosion} → {@code push(Vec3)}
     * in 26.3. Both add the vector to the delta movement; from 1.21.11 both skip a non-finite knockback and
     * {@code setDeltaMovement} skips a non-finite sum.
     */
    private void explosion(PlayerState p, ClientboundExplodePacket explosion) {
        var motion = explosion.playerKnockback().orElse(null);
        if (motion == null) {
            return;
        }
        Vec3d knockback = new Vec3d(motion.x(), motion.y(), motion.z());
        if (!finite(knockback)) {
            return;
        }
        setDeltaMovement(p, p.deltaMovement.add(knockback));
    }

    /**
     * {@code applyPlayerInfoUpdate(UPDATE_GAME_MODE)} for the own profile: {@code onGameModeChanged} runs when the mode
     * differs from the entry's (spectator clears the vertical delta movement), then the entry takes the mode.
     */
    private Result playerInfo(PlayerState p, ClientPlayerData data, ClientboundPlayerInfoUpdatePacket info) {
        var actions = info.actions();
        int ownMode = 0;
        for (var entry : info.entries()) {
            if (facts.localUuid().equals(entry.profileId())) {
                ownMode = Packets.gameModeId(entry.gameMode());
                break;
            }
        }
        if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) && !data.infoPresent) {
            data.infoPresent = true;
            data.infoMode = GameType.SURVIVAL;
        }
        if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE) && data.infoPresent) {
            GameType mode = GameType.byId(ownMode);
            if (data.infoMode != mode && mode == GameType.SPECTATOR) {
                setDeltaMovement(p, p.deltaMovement.with(Axis.Y, 0.0));
            }
            data.infoMode = mode;
        }
        p.spectator = data.peekPlayerInfoMode() == GameType.SPECTATOR;
        return Result.NONE;
    }

    /**
     * {@code handleUpdateAttributes}: attributes movement does not read change nothing it reads.
     */
    private static void attributes(PlayerState p, ClientboundUpdateAttributesPacket packet) {
        for (ru.minecomplex.mac.entity.attribute.AttributeSnapshot snapshot : ru.minecomplex.mac.entity.attribute.AttributeSnapshot.of(packet)) {
            Attribute attribute = attribute(snapshot.attribute());
            if (attribute == null) {
                continue;
            }
            AttributeInstance instance = p.attributes.get(attribute);
            instance.setBaseValue(snapshot.base());
            instance.removeModifiers();
            for (ru.minecomplex.mac.entity.attribute.AttributeSnapshot.Modifier modifier : snapshot.modifiers()) {
                instance.addModifier(new AttributeModifier(Identifier.parse(modifier.id()), modifier.amount(), operation(modifier.operation())));
            }
        }
    }

    /**
     * {@code handleSetEntityData} → {@code SynchedEntityData.assignValues} on the own player: each value is stored as
     * sent, then {@code onSyncedDataUpdated} runs for it.
     * <ul>
     *     <li>shared flags: {@code isSprinting}, {@code isSwimming} (the flag) and {@code isFallFlying} read bits 3, 4
     *     and 7; only the flag changes, not the sprint speed modifier. Bit 1 does not matter: {@code LocalPlayer
     *     .isShiftKeyDown()} reads the keys in every supported release. The elytra callback only plays a sound.</li>
     *     <li>pose: {@code refreshDimensions()}, which also picks up a pending {@code scale} change.</li>
     *     <li>living-entity flags: bit 2 is {@code isAutoSpinAttack}; bit 0 makes {@code LocalPlayer} start using the
     *     held item, which is not modelled.</li>
     *     <li>health: stored without clamping.</li>
     * </ul>
     */
    private Result syncedData(PlayerState p, ClientPlayerData data, ClientboundSetEntityDataPacket packet, ClientPlayerActions.Target target) {
        Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
        Set<Unmodeled> resolves = EnumSet.noneOf(Unmodeled.class);
        var synced = EntityDataItems.of(packet);
        if (synced.get(EntityDataItems.SHARED_FLAGS) instanceof Byte flags) {
            p.sprinting = (flags & 1 << 3) != 0;
            p.swimming = (flags & 1 << 4) != 0;
            p.fallFlying = (flags & 1 << 7) != 0;
        }
        if (synced.get(EntityDataItems.NO_GRAVITY) instanceof Boolean noGravity) {
            p.noGravity = noGravity;
        }
        if (synced.containsKey(EntityDataItems.POSE)) {
            Pose pose = synced.get(EntityDataItems.POSE) instanceof Pose known ? known : null;
            if (pose == null) {
                gaps.add(Unmodeled.ENTITY_POSE);
            } else {
                p.pose = pose;
                // assignValues calls onSyncedDataUpdated also for an unchanged value: refreshDimensions
                p.refreshDimensions();
                resolves.add(Unmodeled.ENTITY_POSE);
            }
        }
        if (synced.get(EntityDataItems.LIVING_FLAGS) instanceof Byte flags) {
            p.autoSpinAttack = (flags & 1 << 2) != 0;
            // bits 0 and 1: LocalPlayer.onSyncedDataUpdated starts the use of that hand or stops it
            itemUse.flags(data, flags);
        }
        if (synced.get(EntityDataItems.HEALTH) instanceof Float health) {
            data.health = health;
            health(p, data.health);
        }
        if (synced.get(EntityDataItems.SLEEPING_POS) instanceof java.util.Optional<?> position) {
            // LivingEntity.onSyncedDataUpdated(SLEEPING_POS_ID): a client puts a sleeper on its bed
            var bed = position.orElse(null) instanceof ru.minecomplex.mac.world.BlockPosition at ? at : null;
            p.sleepingPos = bed != null ? new int[] { bed.x(), bed.y(), bed.z() } : null;
            p.sleeping = bed != null;
            if (bed != null && !setPosToBed(p, bed.x(), bed.y(), bed.z(), target)) {
                gaps.add(Unmodeled.BED_STAND_UP);
            }
        }
        return new Result(List.of(), false, gaps, resolves);
    }

    /**
     * {@code LivingEntity.setPosToBed}: through 26.2 the bed block plus {@code (0.5, 0.6875, 0.5)}; from 26.3
     * ({@code BED_SLEEP_HEIGHT}) only on a bed ({@code AbstractBedBlock}), at its sleep height plus 0.125.
     *
     * @return false when the bed's sleep height is not known
     */
    private boolean setPosToBed(PlayerState p, int x, int y, int z, ClientPlayerActions.Target target) {
        double height;
        {
            Double sleepHeight = target.sleepHeight(x, y, z);
            if (sleepHeight == null)
                return true;
            if (sleepHeight.isNaN())
                return false;
            height = y + sleepHeight + 0.125;
        }
        p.position = ru.minecomplex.mac.util.math.VanillaMath.position(p.position, new ru.minecomplex.mac.util.math.Vec3d(x + 0.5, height, z + 0.5));
        return true;
    }

    /**
     * {@code handleAnimate}: the wake-up action (through 26.2 action 2, from 26.3 action 0) runs
     * {@code Player.stopSleepInBed} → {@code LivingEntity.stopSleeping}: with its sleeping position in a loaded chunk on a
     * bed, the player stands up at {@code findStandUpPosition} turned to the bed, pitch 0
     * ({@link ru.minecomplex.mac.world.BedStandUp}; not known: {@link Unmodeled#BED_STAND_UP} until the server's teleport);
     * then the standing pose (dimensions refreshed) and no sleeping position. The other actions (swings, critical-hit
     * particles) do not move the player.
     */
    private Result animate(PlayerState p, ClientPlayerData data, ClientboundAnimatePacket animation, ClientPlayerActions.Target target) {
        if (animation.getId() != data.entityId || animation.getAction() != ClientboundAnimatePacket.WAKE_UP)
            return Result.NONE;
        Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
        int[] bed = p.sleepingPos;
        if (bed != null) {
            var outcome = target.stopSleeping(bed[0], bed[1], bed[2], p.yRot, gaps);
            if (!outcome.known()) {
                gaps.add(Unmodeled.BED_STAND_UP);
            } else if (outcome.moves()) {
                p.position = outcome.position();
                p.yRot = outcome.yRot();
                p.xRot = 0.0F;
            }
        }
        p.pose = Pose.STANDING;
        p.refreshDimensions();
        p.sleeping = false;
        p.sleepingPos = null;
        return new Result(List.of(), false, gaps, Set.of());
    }

    /**
     * {@code handleSetHealth}: {@code hurtTo(health)} stores {@code Mth.clamp(health, 0, maxHealth)}; food and saturation
     * are replaced ({@link #health(PlayerState, float)}). A later lower maximum clamps the stored health only in the
     * next tick's {@code refreshDirtyAttributes} ({@link #refreshDirtyAttributes}).
     */
    private static Result health(PlayerState p, ClientPlayerData data, ClientboundSetHealthPacket health) {
        // LocalPlayer.hurtTo -> setHealth: Mth.clamp(health, 0.0F, getMaxHealth())
        float max = (float) p.attributes.value(Attribute.MAX_HEALTH);
        data.health = health.getHealth() < 0.0F ? 0.0F : Math.min(health.getHealth(), max);
        data.food = health.getFood();
        data.saturation = health.getSaturation();
        health(p, data.health);
        return Result.NONE;
    }

    /**
     * {@code isAlive()} is {@code health > 0}, {@code isDeadOrDying()} {@code health <= 0}: a NaN health is neither.
     */
    public static void health(PlayerState p, float health) {
        p.alive = health > 0.0F;
        p.deadOrDying = health <= 0.0F;
    }

    /**
     * The health part of {@code LivingEntity.refreshDirtyAttributes} at the end of {@code LivingEntity.tick}: a stored
     * health above the current maximum is set to it. The prediction layer calls this in the tick phase.
     */
    public static void refreshDirtyAttributes(PlayerState p, ClientPlayerData data) {
        float max = (float) p.attributes.value(Attribute.MAX_HEALTH);
        if (data.health > max) {
            data.health = max;
        }
    }

    /**
     * {@code handleContainerContent}: container 0 is the inventory menu ({@code initializeContents}: the first
     * {@code items.size()} menu slots, the carried stack, the state id); a full content resolves an earlier
     * {@link Unmodeled#INVENTORY_CONTAINER}. The open container's menu ({@code containerMenu.initializeContents})
     * changes the player inventory slots behind its layout ({@link MenuLayouts}); an unknown layout or an uncertain
     * mount screen leaves them unknown. Other ids are ignored.
     */
    private Result containerContent(ClientPlayerData data, ClientboundContainerSetContentPacket content) {
        CompensatedInventory inventory = data.inventory;
        if (content.containerId() == 0) {
            List<ItemStack> stacks = content.items();
            for (int slot = 0; slot < stacks.size(); slot++) {
                inventory.setMenuItem(slot, Stacks.of(stacks.get(slot)));
            }
            inventory.setInventoryCarried(Stacks.of(content.carriedItem()));
            inventory.setStateId(content.stateId());
            if (stacks.size() < CompensatedInventory.MENU_SIZE)
                return Result.NONE;
            inventory.inventoryContentKnown();
            data.useOutcomeInventory = false;
            return new Result(List.of(), false, Set.of(), data.useOutcomeOpen() ? Set.of(Unmodeled.INVENTORY_CONTAINER) : Set.of(Unmodeled.INVENTORY_CONTAINER, Unmodeled.ITEM_USE_OUTCOME));
        }
        if (!inventory.mayBeOpen(content.containerId())) {
            return Result.NONE;
        }
        if (!inventory.openMenuKnown()) {
            return Result.gap(Unmodeled.INVENTORY_CONTAINER);
        }
        List<ItemStack> stacks = content.items();
        for (int slot = 0; slot < stacks.size(); slot++) {
            inventory.setOpenMenuItem(slot, Stacks.of(stacks.get(slot)));
        }
        inventory.setContainerCarried(Stacks.of(content.carriedItem()));
        inventory.setOpenStateId(content.stateId());
        return Result.NONE;
    }

    /**
     * {@code handleContainerSetSlot}: as {@link #containerContent} for one slot ({@code menu.setItem(slot, stateId, item)}).
     */
    private Result containerSlot(CompensatedInventory inventory, ClientboundContainerSetSlotPacket slot) {
        if (slot.getContainerId() == 0) {
            inventory.setMenuItem(slot.getSlot(), Stacks.of(slot.getItem()));
            inventory.setStateId(slot.getStateId());
            return Result.NONE;
        }
        if (!inventory.mayBeOpen(slot.getContainerId())) {
            return Result.NONE;
        }
        if (!inventory.openMenuKnown()) {
            return Result.gap(Unmodeled.INVENTORY_CONTAINER);
        }
        inventory.setOpenMenuItem(slot.getSlot(), Stacks.of(slot.getItem()));
        inventory.setOpenStateId(slot.getStateId());
        return Result.NONE;
    }

    /**
     * Equipment the engine reads directly (leather boots on powder snow) and whether a drop may have emptied the main hand.
     */
    private static Result inventory(PlayerState p, ClientPlayerData data, Result result) {
        p.wearingLeatherBoots = data.inventory.walksOnPowderSnow();
        Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class), resolves = EnumSet.noneOf(Unmodeled.class);
        gaps.addAll(result.gaps());
        resolves.addAll(result.resolves());
        ClientPlayerActions.mainHand(data, gaps, resolves);
        return new Result(result.replies(), result.mayAbortDestroy(), gaps, resolves, result.player(), result.local());
    }

    /**
     * {@code handleRespawn}: an open container menu of the old player is closed first ({@code LocalPlayer.closeContainer}
     * sends {@code container_close} with its id), then the new player is created. After an uncertain mount screen
     * whether and which id is sent is unknown.
     */
    private Result respawn(PlayerState p, LocalPlayerState local, ClientPlayerData data, ClientboundRespawnPacket respawn) {
        CompensatedInventory old = data.inventory;
        List<Object> replies = old.mountPending() || old.openContainerId() == 0 ? List.of() : List.of(new ServerboundContainerClosePacket(old.openContainerId()));
        Result recreated = Result.recreated(PlayerRecreation.respawn(p, local, data, respawn));
        Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
        gaps.addAll(recreated.gaps());
        if (old.mountPending()) {
            gaps.add(Unmodeled.INVENTORY_CONTAINER);
        }
        return new Result(replies, false, gaps, recreated.resolves(), recreated.player(), recreated.local());
    }

    /**
     * {@code Entity.setDeltaMovement(Vec3)}: non-finite vectors are ignored from 1.21.11.
     */
    private void setDeltaMovement(PlayerState p, Vec3d movement) {
        if (finite(movement)) {
            p.deltaMovement = movement;
        }
    }

    /**
     * {@code Vec3.xRot}: float sine/cosine widened to double.
     */
    private Vec3d xRot(Vec3d v, float radians) {
        float cos = Trig.cos(radians);
        float sin = Trig.sin(radians);
        return new Vec3d(v.x(), v.y() * cos + v.z() * sin, v.z() * cos - v.y() * sin);
    }

    /**
     * {@code Vec3.yRot}.
     */
    private Vec3d yRot(Vec3d v, float radians) {
        float cos = Trig.cos(radians);
        float sin = Trig.sin(radians);
        return new Vec3d(v.x() * cos + v.z() * sin, v.y(), v.z() * cos - v.x() * sin);
    }

    private static boolean finite(Vec3d v) {
        return Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
    }

    /**
     * {@code Mth.floor(float)}: {@code (int)Math.floor} in 26.x, an int-cast form before. They differ only below
     * {@code Integer.MIN_VALUE}, where both results are outside the game mode range (survival).
     */
    private static int floor(float value) {
        return (int) Math.floor(value);
    }

    static MobEffect effect(String key) {
        return switch(key) {
            case "minecraft:jump_boost" ->
                MobEffect.JUMP_BOOST;
            case "minecraft:levitation" ->
                MobEffect.LEVITATION;
            case "minecraft:slow_falling" ->
                MobEffect.SLOW_FALLING;
            case "minecraft:weaving" ->
                MobEffect.WEAVING;
            case "minecraft:dolphins_grace" ->
                MobEffect.DOLPHINS_GRACE;
            case "minecraft:blindness" ->
                MobEffect.BLINDNESS;
            default ->
                null;
        };
    }

    public static Attribute attribute(String key) {
        Identifier id = Identifier.parse(key);
        for (Attribute attribute : Attribute.values()) {
            if (attribute.id().equals(id)) {
                return attribute;
            }
        }
        return null;
    }

    private static AttributeModifier.Operation operation(int id) {
        return switch(id) {
            case 0 ->
                AttributeModifier.Operation.ADD_VALUE;
            case 1 ->
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case 2 ->
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default ->
                throw new IllegalArgumentException("Unknown attribute operation " + id);
        };
    }
}
