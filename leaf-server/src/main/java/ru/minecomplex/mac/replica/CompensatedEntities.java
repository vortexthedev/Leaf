package ru.minecomplex.mac.replica;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.inventory.item.Stacks;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.Shapes;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.entity.ClientTeams;
import ru.minecomplex.mac.entity.EntityAttachment;
import ru.minecomplex.mac.entity.EntityDataItems;
import ru.minecomplex.mac.entity.EntityDimensions;
import ru.minecomplex.mac.entity.effect.EntityEffect;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.entity.MobData;
import ru.minecomplex.mac.entity.PacketEntity;
import ru.minecomplex.mac.entity.PacketEntityFollowed;
import ru.minecomplex.mac.entity.PacketEntityFollowed.Kind;
import ru.minecomplex.mac.entity.PacketEntityHorse;
import ru.minecomplex.mac.entity.PacketEntityLiving;
import ru.minecomplex.mac.entity.PacketEntityLivingMount;
import ru.minecomplex.mac.entity.PacketEntityMoving;
import ru.minecomplex.mac.entity.PacketEntityPlayer;
import ru.minecomplex.mac.entity.PacketEntityRideable;
import ru.minecomplex.mac.entity.PacketEntityRocket;
import ru.minecomplex.mac.entity.PacketEntitySelf;
import ru.minecomplex.mac.entity.PacketEntityShulker;
import ru.minecomplex.mac.entity.EntityMotions;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.entity.attribute.AttributeSnapshot;
import ru.minecomplex.mac.packet.Packets;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.entity.EntityMotion;
import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.entity.LivingVehicleState;
import ru.minecomplex.mac.engine.movementtick.OtherVehicleRiding;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.util.Tri;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * The client level's entities as its packets made them, in the packet prefix a world view or hypothesis has applied
 * (Grim's {@code CompensatedEntities}): identities and passenger relations, the synced data, attributes, effects and
 * equipment, the connection's {@code PlayerInfo}s and teams, and — for the entities the local player's movement depends
 * on ({@link PacketEntityFollowed}) — their client transforms and their place in the client's entity tick list relative
 * to the local player. Spawn transforms are wire inputs, not simulated positions.
 *
 * <p>Boats, rafts and happy ghasts (the entities a player collides with) are simulated exactly by {@link EntityMotion}
 * (MAEM oracle): a remote one moves on the client only by its packets and interpolation. Shulkers and every entity that
 * may be pushable (living entities, minecarts) keep only the positions their packets named, a conservative reach used to
 * report what is not modelled ({@link Unmodeled#ENTITY_COLLISION}, {@link Unmodeled#ENTITY_PUSH},
 * {@link Unmodeled#VEHICLE_ENTITIES}).
 *
 * <p>Tick order ({@code ClientLevel.tickEntities}): the tick list keeps insertion order. An entity enters it when it is
 * added in a ticking chunk or its chunk starts ticking, leaves it when its chunk stops ticking or it moves into a chunk
 * that does not tick ({@code TransientEntitySectionManager}); players are always ticking. The local player enters at
 * login and at a same-level respawn ({@code ClientLevel.addEntity}), so entities that were present before a same-level
 * respawn tick before it. A passenger is ticked by its vehicle and placed by it ({@code positionRider}).
 *
 * <p>Collision queries follow {@code EntityGetter.getEntityCollisions}: only entities whose section ({@code blockPosition})
 * lies within the query box widened by 2 blocks horizontally and 4 blocks down ({@code EntitySectionStorage
 * .forEachAccessibleNonEmptySection}) are seen, in section and section-insertion order.
 *
 * <p>Handlers take detached native 26.3 wrappers ({@link ru.minecomplex.mac.packet.PacketDecoder}); nothing here keeps a
 * reference into a packet's buffer.
 */
public final class  CompensatedEntities {
    /** {@code Entity.DATA_POSE}'s id, the base item whose assignments {@link #assigned} reports. */
    public static final int POSE_DATA_ID = EntityDataItems.POSE;

    /** A {@code PlayerInfo}: the profile name and the game mode ({@code GameType} id); a player entity keeps its own. */
    public static final class PlayerInfo {
        final String name;
        int gameMode;

        PlayerInfo(String name, int gameMode) {
            this.name = name;
            this.gameMode = gameMode;
        }
    }

    private final EntityTypes types;
    private EntityMotions motions;
    /** Every entity of the level, the local player included, in spawn order. */
    private final Map<Integer, PacketEntity> entities = new LinkedHashMap<>();
    private PassengerGraph passengers = new PassengerGraph(entities);
    private final EnumSet<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
    private int localPlayerId;
    /** {@code removedPlayerVehicleId}: the entity last removed while carrying the local player ({@code MIN_VALUE}: none). */
    private int formerVehicle = Integer.MIN_VALUE;
    /** The connection's scoreboard teams. */
    private ClientTeams teams = new ClientTeams();
    /** The connection's {@code PlayerInfo}s by profile UUID. */
    private final Map<UUID, PlayerInfo> playerInfos = new LinkedHashMap<>();
    /** Whether the wire carried profiles ({@code player_info_update} with its added profiles): absent ones then are absent. */
    private boolean playerInfosTracked;
    /** The local player's profile name ({@code null}: not known). */
    private String localName;
    /** Counts every data assignment ({@link #assigned}). */
    private long stamp;

    public CompensatedEntities(int localPlayerId) {
        this.types = EntityTypes.get();
        this.localPlayerId = localPlayerId;
        passengers.add(new PacketEntitySelf(this, localPlayerId));
        this.motions = new EntityMotions(this);
    }

    private CompensatedEntities(CompensatedEntities source) {
        this.types = source.types;
    }

    /** An independent copy (a hypothesis fork). */
    public CompensatedEntities copy() {
        var copy = new CompensatedEntities(this);
        // the infos are mutable and shared between the connection's map and the entities that found them
        var infos = new IdentityHashMap<PlayerInfo, PlayerInfo>();
        Function<PlayerInfo, PlayerInfo> copied = info -> info == null ? null
                : infos.computeIfAbsent(info, old -> new PlayerInfo(old.name, old.gameMode));
        playerInfos.forEach((uuid, info) -> copy.playerInfos.put(uuid, copied.apply(info)));
        entities.forEach((id, entity) -> copy.entities.put(id, entity instanceof PacketEntityPlayer player
                ? player.copy(copy, copied.apply(player.info())) : entity.copy(copy)));
        copy.passengers = passengers.copy(copy.entities);
        copy.gaps.addAll(gaps);
        copy.localPlayerId = localPlayerId;
        copy.formerVehicle = formerVehicle;
        copy.teams = teams.copy();
        copy.playerInfosTracked = playerInfosTracked;
        copy.localName = localName;
        copy.stamp = stamp;
        copy.motions = motions.copy(copy);
        return copy;
    }

    public EntityTypes types() {
        return types;
    }

    public int localPlayerId() {
        return localPlayerId;
    }

    public PassengerGraph passengers() {
        return passengers;
    }

    /** The entity under {@code id} ({@code null}: none). */
    public PacketEntity entity(int id) {
        return entities.get(id);
    }

    /** A followed entity under {@code id} ({@code null}: none, or not followed). */
    public PacketEntityFollowed followed(int id) {
        return entities.get(id) instanceof PacketEntityFollowed followed ? followed : null;
    }

    private PacketEntityMoving moving(int id) {
        return entities.get(id) instanceof PacketEntityMoving moving ? moving : null;
    }

    /** The {@code add_entity} that created entity {@code id} ({@code null}: none, or the local player). */
    public ClientboundAddEntityPacket spawnInput(int id) {
        var entity = entities.get(id);
        return entity == null ? null : entity.spawn();
    }

    /** The registry key of entity {@code id}'s type ({@code null}: no spawned entity). */
    public String typeOf(int id) {
        var entity = entities.get(id);
        return entity == null || entity.spawn() == null ? null : entity.type();
    }

    /** Stable identity list for bounded diagnostic queries; never exposes the mutable registry. */
    public Set<Integer> entityIds() {
        var ids = new HashSet<Integer>();
        entities.forEach((id, entity) -> { if (entity.spawn() != null) ids.add(id); });
        return Set.copyOf(ids);
    }

    // ---- connection state -----------------------------------------------------------------------------------------

    /**
     * A respawn into another level replaces the level, not the connection: {@code ClientPacketListener} state kept by the
     * new level's entities ({@code removedPlayerVehicleId}).
     */
    public void inheritConnectionState(CompensatedEntities previous) {
        formerVehicle = previous.formerVehicle;
        teams = previous.teams.copy();
        previous.playerInfos.forEach((uuid, info) -> playerInfos.put(uuid, new PlayerInfo(info.name, info.gameMode)));
        playerInfosTracked = previous.playerInfosTracked;
        localName = previous.localName;
    }

    /** The local player's profile name from {@code login} ({@code null}: not known). */
    public void localName(String name) {
        localName = name == null || name.isEmpty() ? null : name;
    }

    /** {@code handleSetPlayerTeamPacket}. */
    public void team(ClientboundSetPlayerTeamPacket packet) {
        teams.apply(packet);
    }

    public ClientTeams teams() {
        return teams;
    }

    /**
     * {@code handlePlayerInfoUpdate}: {@code ADD_PLAYER} gives a profile without a {@code PlayerInfo} one (survival), then
     * {@code UPDATE_GAME_MODE} sets the game mode of every listed entry that has one.
     */
    public void playerInfo(ClientboundPlayerInfoUpdatePacket packet) {
        var actions = packet.actions();
        // a recording without profiles never tracks them: its player entities keep unknown names
        if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) && !packet.entries().isEmpty()) {
            playerInfosTracked = true;
            for (var entry : packet.entries()) {
                var profile = entry.profile();
                playerInfos.putIfAbsent(entry.profileId(), new PlayerInfo(profile == null ? "" : profile.name(), 0));
            }
        }
        if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) {
            for (var entry : packet.entries()) {
                var info = playerInfos.get(entry.profileId());
                if (info != null) info.gameMode = Packets.gameModeId(entry.gameMode());
            }
        }
    }

    /** {@code handlePlayerInfoRemove}. */
    public void playerInfo(ClientboundPlayerInfoRemovePacket packet) {
        for (UUID id : packet.profileIds()) playerInfos.remove(id);
    }

    /**
     * {@code Entity.getScoreboardName()}: a player's profile name (the local player's from {@code login}, another's from
     * its {@code PlayerInfo} when it was created), any other entity's UUID string; {@code null} when not known.
     */
    public String scoreboardName(int id) {
        if (id == localPlayerId) return localName;
        var entity = entities.get(id);
        if (entity == null || entity.spawn() == null) return null;
        if (entity instanceof PacketEntityPlayer player) return player.info() == null ? null : player.info().name;
        if (entity.player()) return null;
        return entity.uuid().toString();
    }

    /**
     * {@code isSpectator()} of another player entity: the game mode of the {@code PlayerInfo} it was created with
     * ({@code GameType.SPECTATOR}); unknown when the connection's profiles are not followed, or when that info was gone
     * before the entity's first tick ({@link #lookUpPlayerInfo}).
     */
    public Tri spectator(int id) {
        var info = entities.get(id) instanceof PacketEntityPlayer player ? player.info() : null;
        if (info == null) return playerInfosTracked ? Tri.FALSE : Tri.EITHER;
        if (((PacketEntityPlayer) entities.get(id)).ambiguousInfo()) return Tri.EITHER;
        return Tri.of(info.gameMode == 3);
    }

    /**
     * A player entity's first tick ({@code Player.tick}: {@code noPhysics = isSpectator()}): by then it has looked its
     * {@code PlayerInfo} up; when the connection no longer holds the one it was created with, which one it found (or none)
     * depends on whether it was rendered before.
     */
    public void lookUpPlayerInfo(int id) {
        if (entities.get(id) instanceof PacketEntityPlayer player && player.info() != null
                && playerInfos.get(player.uuid()) != player.info()) player.ambiguousInfo(true);
    }

    /** {@code entity.hasIndirectPassenger(minecraft.player)} of a present entity. */
    private boolean carriesLocalPlayer(int id) {
        var view = passengers.get(localPlayerId);
        for (int depth = 0; view != null && view.vehicle() != null && depth < 4096; depth++) {
            if (view.vehicle() == id) return true;
            view = passengers.get(view.vehicle());
        }
        return false;
    }

    /**
     * Whether {@code id} is {@code removedPlayerVehicleId}: an entity removed while carrying the local player, with no
     * entity added under its id and no {@code set_passengers} mounting the player since.
     */
    public Tri formerVehicle(int id) {
        return formerVehicle == id ? Tri.TRUE : Tri.FALSE;
    }

    public Set<Unmodeled> unmodeled() {
        var result = EnumSet.copyOf(gaps);
        result.addAll(passengers.unmodeled());
        return Set.copyOf(result);
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    /**
     * {@code handleAddEntity}: {@code tickingChunks} tells which chunks tick ({@code ClientLevel.onChunkLoaded}).
     *
     * @return whether the client created the entity (a player needs a {@code PlayerInfo}, else the old entity stays)
     */
    public boolean spawn(ClientboundAddEntityPacket spawn, LongPredicate tickingChunks) {
        int id = spawn.getId();
        String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(spawn.getType()).toString();
        if (formerVehicle == id) formerVehicle = Integer.MIN_VALUE;
        boolean player = type.equals("minecraft:player");
        var uuid = spawn.getUUID();
        if (player && playerInfosTracked && !playerInfos.containsKey(uuid)) return false;
        // the local player's own id is not followed (its state is the player model's)
        PacketEntity entity = id == localPlayerId ? new PacketEntity(this, id, type, player, spawn) : motions.create(spawn, type);
        if (entity instanceof PacketEntityPlayer remote) remote.info(playerInfos.get(uuid));
        passengers.add(entity);
        motions.spawned(entity, tickingChunks);
        return true;
    }

    /** {@code handleRemoveEntities}. */
    public void remove(ClientboundRemoveEntitiesPacket packet) {
        for (int id : packet.entityIds()) {
            if (carriesLocalPlayer(id)) formerVehicle = id;
            passengers.remove(id);
        }
    }

    /** A same-level respawn replaces the local player while preserving foreign entities: the new LocalPlayer is added last. */
    public void respawnLocalPlayer() {
        motions.respawnLocalPlayer();
        passengers.add(new PacketEntitySelf(this, localPlayerId));
    }

    /** {@code handleSetEntityPassengersPacket}. */
    public void apply(ClientboundSetPassengersPacket packet) {
        int vehicleId = packet.getVehicle();
        // a known vehicle listing the local player clears removedPlayerVehicleId
        if (entities.get(vehicleId) != null && java.util.Arrays.stream(packet.getPassengers()).anyMatch(id -> id == localPlayerId))
            formerVehicle = Integer.MIN_VALUE;
        passengers.apply(packet, id -> {
            var entity = entities.get(id);
            return entity == null || entity.spawn() == null
                    || !(entity.type().equals("minecraft:marker") || entity.type().equals("minecraft:ominous_item_spawner"));
        });
    }

    // ---- data, attributes, effects, equipment ---------------------------------------------------------------------

    /**
     * {@code handleSetEntityData} for another entity: {@code assignValues} stores every item of the packet over the
     * entity's current ones. Data for an id the client has no entity for is dropped, as the client drops it.
     *
     * @param beds          the sleep height of the bed at a position; {@code null}: not known
     * @param tickingChunks which chunks tick (a moved entity may change its tick list membership)
     */
    public void syncedData(ClientboundSetEntityDataPacket packet, EntityMotions.Beds beds, LongPredicate tickingChunks) {
        int id = packet.id();
        var entity = entities.get(id);
        if (id == localPlayerId || entity == null || entity.spawn() == null) return;
        var items = EntityDataItems.of(packet);
        entity.assign(items, ++stamp);
        // onSyncedDataUpdated after every item is stored
        motions.syncedData(entity, items, beds, tickingChunks);
    }

    /** {@link #syncedData(ClientboundSetEntityDataPacket, EntityMotions.Beds, LongPredicate)} without the level. */
    public void syncedData(ClientboundSetEntityDataPacket packet) {
        syncedData(packet, null, chunk -> false);
    }

    /**
     * When data item {@code dataId} (a mob item, or {@link #POSE_DATA_ID}) of entity {@code id} was last assigned by a
     * packet: increases with every assignment, 0 when never. Lets an owner of client-side copies apply only new values.
     */
    public long assigned(int id, int dataId) {
        var entity = entities.get(id);
        return entity == null || entity.spawn() == null ? 0L : entity.assigned(dataId);
    }

    /** The mob items (id 15 on) the client received for entity {@code id}, by data id; absent ones keep their defaults. */
    public Map<Integer, Object> mobItems(int id) {
        var entity = entities.get(id);
        return entity == null || entity.spawn() == null ? Map.of() : entity.mobItems();
    }

    /** {@code handleUpdateAttributes} for another entity: each listed attribute takes the snapshot's base and modifiers. */
    public void attributes(ClientboundUpdateAttributesPacket packet) {
        int id = packet.getEntityId();
        var entity = entities.get(id);
        if (id == localPlayerId || entity == null || entity.spawn() == null) return;
        entity.attributes(AttributeSnapshot.of(packet));
    }

    /** The attribute snapshots the server sent for entity {@code id}, by attribute key, in first-sent order. */
    public Map<String, AttributeSnapshot> attributes(int id) {
        var entity = entities.get(id);
        return entity == null || entity.spawn() == null ? Map.of() : Map.copyOf(entity.attributes());
    }

    /** The attribute snapshots of entity {@code id} in first-sent order, without a copy. */
    public java.util.Collection<AttributeSnapshot> attributeSnapshots(int id) {
        var entity = entities.get(id);
        return entity == null || entity.spawn() == null ? List.of() : entity.attributes().values();
    }

    /** {@code handleUpdateMobEffect}/{@code handleRemoveMobEffect} for another entity. */
    public void effect(Object packet) {
        switch (packet) {
            case ClientboundUpdateMobEffectPacket update -> {
                var entity = entities.get(update.getEntityId());
                if (update.getEntityId() == localPlayerId || entity == null || entity.spawn() == null) return;
                entity.effect(EntityEffect.of(update));
            }
            case ClientboundRemoveMobEffectPacket remove -> {
                var entity = entities.get(remove.entityId());
                if (entity != null && entity.spawn() != null) entity.removeEffect(remove.effect().unwrapKey().orElseThrow().identifier().toString());
            }
            default -> throw new IllegalArgumentException("Not an effect packet: " + packet);
        }
    }

    /** The effects the server sent for entity {@code id} and has not removed, by effect key. */
    public Map<String, EntityEffect> effects(int id) {
        var entity = entities.get(id);
        return entity == null || entity.spawn() == null ? Map.of() : Map.copyOf(entity.effects());
    }

    /** {@code handleSetEquipment} for another entity, as far as the saddle and body slots go. */
    public void equipment(ClientboundSetEquipmentPacket packet) {
        int id = packet.getEntity();
        var entity = entities.get(id);
        if (id == localPlayerId || entity == null || entity.spawn() == null) return;
        ItemStack saddle = null, body = null;
        for (var equipment : packet.getSlots()) {
            if (equipment.getFirst() == EquipmentSlot.SADDLE) saddle = Stacks.of(equipment.getSecond());
            else if (equipment.getFirst() == EquipmentSlot.BODY) body = Stacks.of(equipment.getSecond());
        }
        entity.equipment(saddle, body);
    }

    /** Whether an equipment packet names a slot the model reads (else the client's handler changes nothing modelled). */
    public static boolean modelledEquipment(ClientboundSetEquipmentPacket packet) {
        for (var equipment : packet.getSlots()) {
            if (equipment.getFirst() == EquipmentSlot.SADDLE || equipment.getFirst() == EquipmentSlot.BODY) return true;
        }
        return false;
    }

    /** The stack in entity {@code id}'s body slot, {@code null} when empty. */
    public ItemStack body(int id) {
        var entity = entities.get(id);
        return entity == null || entity.spawn() == null ? null : entity.body();
    }

    /** The stack in entity {@code id}'s saddle slot, {@code null} when empty. */
    public ItemStack saddle(int id) {
        var entity = entities.get(id);
        return entity == null || entity.spawn() == null ? null : entity.saddle();
    }

    // ---- vehicle ride states --------------------------------------------------------------------------------------

    /** Install fully resolved client body fields; a spawn transform alone cannot certify hidden tick state. */
    public void boatState(int id, BoatState state) {
        if (!(entities.get(id) instanceof ru.minecomplex.mac.entity.PacketEntityBoat boat))
            throw new IllegalArgumentException("Boat state belongs to a different client entity generation");
        boat.state(state);
    }

    public BoatState boatState(int id) {
        return entities.get(id) instanceof ru.minecomplex.mac.entity.PacketEntityBoat boat ? boat.state() : null;
    }

    public void horseState(int id, HorseState state) {
        if (!(entities.get(id) instanceof PacketEntityHorse horse))
            throw new IllegalArgumentException("Horse state belongs to a different client entity generation");
        horse.state(state);
    }

    public HorseState horseState(int id) {
        return entities.get(id) instanceof PacketEntityHorse horse ? horse.state() : null;
    }

    public void livingState(int id, LivingVehicleState state) {
        if (!(entities.get(id) instanceof PacketEntityLivingMount mount))
            throw new IllegalArgumentException("Vehicle state belongs to a different client entity generation");
        mount.state(state);
    }

    public LivingVehicleState livingState(int id) {
        return entities.get(id) instanceof PacketEntityLivingMount mount ? mount.state() : null;
    }

    // ---- motions -------------------------------------------------------------------------------------------------

    /** The followed entities' transforms, ticks and queries. */
    public EntityMotions motions() {
        return motions;
    }

    /** {@code handleSetEntityMotion} for another entity ({@link EntityMotions#velocity}). */
    public void velocity(ClientboundSetEntityMotionPacket packet) {
        if (packet.id() == localPlayerId) return;
        motions.velocity(packet);
    }

    /** {@code move_entity_*}, {@code teleport_entity}, {@code entity_position_sync}. */
    public void motion(Object packet, LongPredicate tickingChunks) {
        switch (packet) {
            case ClientboundTeleportEntityPacket teleport -> motions.teleport(teleport, tickingChunks);
            case ClientboundEntityPositionSyncPacket sync -> motions.sync(sync, tickingChunks);
            default -> {
                if (!Packets.entityMove(packet)) throw new IllegalArgumentException("Not an entity motion: " + packet);
                motions.move(packet, tickingChunks);
            }
        }
    }

    /** A chunk started or stopped ticking. */
    public void chunkTicking(int chunkX, int chunkZ, boolean ticking) {
        motions.chunkTicking(chunkX, chunkZ, ticking);
    }

    /** {@link MobData} of entity {@code id}. */
    public MobData mob(int id) {
        return new MobData(this, id);
    }

    /** The level's entities in spawn order, the local player included (read-only). */
    public java.util.Collection<PacketEntity> all() {
        return java.util.Collections.unmodifiableCollection(entities.values());
    }
}
