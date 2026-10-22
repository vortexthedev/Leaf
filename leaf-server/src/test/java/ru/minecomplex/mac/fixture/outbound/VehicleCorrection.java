package ru.minecomplex.mac.fixture.outbound;

/** Applies to the client's authoritative root vehicle at handler time, not a named entity or tick. */
public record VehicleCorrection(double x, double y, double z, float yaw, float pitch) implements OutboundEvent { }
