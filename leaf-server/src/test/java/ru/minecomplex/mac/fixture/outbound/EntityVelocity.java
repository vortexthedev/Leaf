package ru.minecomplex.mac.fixture.outbound;

/** Native set_entity_motion fixture, with velocity decoded through LpVec3. */
public record EntityVelocity(int entityId, double x, double y, double z) implements OutboundEvent {
}
