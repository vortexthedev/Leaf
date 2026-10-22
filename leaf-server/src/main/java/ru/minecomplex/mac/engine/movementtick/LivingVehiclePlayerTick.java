package ru.minecomplex.mac.engine.movementtick;

import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.entity.LivingVehicleState;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.Tri;

/**
 * Generation-bound whole pig/strider/happy-ghast/nautilus root tick, mounted player subtree and optional late send.
 */
public final class LivingVehiclePlayerTick {

    private final LivingVehiclePlayerRide ride;

    private final LivingVehicleTickPhysics vehicle;

    private final LivingVehicleState state;

    private final PlayerState player;

    private final LocalPlayerState local;

    private final MovementTickerPlayer passenger;

    private boolean rootTickPending;

    private Set<Unmodeled> rootGaps = Set.of();

    public LivingVehiclePlayerTick(MovementEnvironment env, LivingVehicleState state, LivingVehicleTickPhysics vehicle, LivingVehiclePlayerRide ride, PlayerState player, LocalPlayerState local, ClientPlayerData data) {
        this.ride = ride;
        this.state = state;
        this.vehicle = vehicle;
        this.player = player;
        this.local = local;
        passenger = new MovementTickerPlayer(env, player, local, data);
    }

    private boolean bound() {
        var root = ride.rootVehicle();
        return root != null && root.generation() == state.generation && root.localAuthority() == Tri.TRUE;
    }

    private TickPackets unresolved() {
        local.rootVehicle = null;
        return TickPackets.UNRESOLVED;
    }

    /**
     * Root before the player: the rider's previous movement fields and jumping flag, its current rotation.
     */
    public Set<Unmodeled> simulateVehicleTick(LivingVehicleTickPhysics.Facts facts) {
        if (rootTickPending)
            throw new IllegalStateException("Previous root tick still needs its passenger phase");
        if (!bound()) {
            state.completeTick = false;
            rootGaps = Set.of(Unmodeled.RIDING);
            return rootGaps;
        }
        rootGaps = vehicle.tick(facts, new OtherVehicleRiding.Rider(player.xxa, player.zza, player.yRot, player.xRot, player.jumping));
        rootTickPending = true;
        return rootGaps;
    }

    /**
     * The mounted player's tick, after the root tick; the ride reads the vehicle as that tick left it.
     */
    public TickPackets simulatePassengerTick(PlayerInput keys) {
        if (!bound()) {
            rootTickPending = false;
            return unresolved();
        }
        if (!rootTickPending)
            throw new IllegalStateException("Mounted player phase requires its current root tick");
        rootTickPending = false;
        player.passenger = true;
        player.oldPosition = player.position;
        local.yawOld = player.yRot;
        Set<Unmodeled> gaps = passenger.simulateRideTick(keys, ride);
        return new TickPackets(List.copyOf(passenger.packets()), gaps).withGaps(rootGaps);
    }

    /**
     * From 26.3: the late send after the unrelated entity ticks; returns only the packets it sends.
     */
    public TickPackets sendAfterEntityTicks() {
        if (!bound())
            return unresolved();
        int from = passenger.packets().size();
        Set<Unmodeled> gaps = passenger.sendAfterEntityTicks(ride);
        var all = passenger.packets();
        return new TickPackets(List.copyOf(all.subList(from, all.size())), gaps).withGaps(rootGaps);
    }

    /**
     * Convenience for a world without unrelated entity ticks before its late send.
     */
    public TickPackets tick(PlayerInput keys, LivingVehicleTickPhysics.Facts rootFacts) {
        simulateVehicleTick(rootFacts);
        var result = simulatePassengerTick(keys);
        return result.then(sendAfterEntityTicks());
    }
}
