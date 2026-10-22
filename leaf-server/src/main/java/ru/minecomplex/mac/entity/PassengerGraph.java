package ru.minecomplex.mac.entity;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import java.util.*;
import ru.minecomplex.mac.engine.Unmodeled;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;

/**
 * Client entity relations ({@code Entity.vehicle}/{@code passengers}) over the level's entities: the relations are the
 * entities' own fields ({@link PacketEntity#vehicle()}, {@link PacketEntity#passengers()}); this graph runs vanilla's
 * mount/dismount rules on them. Seating, controller metadata and subclass dismount physics remain separate.
 */
public final class PassengerGraph {
    public record View(int id, Object generation, Integer vehicle, List<Integer> passengers, Pose pose, int boardingCooldown) { }

    /** The level's entities in spawn order (shared with the owning {@code CompensatedEntities}). */
    private final Map<Integer, PacketEntity> entities;
    private final EnumSet<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);

    public PassengerGraph(Map<Integer, PacketEntity> entities) {
        this.entities = entities;
    }

    /** A graph of its own entities, without a level (physics fixtures that only need relations). */
    public PassengerGraph() {
        this(new LinkedHashMap<>());
    }

    /** {@link #add} of a bare entity of no level (a standalone graph). */
    public void spawn(int id, boolean player) {
        add(new PacketEntity(null, id, player ? "minecraft:player" : "minecraft:marker", player, null));
    }

    public void spawn(int id) {
        spawn(id, false);
    }

    /** A graph over {@code entities} with this graph's gaps. */
    public PassengerGraph copy(Map<Integer, PacketEntity> entities) {
        var copy = new PassengerGraph(entities);
        copy.gaps.addAll(gaps);
        return copy;
    }

    private static View view(PacketEntity entity) {
        return new View(entity.id(), entity.generation(), entity.vehicle, entity.passengers, entity.pose, entity.boardingCooldown);
    }

    public View get(int id) {
        var entity = entities.get(id);
        return entity == null ? null : view(entity);
    }

    public Collection<View> entities() {
        return entities.values().stream().map(PassengerGraph::view).toList();
    }

    public Set<Unmodeled> unmodeled() {
        return Set.copyOf(gaps);
    }

    /** {@code ClientLevel.addEntity} removes the old id before registering the replacement. */
    public void add(PacketEntity entity) {
        remove(entity.id());
        entities.put(entity.id(), entity);
    }

    public void pose(int id, Pose pose) {
        var entity = entities.get(id);
        if (entity != null) entity.pose = pose;
    }

    public void stopRiding(int id) {
        var entity = entities.get(id);
        if (entity == null) return;
        if (entity.vehicle == null) {
            if (entity.player()) entity.boardingCooldown = 0;
            return;
        }
        var vehicle = entities.get(entity.vehicle);
        entity.vehicle = null;
        if (vehicle != null) vehicle.passengers = vehicle.passengers.stream().filter(passenger -> passenger != id).toList();
        entity.boardingCooldown = entity.player() ? 0 : 60;
    }

    public void remove(int id) {
        var entity = entities.get(id);
        if (entity == null) return;
        stopRiding(id);
        for (int passenger : entity.passengers) stopRiding(passenger);
        entities.remove(id);
    }

    /** Force mount, with an already resolved couldAcceptPassenger predicate. */
    public boolean startRiding(int id, int vehicleId, boolean couldAcceptPassenger) {
        var entity = entities.get(id);
        var vehicle = entities.get(vehicleId);
        if (entity == null || vehicle == null || Objects.equals(entity.vehicle, vehicleId) || !couldAcceptPassenger) return false;
        // Vanilla can create a self cycle from a malformed forced handler input. It is not safe to query a root.
        if (entity == vehicle) {
            gaps.add(Unmodeled.VEHICLE_PASSENGER_GRAPH);
            return false;
        }
        for (PacketEntity current = vehicle; current.vehicle != null; current = entities.get(current.vehicle)) {
            if (current.vehicle == id) return false;
        }
        stopRiding(id);
        entity.pose = Pose.STANDING;
        entity.vehicle = vehicleId;
        var next = new ArrayList<>(vehicle.passengers);
        next.add(id);
        vehicle.passengers = List.copyOf(next);
        return true;
    }

    /** Exact packet order: reverse ejection, then force mounts in wire order, ignoring missing ids. */
    public void apply(ClientboundSetPassengersPacket packet, java.util.function.IntPredicate couldAcceptPassenger) {
        var vehicle = entities.get(packet.getVehicle());
        if (vehicle == null) return;
        var previous = vehicle.passengers;
        for (int index = previous.size() - 1; index >= 0; index--) stopRiding(previous.get(index));
        for (int id : packet.getPassengers()) startRiding(id, packet.getVehicle(), couldAcceptPassenger.test(packet.getVehicle()));
    }

    /** {@link #root} without boxing: the root's id, or {@code id} itself where {@link #root} is {@code null}. */
    public int rootId(int id) {
        if (gaps.contains(Unmodeled.VEHICLE_PASSENGER_GRAPH)) return id;
        var entity = entities.get(id);
        return entity == null ? id : rootId(entity);
    }

    /** {@link #rootId(int)} of an entity of this graph's level. */
    public int rootId(PacketEntity entity) {
        if (gaps.contains(Unmodeled.VEHICLE_PASSENGER_GRAPH)) return entity.id();
        while (entity.vehicle != null) entity = entities.get(entity.vehicle);
        return entity.id();
    }

    public Integer root(int id) {
        var entity = entities.get(id);
        if (entity == null || gaps.contains(Unmodeled.VEHICLE_PASSENGER_GRAPH)) return null;
        while (entity.vehicle != null) entity = entities.get(entity.vehicle);
        return entity.id();
    }
}
