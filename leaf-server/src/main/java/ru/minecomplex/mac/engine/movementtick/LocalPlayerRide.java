package ru.minecomplex.mac.engine.movementtick;

import java.util.Set;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;

/** Resolved vehicle phases from one persistent client hypothesis, never from server entity locations. */
public interface LocalPlayerRide {
    FluidWorld.BoatPassenger boatFluidFacts();
    default boolean nonBoatVehicleResolved() { return false; }
    VehiclePacketState rootVehicle();
    Set<Unmodeled> unmodeled();
    void positionRider(PlayerState player, LocalPlayerState local);
    boolean controlsBoat();
    void setBoatInput(PlayerInput input);
    /** The jump callback happens before the command packet and before superclass aiStep. */
    default boolean onPlayerJump(int charge) { return false; }

    // What LocalPlayer reads from its vehicle during the passenger's tick (after the vehicle's tick)

    /** {@code LocalPlayer.vehicleCanSprint(getVehicle())}. */
    default boolean vehicleCanSprint() { return false; }

    /** {@code jumpableVehicle() != null}. */
    default boolean jumpableVehicle() { return false; }

    /** {@code jumpableVehicle().getJumpCooldown()}. */
    default int vehicleJumpCooldown() { return 0; }

    /** {@code getVehicle().onGround()}. */
    default boolean vehicleOnGround() { return false; }
}
