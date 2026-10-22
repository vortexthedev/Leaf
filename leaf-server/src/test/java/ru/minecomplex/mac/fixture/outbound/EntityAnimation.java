package ru.minecomplex.mac.fixture.outbound;

/** Native ClientboundAnimatePacket action ID; wake-up uses action 0. */
public record EntityAnimation(int entityId, int action) implements OutboundEvent {
}
