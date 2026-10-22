package ru.minecomplex.mac.engine.movementtick;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Connects a horse's phases ({@link MovementTickerHorse}) and its seat ({@link HorsePassengerPhysics}) to
 * {@code LocalPlayer.rideTick}, including the rider's jump charge. Partial phase calls retain {@code RIDING}; only the
 * horse's whole tick resolves it.
 */
public final class HorsePlayerRide implements LocalPlayerRide {
    private final PassengerGraph graph;
    private final int playerId, vehicleId;
    private final Object playerGeneration;
    private final HorseState state;
    private final MovementTickerHorse horse;
    private final HorsePassengerPhysics seats;
    private final Supplier<HorsePassengerPhysics.Facts> seatFacts;
    private final Supplier<VehiclePacketState> root;
    private final Function<PlayerState, Vec3d> attachment;
    private final EnumSet<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);

    public HorsePlayerRide(PassengerGraph graph, int playerId, int vehicleId,
                           MovementTickerHorse horse, Supplier<HorsePassengerPhysics.Facts> seatFacts,
                           Supplier<VehiclePacketState> root, Function<PlayerState, Vec3d> attachment) {
        this.graph = graph;
        this.playerId = playerId;
        this.vehicleId = vehicleId;
        this.horse = horse;
        this.state = horse.horse();
        var own = graph.get(playerId);
        playerGeneration = own == null ? null : own.generation();
        seats = new HorsePassengerPhysics();
        this.seatFacts = seatFacts;
        this.root = root;
        this.attachment = attachment;
    }

    private boolean bound() {
        var own = graph.get(playerId);
        var vehicle = graph.get(vehicleId);
        return own != null && own.generation() == playerGeneration && vehicle != null
                && vehicle.generation() == state.generation && java.util.Objects.equals(own.vehicle(), vehicleId)
                && vehicle.vehicle() == null && vehicle.passengers().contains(playerId);
    }

    public boolean nonBoatVehicleResolved() {
        return bound();
    }

    public FluidWorld.BoatPassenger boatFluidFacts() {
        return null;
    }

    public VehiclePacketState rootVehicle() {
        if (!bound()) {
            gaps.add(Unmodeled.RIDING);
            return null;
        }
        var snapshot = root.get();
        if (snapshot == null || snapshot.generation() != state.generation) {
            gaps.add(Unmodeled.RIDING);
            return null;
        }
        return snapshot;
    }

    public Set<Unmodeled> unmodeled() {
        var result = EnumSet.copyOf(gaps);
        if (!state.completeTick) result.addAll(state.baseGaps);
        result.addAll(state.tickGaps);
        result.addAll(graph.unmodeled());
        if (!bound()) result.add(Unmodeled.RIDING);
        return Set.copyOf(result);
    }

    public boolean controlsBoat() {
        return false;
    }

    public void setBoatInput(PlayerInput input) {
        throw new IllegalStateException("Horse is not a boat");
    }

    // What LocalPlayer reads from the horse: a camel sprints and dashes; a saddled horse is jumpable unless the camel
    // refuses to move (Camel.canJump)

    public boolean vehicleCanSprint() {
        return state.camel();
    }


    public boolean jumpableVehicle() {
        return state.saddled && !horse.refuses();
    }

    public int vehicleJumpCooldown() {
        return state.camel() ? state.dashCooldown : 0;
    }

    public boolean vehicleOnGround() {
        return state.body.onGround;
    }

    /** {@code onPlayerJump}: the first passenger released a charged jump. */
    public boolean onPlayerJump(int charge) {
        if (!bound() || graph.get(vehicleId).passengers().getFirst() != playerId) return false;
        horse.charge(charge);
        return true;
    }

    /** {@code positionRider}: the seat from the horse's live body and animation fields of this hypothesis. */
    public void positionRider(PlayerState player, LocalPlayerState local) {
        if (!bound()) {
            gaps.add(Unmodeled.RIDING);
            return;
        }
        var f = seatFacts.get();
        var offset = attachment.apply(player);
        if (f == null || f.kind() != state.kind || offset == null) {
            gaps.add(Unmodeled.RIDING);
            return;
        }
        var riders = graph.get(vehicleId).passengers();
        f = new HorsePassengerPhysics.Facts(state.kind, state.body.position, state.body.yRot, state.bodyYaw,
                state.body.height(), f.seatScale(), f.baby(), f.removed(), riders.indexOf(playerId), riders.size(),
                false, f.gameTime(), state.lastPoseChangeTick, state.oldStandAnimation, f.defaultAttachment());
        var passenger = new BoatPassengerPhysics.State();
        passenger.position = player.position;
        passenger.yaw = player.yRot;
        passenger.yawOld = local.yawOld;
        passenger.headYaw = local.headYaw;
        passenger.bodyYaw = local.bodyYaw;
        seats.position(passenger, f, offset, true);
        player.position = passenger.position;
        local.bodyYaw = passenger.bodyYaw;
    }

    /**
     * The ridden movement alone, after the caller's resolved base phases and before its LocalPlayer passenger. It does not
     * advance a complete Mob tick or clear the missing-base uncertainty.
     */
    public Set<Unmodeled> advanceRiddenMovement(PlayerState rider, boolean simulate) {
        state.completeTick = false;
        if (!bound()) return Set.of(Unmodeled.RIDING);
        horse.travelRidden(rider, simulate);
        horse.applyEffectsFromBlocks();
        gaps.addAll(horse.unmodeled());
        return unmodeled();
    }

    /** The tick tails alone ({@link MovementTickerHorse#tickTail}) with the body's contacts, without effective AI. */
    public Set<Unmodeled> advanceTickTail(long gameTime) {
        state.completeTick = false;
        if (!bound()) return Set.of(Unmodeled.RIDING);
        var body = state.body;
        if (MovementTickerHorse.tickTail(state, false, body.onGround, body.wasTouchingWater || body.isInLava, false,
                body.wasTouchingWater, false, gameTime)) {
            horse.refreshStandingDimensions();
        }
        return unmodeled();
    }
}
