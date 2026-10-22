package ru.minecomplex.mac.fixture.inbound;

/** Root vehicle coordinates claimed by the client; also used as a payload by provenance-owned predictions. */
public record VehicleMovement(double x, double y, double z, float yaw, float pitch, boolean onGround) implements InboundEvent { }
