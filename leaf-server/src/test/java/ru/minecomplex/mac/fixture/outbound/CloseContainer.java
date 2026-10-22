package ru.minecomplex.mac.fixture.outbound;

/** {@code container_close}: {@code clientSideCloseContainer()} returns to the inventory menu whatever the id. */
public record CloseContainer(int containerId) implements OutboundEvent {
}
