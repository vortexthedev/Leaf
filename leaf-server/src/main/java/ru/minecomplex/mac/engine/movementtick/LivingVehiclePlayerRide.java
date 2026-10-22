package ru.minecomplex.mac.engine.movementtick;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.entity.LivingVehicleState;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Trig;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Connects a pig/strider/happy-ghast/nautilus whole tick to {@code LocalPlayer.rideTick}: the seat ({@code Entity
 * .positionRider} with {@code LivingEntity.getPassengerRidingPosition}; the strider's seat bobs with its walk animation on
 * the client) and the nautilus dash charge ({@code AbstractNautilus.onPlayerJump}).
 */
public final class LivingVehiclePlayerRide implements LocalPlayerRide {
    private final PassengerGraph graph;
    private final int playerId, vehicleId;
    private final Object playerGeneration;
    private final LivingVehicleState state;
    private final OtherVehicleRiding control;
    private final Supplier<OtherVehicleRiding.Facts> controlFacts;
    private final IntFunction<Vec3d> passengerAttachment;
    private final Supplier<Float> seatScale;
    private final Supplier<VehiclePacketState> root;
    private final Function<PlayerState, Vec3d> attachment;
    private final EnumSet<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);

    /**
     * @param passengerAttachment for a passenger index, the vehicle's current {@code getClamped(PASSENGER, index, yRot)}
     * @param seatScale           {@code getScale() * getAgeScale()} of the vehicle
     * @param attachment          the passenger's {@code getVehicleAttachmentPoint}
     */
    public LivingVehiclePlayerRide(PassengerGraph graph, int playerId, int vehicleId,
                                   LivingVehicleState state, OtherVehicleRiding control, Supplier<OtherVehicleRiding.Facts> controlFacts,
                                   IntFunction<Vec3d> passengerAttachment, Supplier<Float> seatScale,
                                   Supplier<VehiclePacketState> root, Function<PlayerState, Vec3d> attachment) {
        this.graph = graph;
        this.playerId = playerId;
        this.vehicleId = vehicleId;
        this.state = state;
        var own = graph.get(playerId);
        playerGeneration = own == null ? null : own.generation();
        this.control = control;
        this.controlFacts = controlFacts;
        this.passengerAttachment = passengerAttachment;
        this.seatScale = seatScale;
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
        if (!state.completeTick) result.add(Unmodeled.RIDING);
        result.addAll(state.tickGaps);
        result.addAll(graph.unmodeled());
        if (!bound()) result.add(Unmodeled.RIDING);
        return Set.copyOf(result);
    }

    public boolean controlsBoat() {
        return false;
    }

    private boolean nautilus() {
        return state.kind == OtherVehicleRiding.Kind.NAUTILUS || state.kind == OtherVehicleRiding.Kind.ZOMBIE_NAUTILUS;
    }

    /** A saddled nautilus is jumpable ({@code PlayerRideableJumping}), with its dash cooldown. */
    public boolean jumpableVehicle() {
        var facts = controlFacts.get();
        return nautilus() && facts != null && facts.saddled();
    }

    public int vehicleJumpCooldown() {
        return nautilus() ? state.control.dashCooldown : 0;
    }

    public boolean vehicleOnGround() {
        return state.body.onGround;
    }

    public void setBoatInput(PlayerInput input) {
        throw new IllegalStateException("Not a boat");
    }

    /** {@code AbstractNautilus.onPlayerJump}; the other mounts are no {@code PlayerRideableJumping}. */
    public boolean onPlayerJump(int charge) {
        if (!bound() || graph.get(vehicleId).passengers().getFirst() != playerId) return false;
        if (state.kind != OtherVehicleRiding.Kind.NAUTILUS && state.kind != OtherVehicleRiding.Kind.ZOMBIE_NAUTILUS) return false;
        var facts = controlFacts.get();
        if (facts == null) return false;
        control.charge(state.control, facts, charge);
        return true;
    }

    public void positionRider(PlayerState player, LocalPlayerState local) {
        if (!bound()) {
            gaps.add(Unmodeled.RIDING);
            return;
        }
        var offset = attachment.apply(player);
        var riders = graph.get(vehicleId).passengers();
        var point = passengerAttachment.apply(riders.indexOf(playerId));
        if (offset == null || point == null) {
            gaps.add(Unmodeled.RIDING);
            return;
        }
        if (state.kind == OtherVehicleRiding.Kind.STRIDER) {
            // Strider.getPassengerAttachmentPoint on the client: super(...).add(0.0, bob * scale, 0.0)
            float speed = Math.min(0.25F, state.walkSpeed);
            float position = state.walkPosition * state.walkPositionScale;
            float bob = 0.12F * Trig.cos(position * 1.5F) * 2.0F * speed;
            point = new Vec3d(point.x() + 0.0, point.y() + bob * seatScale.get(), point.z() + 0.0);
        }
        var p = state.body.position;
        var riding = new Vec3d(p.x() + point.x(), p.y() + point.y(), p.z() + point.z());
        player.position = VanillaMath.position(player.position,
                new Vec3d(riding.x() - offset.x(), riding.y() - offset.y(), riding.z() - offset.z()));
    }
}
