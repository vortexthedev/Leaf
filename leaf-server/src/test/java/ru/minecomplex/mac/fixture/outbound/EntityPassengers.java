package ru.minecomplex.mac.fixture.outbound;

import java.util.Arrays;

/** Main-thread passenger handler input. An unknown vehicle/passenger is ignored at this prefix. */
public record EntityPassengers(int vehicleId, int[] passengerIds) implements OutboundEvent {
    public EntityPassengers { passengerIds = passengerIds.clone(); }
    @Override public int[] passengerIds() { return passengerIds.clone(); }
    @Override public boolean equals(Object other) { return other instanceof EntityPassengers passengers && vehicleId == passengers.vehicleId && Arrays.equals(passengerIds, passengers.passengerIds); }
    @Override public int hashCode() { return 31 * vehicleId + Arrays.hashCode(passengerIds); }
}
