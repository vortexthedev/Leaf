package ru.minecomplex.mac.fixture.inbound;

/** Native entity attack packet; its optional location records the client's targeted hit point. */
public record Attack(int entityId) implements InboundEvent {
}
