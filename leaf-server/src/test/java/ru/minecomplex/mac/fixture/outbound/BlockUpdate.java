package ru.minecomplex.mac.fixture.outbound;

/** {@code block_update} with the client's registry id. */
public record BlockUpdate(int x, int y, int z, int state) implements OutboundEvent {
}
