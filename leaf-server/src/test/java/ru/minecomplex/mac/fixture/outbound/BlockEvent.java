package ru.minecomplex.mac.fixture.outbound;

/** Wire block event. Vanilla dispatches to the current client block, ignoring {@code blockType}. */
public record BlockEvent(int x, int y, int z, int action, int parameter, int blockType) implements OutboundEvent {
}
