package ru.minecomplex.mac.fixture.inbound;

/** Native entity interaction fixture. The hit location selects interactAt before the general interactOn path. */
public record InteractEntity(int entityId, boolean offHand, boolean at) implements InboundEvent {
}
