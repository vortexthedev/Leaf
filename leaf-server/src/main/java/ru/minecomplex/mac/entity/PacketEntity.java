package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.UUID;
import ru.minecomplex.mac.entity.attribute.AttributeSnapshot;
import ru.minecomplex.mac.replica.CompensatedEntities;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import ru.minecomplex.mac.entity.effect.EntityEffect;

/**
 * An entity of the client's level as its packets made it ({@code ClientLevel} entities, keyed by the native id): its
 * type, generation (an id reused by a new {@code add_entity} is another entity), passenger relations, the synced data,
 * attributes, effects and equipment the client received for it. Subclasses add what the client simulates for the types
 * the local player's movement depends on ({@link PacketEntityFollowed}) and the local player itself
 * ({@link PacketEntitySelf}).
 *
 * <p>Every entity belongs to one {@link CompensatedEntities}; a hypothesis fork copies it ({@link #copy}). Values kept
 * here are stable state: decoded wrappers are read when a packet is handled and never retained mutable.
 */
public class PacketEntity {
    protected CompensatedEntities owner;
    private final int id;
    private final String type;
    private final boolean player;
    /** Identity of this client entity: a new entity under the same id is a new generation. */
    private final Object generation;
    /** The {@code add_entity} that created it ({@code null}: the local player). */
    private final ClientboundAddEntityPacket spawn;

    // ---- passenger relations (Entity.vehicle / passengers) --------------------------------------------------------
    Integer vehicle;
    List<Integer> passengers = List.of();
    Pose pose = Pose.STANDING;
    int boardingCooldown;
    // ---- synced data (SynchedEntityData) ---------------------------------------------------------------------------
    /** Every item the client received, by data id ({@link EntityDataItems}). */
    private Map<Integer, Object> data = Map.of();
    /** When each mob item and the pose were last assigned ({@link CompensatedEntities#assigned}). */
    private final Map<Integer, Long> stamps = new HashMap<>();
    /** Attributes as the server last synchronized them, by attribute key, in first-sent order. */
    private final Map<String, AttributeSnapshot> attributes = new LinkedHashMap<>();
    /** Active effects as the server last sent them, by effect key. */
    private final Map<String, EntityEffect> effects = new LinkedHashMap<>();
    /** The saddle and body slot stacks ({@code null}: empty). */
    private ItemStack saddle, body;

    public PacketEntity(CompensatedEntities owner, int id, String type, boolean player, ClientboundAddEntityPacket spawn) {
        this(owner, id, type, player, spawn, new Object());
    }

    protected PacketEntity(CompensatedEntities owner, int id, String type, boolean player, ClientboundAddEntityPacket spawn,
                           Object generation) {
        this.owner = owner;
        this.id = id;
        this.type = type;
        this.player = player;
        this.spawn = spawn;
        this.generation = generation;
    }

    /** An independent copy for {@code owner} (a hypothesis fork); subclasses copy their own state. */
    public PacketEntity copy(CompensatedEntities owner) {
        return copyInto(new PacketEntity(owner, id, type, player, spawn, generation));
    }

    /** Copies this entity's base state into a new instance of the same generation. */
    protected final <T extends PacketEntity> T copyInto(T copy) {
        PacketEntity target = copy;
        target.vehicle = vehicle;
        target.passengers = passengers;
        target.pose = pose;
        target.boardingCooldown = boardingCooldown;
        target.data = data;
        target.stamps.putAll(stamps);
        target.attributes.putAll(attributes);
        target.effects.putAll(effects);
        target.saddle = saddle;
        target.body = body;
        return copy;
    }

    public int id() {
        return id;
    }

    /** The registry key of its type ({@code minecraft:pig}). */
    public String type() {
        return type;
    }

    /** A player entity ({@code minecraft:player}). */
    public boolean player() {
        return player;
    }

    public Object generation() {
        return generation;
    }

    /** The {@code add_entity} that created it, detached ({@code null}: the local player). */
    public ClientboundAddEntityPacket spawn() {
        return spawn;
    }

    /** {@code Entity.getUUID()}. */
    public UUID uuid() {
        return spawn == null ? null : spawn.getUUID();
    }

    public Integer vehicle() {
        return vehicle;
    }

    public List<Integer> passengers() {
        return passengers;
    }

    public Pose pose() {
        return pose;
    }

    public int boardingCooldown() {
        return boardingCooldown;
    }

    // ---- synced data ----------------------------------------------------------------------------------------------

    /**
     * {@code assignValues}: every item of the packet over the entity's current ones; mob items and the pose take the
     * assignment stamp.
     */
    public void assign(Map<Integer, Object> items, long stamp) {
        for (var entry : items.entrySet()) {
            if (EntityDataItems.mobItem(entry.getKey(), entry.getValue()) || entry.getKey() == EntityDataItems.POSE) {
                stamps.put(entry.getKey(), stamp);
            }
        }
        var merged = new TreeMap<>(data);
        merged.putAll(items);
        data = Collections.unmodifiableMap(merged);
    }

    /** The item with id {@code dataId} the client received ({@code null}: never; the type's default stands). */
    public Object item(int dataId) {
        return data.get(dataId);
    }

    /** Whether any base item was received (the client's synced data differs from the constructor's). */
    public boolean hasData() {
        return !data.isEmpty();
    }

    /** The mob items (id 15 on, and optional ints) the client received, by data id. */
    public Map<Integer, Object> mobItems() {
        var items = new TreeMap<Integer, Object>();
        data.forEach((dataId, value) -> { if (EntityDataItems.mobItem(dataId, value)) items.put(dataId, value); });
        return Collections.unmodifiableMap(items);
    }

    /** When data item {@code dataId} was last assigned (0: never). */
    public long assigned(int dataId) {
        return stamps.getOrDefault(dataId, 0L);
    }

    /** {@code Entity.getPose()}: the synched pose. */
    public Pose syncedPose() {
        return data.get(EntityDataItems.POSE) instanceof Pose value ? value : Pose.STANDING;
    }

    /** {@code LivingEntity.isAlive()} as far as the data tells: a health item that is not positive is dying. */
    public boolean alive() {
        return !(data.get(EntityDataItems.HEALTH) instanceof Float health) || health > 0.0F;
    }

    /** {@code Entity.isNoGravity()}. */
    public boolean noGravity() {
        return data.get(EntityDataItems.NO_GRAVITY) instanceof Boolean value && value;
    }

    /** The sleeping position the client holds ({@code null}: none). */
    public ru.minecomplex.mac.world.BlockPosition sleepingPosition() {
        return data.get(EntityDataItems.SLEEPING_POS) instanceof java.util.Optional<?> position
                && position.orElse(null) instanceof ru.minecomplex.mac.world.BlockPosition bed ? bed : null;
    }

    /** A firework rocket's {@code DATA_ATTACHED_TO_TARGET} as the client stores it (empty by default). */
    public OptionalInt optionalInt(int dataId) {
        return dataId >= 0 && data.get(dataId) instanceof OptionalInt value ? value : OptionalInt.empty();
    }

    // ---- attributes, effects, equipment ---------------------------------------------------------------------------

    /** {@code handleUpdateAttributes}: each listed attribute takes the snapshot's base and modifiers. */
    public void attributes(List<AttributeSnapshot> snapshots) {
        for (var snapshot : snapshots) attributes.put(snapshot.attribute(), snapshot);
    }

    public Map<String, AttributeSnapshot> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public void effect(EntityEffect effect) {
        effects.put(effect.effect(), effect);
    }

    public void removeEffect(String effect) {
        effects.remove(effect);
    }

    public Map<String, EntityEffect> effects() {
        return Collections.unmodifiableMap(effects);
    }

    /** {@code handleSetEquipment}, as far as the saddle and body slots go ({@code null}: the slot was not sent). */
    public void equipment(ItemStack saddleStack, ItemStack bodyStack) {
        if (saddleStack != null) saddle = saddleStack.isEmpty() ? null : saddleStack;
        if (bodyStack != null) body = bodyStack.isEmpty() ? null : bodyStack;
    }

    /** The stack in the saddle slot ({@code null}: empty). */
    public ItemStack saddle() {
        return saddle;
    }

    /** The stack in the body slot ({@code null}: empty). */
    public ItemStack body() {
        return body;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + id + " " + type + "]";
    }
}
