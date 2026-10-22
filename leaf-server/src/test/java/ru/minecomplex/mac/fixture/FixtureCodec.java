package ru.minecomplex.mac.fixture;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;

import ru.minecomplex.mac.listener.MultiBlockChangeBitRepackHandler;

import java.util.ArrayList;
import java.util.Arrays;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.TreeMap;
import java.util.UUID;

import ru.minecomplex.mac.player.connection.PlayerConnectionContext;
import ru.minecomplex.mac.packet.NativeRuntime;
import ru.minecomplex.mac.packet.Packets;
import ru.minecomplex.mac.packet.UnhandledPacket;
import ru.minecomplex.mac.fixture.inbound.*;
import ru.minecomplex.mac.fixture.outbound.*;

import net.minecraft.world.entity.PositionPath;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import net.minecraft.nbt.CompoundTag;

import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.entity.EquipmentSlot;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Relative;
import net.minecraft.network.protocol.game.VecDelta;

import net.minecraft.world.level.dimension.DimensionType;

/**
 * Test packets as records: tests and oracle files describe packets with the records of {@code fixture.inbound} /
 * {@code fixture.outbound} (the values the model reads); {@link #decode} builds the native 26.3 wrapper the transport
 * would decode for such a packet, and {@link #encodeInbound}/{@link #encodeOutbound} read a wrapper (a predicted packet)
 * back into a record for comparison. Test code only: the core reads native 26.3 wrappers.
 */
public final class FixtureCodec {
    private FixtureCodec() {
    }

    // ---- wrapper → record -----------------------------------------------------------------------------------------

    /** A serverbound packet's record. */
    public static InboundEvent encodeInbound(Object packet, PlayerConnectionContext facts) {
        return switch (packet) {
            case ServerboundClientTickEndPacket ignored -> TickEnd.INSTANCE;
            case ServerboundPongPacket pong -> new Pong(pong.getId());
            case ServerboundMovePlayerPacket flying -> {
                Movement.Kind kind = Movement.Kind.valueOf(Packets.kind(flying).name());
                yield new Movement(kind, flying.getX(0), flying.getY(0), flying.getZ(0), flying.getYRot(0), flying.getXRot(0), flying.isOnGround(), flying.horizontalCollision());
            }
            case ServerboundMoveVehiclePacket move -> {
                var position = move.movingTo().position();
                yield new VehicleMovement(position.x(), position.y(), position.z(), move.movingTo().yRot(), move.movingTo().xRot(), move.onGround());
            }
            case ServerboundPaddleBoatPacket steer -> new PaddleBoat(steer.getLeft(), steer.getRight());
            case ServerboundAcceptTeleportationPacket confirm -> TeleportAccept.withPosition(confirm.id(), confirm.x(), confirm.y(), confirm.z(), confirm.yRot(), confirm.xRot());
            case ServerboundPlayerInputPacket input -> new PlayerInput(Packets.inputFlags(input));
            case ServerboundPlayerAbilitiesPacket abilities -> new PlayerAbilities(abilities.isFlying());
            case ServerboundPlayerCommandPacket action -> new PlayerCommand(command(action.getAction()), action.getData());
            case ServerboundSetCarriedItemPacket held -> new HeldSlot(held.getSlot());
            case ServerboundUseItemPacket use -> new UseItem(use.hand() == InteractionHand.OFF_HAND, use.sequence(),
                    use.yRot(), use.xRot());
            case ServerboundUseItemOnPacket place -> {
                var position = place.hitResult().getBlockPos();
                yield new UseItemOn(place.hand() == InteractionHand.OFF_HAND, place.sequence(),
                        position.getX(), position.getY(), position.getZ());
            }
            case ServerboundInteractPacket p -> new InteractEntity(p.entityId(),p.hand()==InteractionHand.OFF_HAND,p.location()!=null);
            case ServerboundAttackPacket attack -> new Attack(attack.entityId());
            case ServerboundPlayerActionPacket digging -> {
                var position = digging.getPos();
                yield new PlayerAction(action(digging.getAction()), position.getX(), position.getY(), position.getZ(),
                        digging.getDirection().ordinal(), digging.getSequence());
            }
            case ServerboundPunchPacket swing -> new Swing(Swing.Hand.MAIN_HAND);
            case ServerboundContainerClosePacket close -> new ContainerClosed(close.getContainerId());
            case ServerboundContainerClickPacket click -> containerClick(click);
            case ServerboundSetCreativeModeSlotPacket creative -> CreativeSlot.of(creative.slotNum(), FixtureItems.of(creative.itemStack()));
            case ServerboundPlayerLoadedPacket ignored -> PlayerLoaded.INSTANCE;
            case ServerboundKeepAlivePacket keepAlive -> new KeepAlive(keepAlive.getId());
            case ServerboundChunkBatchReceivedPacket ack -> new ChunkBatchReceived(ack.desiredChunksPerTick());
            case UnhandledPacket other -> new OtherInbound(other.name());
            default -> throw new IllegalArgumentException("Not a serverbound packet the core decodes: " + packet);
        };
    }

    /**
     * {@code container_click} with the client's native hashed result (item, count and component patch).
     */
    private static ContainerClick containerClick(ServerboundContainerClickPacket packet) {
        return ContainerClick.of(packet.containerId(),packet.stateId(),packet.slotNum(),packet.buttonNum(),packet.containerInput().ordinal(),claims(packet),FixtureItems.claimed(packet.carriedItem()));
    }
    private static Map<Integer, ClaimedStack> claims(ServerboundContainerClickPacket packet) {
        var changed = new java.util.TreeMap<Integer, ClaimedStack>();
        packet.changedSlots().forEach((slot,stack)->changed.put(slot,FixtureItems.claimed(stack)));
        return changed;
    }

    /** {@code player_command}'s action by vanilla's name. */
    static PlayerCommand.Action command(ServerboundPlayerCommandPacket.Action action) {
        return PlayerCommand.Action.valueOf(action.name());
    }
    static ServerboundPlayerCommandPacket.Action action(PlayerCommand.Action action) {
        return ServerboundPlayerCommandPacket.Action.valueOf(action.name());
    }
    static PlayerAction.Action action(Action action) { return PlayerAction.Action.valueOf(action.name()); }
    static Action action(PlayerAction.Action action) { return Action.valueOf(action.name()); }

    /** A clientbound packet's record. */
    public static OutboundEvent encodeOutbound(Object packet, PlayerConnectionContext facts) {
        return switch (packet) {
            case ClientboundInitializeBorderPacket p -> new WorldBorder(WorldBorder.Operation.INITIALIZE, p.getNewCenterX(), p.getNewCenterZ(),
                    p.getOldSize(), p.getNewSize(), p.getLerpTime(), p.getNewAbsoluteMaxSize());
            case ClientboundSetBorderCenterPacket p -> new WorldBorder(WorldBorder.Operation.CENTER, p.getNewCenterX(), p.getNewCenterZ(), 0, 0, 0, 0);
            case ClientboundSetBorderSizePacket p -> new WorldBorder(WorldBorder.Operation.SIZE, 0, 0, 0, p.getSize(), 0, 0);
            case ClientboundSetBorderLerpSizePacket p -> new WorldBorder(WorldBorder.Operation.LERP, 0, 0, p.getOldSize(),
                    p.getNewSize(), p.getLerpTime(), 0);
            case ClientboundPingPacket p -> new Ping(p.getId());
            case ClientboundBundleDelimiterPacket ignored -> BundleDelimiter.INSTANCE;
            case ClientboundSetPassengersPacket p -> new EntityPassengers(p.getVehicle(), p.getPassengers());
            case ClientboundRemoveEntitiesPacket p -> new EntityRemove(p.entityIds().toIntArray());
            case ClientboundMoveVehiclePacket p -> {
                var position = p.movingTo().position();
                yield new VehicleCorrection(position.x(), position.y(), position.z(), p.movingTo().yRot(), p.movingTo().xRot());
            }
            case ClientboundAddEntityPacket p -> {
                var velocity=p.getMovement();var uuid=p.getUUID();
                yield new EntitySpawn(p.getId(),net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(p.getType()).toString(),p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot(),p.getYHeadRot(),p.getData(),true,velocity.x(),velocity.y(),velocity.z(),uuid.getMostSignificantBits(),uuid.getLeastSignificantBits());
            }
            case ClientboundLoginPacket p -> {
                DimensionType dimension = p.commonPlayerSpawnInfo().dimensionType().value();
                // the profile the server logged the client in with: the local player's scoreboard name
                yield new JoinGame(p.playerId(), p.commonPlayerSpawnInfo().dimension().identifier().toString(), dimension.minY(), dimension.height(),
                        p.chunkRadius(), Packets.gameModeId(p.commonPlayerSpawnInfo().gameType()), facts.localName(), Packets.canHaveWeather(p, dimension));
            }
            case ClientboundRespawnPacket p -> {
                DimensionType dimension = p.commonPlayerSpawnInfo().dimensionType().value();
                yield new Respawn(p.commonPlayerSpawnInfo().dimension().identifier().toString(), p.dataToKeep(), dimension.minY(), dimension.height(),
                        Packets.gameModeId(p.commonPlayerSpawnInfo().gameType()), Packets.canHaveWeather(p, dimension));
            }
            case ClientboundLevelChunkWithLightPacket p -> chunkData(p, facts);
            case ClientboundLightUpdatePacket p -> new LightUpdate(p.x(), p.z());
            case ClientboundChunksBiomesPacket p -> new ChunkBiomes(p.chunkBiomeData().stream().mapToLong(c -> c.pos().pack()).toArray());
            case ClientboundUpdateTagsPacket p -> blockTags(p);
            case ClientboundAnimatePacket p -> new EntityAnimation(p.getId(), p.getAction());
            case ClientboundForgetLevelChunkPacket p -> new ForgetChunk(p.pos().x(), p.pos().z());
            case ClientboundBlockUpdatePacket p -> {
                BlockPos position = p.getPos();
                yield new BlockUpdate(position.getX(), position.getY(), position.getZ(), net.minecraft.world.level.block.Block.getId(p.getBlockState()));
            }
            case ClientboundBlockEventPacket p -> {
                var pos = p.getPos();
                yield new BlockEvent(pos.getX(), pos.getY(), pos.getZ(), p.getB0(), p.getB1(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(p.getBlock()));
            }
            case ClientboundBlockEntityDataPacket p -> {
                var pos = p.getPos();
                yield new BlockEntityData(pos.getX(), pos.getY(), pos.getZ(), net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(p.getType()).toString(), nbt(p.getTag()));
            }
            case MultiBlockChangeBitRepackHandler.Update p -> {
                long[] entries = new long[p.count()];
                for (int i = 0; i < entries.length; i++) entries[i] = p.entry(i);
                yield new SectionBlocksUpdate(p.sectionX(), p.sectionY(), p.sectionZ(), entries);
            }
            case ClientboundSectionBlocksUpdatePacket p -> encodeOutbound(MultiBlockChangeBitRepackHandler.read(p),facts);
            case ClientboundBlockChangedAckPacket p -> new BlockChangedAck(p.sequence());
            case ClientboundChunkBatchStartPacket ignored -> new ChunkBatch(false, 0);
            case ClientboundChunkBatchFinishedPacket p -> new ChunkBatch(true, p.batchSize());
            case ClientboundSetChunkCacheCenterPacket p -> new ChunkCacheCenter(p.getX(), p.getZ());
            case ClientboundSetChunkCacheRadiusPacket p -> new ChunkCacheRadius(p.getRadius());
            case ClientboundPlayerPositionPacket p -> {
                Vec3 delta = p.change().deltaMovement();
                yield new PlayerPosition(p.id(), p.change().position().x, p.change().position().y(), p.change().position().z, delta.x(), delta.y(), delta.z(),
                        p.change().yRot(), p.change().xRot(), Relative.pack(p.relatives()));
            }
            case ClientboundPlayerRotationPacket p -> new PlayerRotation(p.yRot(), p.relativeY(), p.xRot(), p.relativeX());
            case ClientboundSetEntityMotionPacket p -> {
                Vec3 velocity = p.movement();
                yield new EntityVelocity(p.id(), velocity.x(), velocity.y(), velocity.z());
            }
            case ClientboundExplodePacket p -> {
                Vec3 center = p.center();
                Vec3 knockback = p.playerKnockback().orElse(null);
                yield knockback == null
                        ? new Explosion(center.x(), center.y(), center.z(), false, 0, 0, 0)
                        : new Explosion(center.x(), center.y(), center.z(), true, knockback.x(), knockback.y(), knockback.z());
            }
            case ClientboundPlayerAbilitiesPacket p -> new Abilities(p.isInvulnerable(), p.isFlying(), p.canFly(),
                    p.canInstabuild(), p.getFlyingSpeed(), p.getWalkingSpeed());
            // native Reason constants are declared in vanilla id order
            case ClientboundGameEventPacket p -> new GameEvent(gameEventId(p.getEvent()), p.getParam());
            // the blend flag (visual only) is not exposed by the wrapper
            case ClientboundUpdateMobEffectPacket p -> new EffectUpdate(p.getEntityId(), p.getEffect().unwrapKey().orElseThrow().identifier().toString(),
                    p.getEffectAmplifier(), p.getEffectDurationTicks(), p.isEffectAmbient(), p.isEffectVisible(), p.effectShowsIcon(), p.shouldBlend());
            case ClientboundRemoveMobEffectPacket p -> new EffectRemove(p.entityId(), p.effect().unwrapKey().orElseThrow().identifier().toString());
            case ClientboundUpdateAttributesPacket p -> EntityAttributes.of(p.getEntityId(),
                    ru.minecomplex.mac.entity.attribute.AttributeSnapshot.of(p));
            case ClientboundSetHealthPacket p -> new PlayerHealth(p.getHealth(), p.getFood(), p.getSaturation());
            case ClientboundPlayerInfoUpdatePacket p -> playerInfo(p, facts.localUuid());
            case ClientboundContainerSetContentPacket p -> {
                var stacks = new ArrayList<WireItem>();
                for (var stack : p.items()) stacks.add(FixtureItems.of(stack));
                yield ContainerContent.of(p.containerId(), p.stateId(), stacks, FixtureItems.of(p.carriedItem()));
            }
            case ClientboundContainerSetSlotPacket p -> ContainerSlot.of(p.getContainerId(), p.getStateId(), p.getSlot(), FixtureItems.of(p.getItem()));
            case ClientboundSetPlayerInventoryPacket p -> PlayerInventorySlot.of(p.slot(), FixtureItems.of(p.contents()));
            case ClientboundSetHeldSlotPacket p -> new SetHeldSlot(p.slot());
            case ClientboundEntityEventPacket p -> new EntityEvent(p.entityId(), p.getEventId());
            case ClientboundCooldownPacket p -> new ItemCooldown(p.cooldownGroup().toString(), p.duration());
            case ClientboundSetCursorItemPacket p -> CursorItem.of(FixtureItems.of(p.contents()));
            // the client's menu type registry id; the core holds the layouts per protocol
            case ClientboundOpenScreenPacket p -> new OpenContainer(p.getContainerId(), net.minecraft.core.registries.BuiltInRegistries.MENU.getId(p.getType()));
            case ClientboundMountScreenOpenPacket p -> new MountScreenOpen(p.getContainerId(), p.getInventoryColumns(), p.getEntityId());
            case ClientboundContainerClosePacket p -> new CloseContainer(p.getContainerId());
            case ClientboundMoveEntityPacket.PosRot p -> entityMove(p.entityId(), p.getPositionDelta(), true, p.getYRot(), p.getXRot(), p.isOnGround());
            case ClientboundMoveEntityPacket.Pos p -> entityMove(p.entityId(), p.getPositionDelta(), false, 0.0F, 0.0F, p.isOnGround());
            case ClientboundMoveEntityPacket.Rot p -> EntityMove.linear(p.entityId(), false, true, (short) 0, (short) 0, (short) 0,
                    Packets.packedAngle(p.getYRot()), Packets.packedAngle(p.getXRot()), p.isOnGround());
            case ClientboundTeleportEntityPacket p -> {
                var values = p.change();
                var position = values.position();
                var delta = values.deltaMovement();
                yield new EntityTeleport(p.id(), position.x(), position.y(), position.z(), delta.x(), delta.y(),
                        delta.z(), values.yRot(), values.xRot(), Relative.pack(p.relatives()), p.onGround());
            }
            case ClientboundEntityPositionSyncPacket p -> {
                if (p.position() instanceof PositionPath.Stepped stepped) {
                    var steps = stepped.steps();
                    double[][] positions = new double[steps.size()][];
                    int[] ticks = new int[steps.size()];
                    for (int i = 0; i < steps.size(); i++) {
                        var step = steps.get(i);
                        positions[i] = new double[]{step.position().x(), step.position().y(), step.position().z()};
                        ticks[i] = step.tickOffset();
                    }
                    yield EntityPositionSync.stepped(p.id(), positions, ticks, p.yRot(), p.xRot(), p.onGround());
                }
                var end = p.position().endPosition();
                yield EntityPositionSync.linear(p.id(), end.x(), end.y(), end.z(), p.yRot(), p.xRot(), p.onGround());
            }
            case ClientboundSetEntityDataPacket p -> entityData(p);
            case ClientboundSetEquipmentPacket p -> {
                WireItem saddle = null, body = null;
                for (var equipment : p.getSlots()) {
                    if (equipment.getFirst() == EquipmentSlot.SADDLE) saddle = FixtureItems.of(equipment.getSecond());
                    else if (equipment.getFirst() == EquipmentSlot.BODY) body = FixtureItems.of(equipment.getSecond());
                }
                yield saddle == null && body == null ? new OtherOutbound("ENTITY_EQUIPMENT") : EntityEquipment.of(p.getEntity(), saddle, body);
            }
            case ClientboundSetTimePacket p -> new GameTime(p.gameTime());
            case ClientboundPlayerInfoRemovePacket p -> new PlayerInfoRemove(!p.profileIds().contains(facts.localUuid()),
                    PlayerInfoRemove.encode(List.copyOf(p.profileIds())));
            case ClientboundSetPlayerTeamPacket p -> {
                int rule = p.getParameters().map(info -> info.collisionRule() == null ? -1 : info.collisionRule().ordinal()).orElse(-1);
                var players = p.getPlayers() == null ? List.<String>of() : List.copyOf(p.getPlayers());
                yield TeamUpdate.of(p.getName(), p.method(), rule, players);
            }
            case UnhandledPacket other -> new OtherOutbound(other.name());
            default -> throw new IllegalArgumentException("Not a clientbound packet the core decodes: " + packet);
        };
    }

    /** {@code move_entity_pos}/{@code _pos_rot}: the raw 1/4096 deltas, linear or (26.3) stepped. */
    private static EntityMove entityMove(int id, VecDelta delta, boolean rotation, float yaw, float pitch, boolean onGround) {
        byte packedYaw = rotation ? Packets.packedAngle(yaw) : 0, packedPitch = rotation ? Packets.packedAngle(pitch) : 0;
        if (delta instanceof VecDelta.Stepped stepped) {
            var steps = stepped.steps();
            short[][] deltas = new short[steps.size()][];
            int[] ticks = new int[steps.size()];
            for (int i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                deltas[i] = new short[]{step.xa(), step.ya(), step.za()};
                ticks[i] = step.ticks();
            }
            return EntityMove.stepped(id, rotation, deltas, ticks, packedYaw, packedPitch, onGround);
        }
        var linear = (VecDelta.Linear) delta;
        return EntityMove.linear(id, true, rotation, linear.xa(), linear.ya(), linear.za(), packedYaw, packedPitch, onGround);
    }

    /**
     * The base entity / living-entity items of {@code set_entity_data} ({@link EntitySyncedData}); ids are identical in all
     * supported protocols. Items 8 and 9 are taken only with the living-entity value types; item 14 tells whether a
     * sleeping position is present. Mob items: {@link Packets#mobItems}.
     */
    private static OutboundEvent entityData(ClientboundSetEntityDataPacket packet) {
        int present = 0;
        byte sharedFlags = 0, livingFlags = 0;
        boolean noGravity = false;
        String pose = "";
        float health = 0.0F;
        boolean sleeping = false;
        int sleepX = 0, sleepY = 0, sleepZ = 0;
        for (var item : packet.packedItems()) {
            Object value = item.value();
            switch (item.id()) {
                case 0 -> { if (value instanceof Byte b) { sharedFlags = b; present |= EntitySyncedData.SHARED_FLAGS; } }
                case 5 -> { if (value instanceof Boolean b) { noGravity = b; present |= EntitySyncedData.NO_GRAVITY; } }
                case 6 -> { if (value instanceof Enum<?> e) { pose = e.name(); present |= EntitySyncedData.POSE; } }
                case 8 -> { if (value instanceof Byte b) { livingFlags = b; present |= EntitySyncedData.LIVING_FLAGS; } }
                case 9 -> { if (value instanceof Float f) { health = f; present |= EntitySyncedData.HEALTH; } }
                case 14 -> {
                    if (value instanceof Optional<?> o) {
                        present |= EntitySyncedData.SLEEPING_POS;
                        if (o.orElse(null) instanceof BlockPos bed) {
                            sleeping = true;
                            sleepX = bed.getX();
                            sleepY = bed.getY();
                            sleepZ = bed.getZ();
                        }
                    }
                }
                default -> { }
            }
        }
        var mobItems = Packets.mobItems(packet);
        return present == 0 && mobItems.isEmpty() ? new OtherOutbound("ENTITY_METADATA")
                : new EntitySyncedData(packet.id(), present, sharedFlags, noGravity, pose, livingFlags, health,
                EntitySyncedData.encodeMobItems(mobItems), sleeping, sleepX, sleepY, sleepZ);
    }

    /** The client's own entry of {@code player_info_update}; entries of other players are not player state. */
    private static OutboundEvent playerInfo(ClientboundPlayerInfoUpdatePacket packet, UUID self) {
        var actions = packet.actions();
        boolean added = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
        boolean gameMode = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE);
        var profiles = new ArrayList<PlayerInfoGameMode.Profile>();
        if (added) {
            for (var entry : packet.entries()) {
                var profile = entry.profile();
                profiles.add(new PlayerInfoGameMode.Profile(entry.profileId(), profile == null ? "" : profile.name()));
            }
        }
        byte[] payload = profiles.isEmpty() ? null : PlayerInfoGameMode.encode(profiles);
        // every entry's game mode (other players' isSpectator)
        var modes = new ArrayList<PlayerInfoGameMode.EntryGameMode>();
        if (gameMode) {
            for (var entry : packet.entries()) {
                // GameType.byId maps unknown ids to survival
                modes.add(new PlayerInfoGameMode.EntryGameMode(entry.profileId(), Packets.gameModeId(entry.gameMode())));
            }
        }
        byte[] modePayload = modes.isEmpty() ? null : PlayerInfoGameMode.encodeGameModes(modes);
        for (var entry : packet.entries()) {
            if (self.equals(entry.profileId()) && (added || gameMode)) {
                return new PlayerInfoGameMode(added, gameMode, Packets.gameModeId(entry.gameMode()), payload, modePayload);
            }
        }
        // other players added or changed: the profiles their entities will be created with, their game modes
        return payload == null && modePayload == null ? new OtherOutbound("PLAYER_INFO_UPDATE")
                : new PlayerInfoGameMode(false, false, 0, payload, modePayload);
    }

    /** The block registry's tags of an {@code update_tags}: the client's block registry ids, as written to it. */
    public static BlockTagsUpdate blockTags(ClientboundUpdateTagsPacket packet) {
        Map<String, int[]> tags = new LinkedHashMap<>();
        for (var registry : packet.tags().entrySet()) {
            if (!registry.getKey().identifier().toString().equals("minecraft:block")) continue;
            for (var tag : registry.getValue().tags().entrySet()) tags.put(tag.getKey().toString(),tag.getValue().toIntArray());
        }
        return BlockTagsUpdate.of(tags);
    }

    private static ChunkData chunkData(ClientboundLevelChunkWithLightPacket packet, PlayerConnectionContext facts) {
        var sections = ru.minecomplex.mac.world.ChunkPacketReader.sections(packet, NativeRuntime.registries());
        int count = sections.size();
        int bits = 32 - Integer.numberOfLeadingZeros(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size() - 1);
        int perLong = 64 / bits;
        int words = (4096 + perLong - 1) / perLong;
        int[] sectionBits = new int[count], sizes = new int[count];
        long[] data = new long[count * words];
        java.util.Arrays.fill(sectionBits, bits);
        java.util.Arrays.fill(sizes, -1);
        for (int section = 0; section < count; section++) for (int index = 0; index < 4096; index++) {
            data[section * words + index / perLong] |= (long) sections.get(section).get(index) << (index % perLong * bits);
        }
        var entities = ru.minecomplex.mac.world.ChunkPacketReader.blockEntities(packet).stream().map(e -> new BlockEntityData(e.x(),e.y(),e.z(),e.type(),e.nbt())).toList();
        return new ChunkData(packet.x(),packet.z(),sectionBits,sizes,new int[0],data,ChunkBlockEntities.encode(entities));
    }
    private static int gameEventId(ClientboundGameEventPacket.Type type) {
        ClientboundGameEventPacket.Type[] types={ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE,ClientboundGameEventPacket.START_RAINING,ClientboundGameEventPacket.STOP_RAINING,ClientboundGameEventPacket.CHANGE_GAME_MODE,ClientboundGameEventPacket.WIN_GAME,ClientboundGameEventPacket.DEMO_EVENT,ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND,ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,ClientboundGameEventPacket.PUFFER_FISH_STING,ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT,ClientboundGameEventPacket.IMMEDIATE_RESPAWN,ClientboundGameEventPacket.LIMITED_CRAFTING,ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START};
        for(int i=0;i<types.length;i++) if(types[i]==type)return i;
        throw new IllegalArgumentException("Unknown game event "+type);
    }
    private static byte[] nbt(CompoundTag tag) {
        return ru.minecomplex.mac.world.BlockEntityData.nbt(tag);
    }
    public static Object decode(InboundEvent event, PlayerConnectionContext facts) {
        return NativeFixtureFactory.inbound(event, facts);
    }
    public static Object decode(OutboundEvent event, PlayerConnectionContext facts) {
        return NativeFixtureFactory.outbound(event, facts);
    }
}
