package ru.minecomplex.mac.engine.movementtick;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * A root boat followed by its LocalPlayer passenger, in ClientLevel.tickNonPassenger order.
 * Bind the graph, body and player to the same persistent WorldModel/PlayerModel hypothesis.
 * Packet acknowledgements may change that graph but never advance either entity clock.
 */
public final class BoatPlayerTick {

    private final PassengerGraph graph;

    private final int playerId, boatId;

    private final Object playerGeneration;

    private final BoatState boat;

    private final MovementTickerBoat boatPhysics;

    private final PlayerState player;

    private final LocalPlayerState local;

    private final MovementTickerPlayer playerPhysics;

    private final boolean raft, chest;

    private final Function<PlayerState, Vec3d> attachment;

    private Tri lastAuthority = Tri.EITHER;

    public BoatPlayerTick(MovementEnvironment env, ru.minecomplex.mac.replica.CompensatedEntities entities, int boatId, PlayerState player, LocalPlayerState local, ClientPlayerData data, boolean raft, boolean chest, Function<PlayerState, Vec3d> attachment, MovementTickerBoat.Contacts contacts) {
        this(env, entities.passengers(), entities.localPlayerId(), boatId, java.util.Objects.requireNonNull(entities.boatState(boatId), "Unresolved client boat body"), player, local, data, raft, chest, attachment, contacts);
    }

    /**
     * Attachment resolver reads the passenger's current client pose/scale/yaw after its tick.
     */
    public BoatPlayerTick(MovementEnvironment env, PassengerGraph graph, int playerId, int boatId, BoatState boat, PlayerState player, LocalPlayerState local, ClientPlayerData data, boolean raft, boolean chest, Function<PlayerState, Vec3d> attachment, MovementTickerBoat.Contacts contacts) {
        this.graph = graph;
        this.playerId = playerId;
        this.boatId = boatId;
        var own = graph.get(playerId);
        playerGeneration = own == null ? null : own.generation();
        this.boat = boat;
        this.player = player;
        this.local = local;
        this.raft = raft;
        this.chest = chest;
        this.attachment = java.util.Objects.requireNonNull(attachment);
        boatPhysics = new MovementTickerBoat(env, boat, contacts);
        playerPhysics = new MovementTickerPlayer(env, player, local, data);
    }

    private boolean bound() {
        var own = graph.get(playerId);
        var vehicle = graph.get(boatId);
        return own != null && own.generation() == playerGeneration && vehicle != null && vehicle.generation() == boat.generation && java.util.Objects.equals(own.vehicle(), boatId) && vehicle.vehicle() == null && vehicle.passengers().contains(playerId);
    }

    /**
     * One selected client tick. Other entity ticks must run before the late send in a world orchestrator;
     * this convenience models the root boat and its player subtree with no intervening unrelated ticks.
     */
    public TickPackets tick(PlayerInput keys, Tri boatAuthority, boolean controlledByClient, boolean firstPassengerIsPlayer) {
        if (!bound())
            return unresolved();
        var result = simulateEntityTicks(keys, boatAuthority, controlledByClient, firstPassengerIsPlayer);
        return result.then(sendAfterEntityTicks());
    }

    private TickPackets unresolved() {
        local.rootVehicle = null;
        return TickPackets.UNRESOLVED;
    }

    /**
     * Called between the boat's tick and its rider's (a caller fixing the rider's yaw from the boat's rotation).
     */
    public java.util.function.Consumer<BoatState> beforeRider = boat -> {
    };

    /**
     * Run the root/passenger subtree; the full world orchestrator then ticks other entities before late send.
     */
    public TickPackets simulateEntityTicks(PlayerInput keys, Tri boatAuthority, boolean controlledByClient, boolean firstPassengerIsPlayer) {
        if (!bound()) {
            return unresolved();
        }
        lastAuthority = boatAuthority;
        var passengers = graph.get(boatId).passengers();
        boolean controls = passengers.getFirst() == playerId;
        var body = boatPhysics.tick(true, firstPassengerIsPlayer, firstPassengerIsPlayer, controlledByClient, boatAuthority);
        var gaps = EnumSet.noneOf(Unmodeled.class);
        gaps.addAll(body.unmodeled());
        gaps.addAll(graph.unmodeled());
        var packets = new ArrayList<Object>(body.packets());
        beforeRider.accept(boat);
        player.passenger = true;
        player.oldPosition = player.position;
        // ClientLevel commonTick/setOldPosAndRot before passenger.rideTick.
        local.yawOld = player.yRot;
        var ride = new Ride(boatAuthority, controls, gaps);
        gaps.addAll(playerPhysics.simulateRideTick(keys, ride));
        packets.addAll(playerPhysics.packets());
        return new TickPackets(List.copyOf(packets), Set.copyOf(gaps));
    }

    /**
     * Read the live hypothesis again after intervening entity ticks, rather than retaining the earlier packet view.
     */
    public TickPackets sendAfterEntityTicks() {
        if (!bound())
            return unresolved();
        local.rootVehicle = boat.packetState(lastAuthority);
        int from = playerPhysics.packets().size();
        Set<Unmodeled> gaps = playerPhysics.sendAfterEntityTicks();
        var all = playerPhysics.packets();
        return new TickPackets(List.copyOf(all.subList(from, all.size())), gaps);
    }

    private final class Ride implements LocalPlayerRide {

        private final Tri authority;

        private final boolean controls;

        private final EnumSet<Unmodeled> gaps;

        Ride(Tri authority, boolean controls, EnumSet<Unmodeled> gaps) {
            this.authority = authority;
            this.controls = controls;
            this.gaps = gaps;
        }

        public FluidWorld.BoatPassenger boatFluidFacts() {
            boolean underwater = boat.underwater();
            return new FluidWorld.BoatPassenger(boat.body.boundingBox(), underwater);
        }

        public VehiclePacketState rootVehicle() {
            return bound() ? boat.packetState(authority) : null;
        }

        public Set<Unmodeled> unmodeled() {
            if (!bound())
                gaps.add(Unmodeled.RIDING);
            return Set.copyOf(gaps);
        }

        public void positionRider(PlayerState p, LocalPlayerState l) {
            if (!bound()) {
                gaps.add(Unmodeled.RIDING);
                return;
            }
            var seat = new BoatPassengerPhysics.State();
            seat.position = p.position;
            seat.yaw = p.yRot;
            seat.yawOld = l.yawOld;
            seat.headYaw = l.headYaw;
            seat.bodyYaw = l.bodyYaw;
            var passengers = graph.get(boatId).passengers();
            var offset = attachment.apply(p);
            if (offset == null) {
                gaps.add(Unmodeled.RIDING);
                return;
            }
            new BoatPassengerPhysics().position(seat, boat.body.position, boat.body.yRot, boat.deltaRotation, boat.body.height(), raft, chest, passengers.size(), passengers.indexOf(playerId), offset, false, true);
            p.position = seat.position;
            p.yRot = seat.yaw;
            l.yawOld = seat.yawOld;
            l.headYaw = seat.headYaw;
            l.bodyYaw = seat.bodyYaw;
        }

        public boolean controlsBoat() {
            return bound() && controls;
        }

        public boolean vehicleOnGround() {
            return boat.body.onGround;
        }

        public void setBoatInput(PlayerInput input) {
            boat.input = input;
        }
    }
}
