package ru.minecomplex.mac.fixture.inbound;

/** {@code client_tick_end}: the last packet of every client tick in multiplayer. */
public record TickEnd() implements InboundEvent {
    public static final TickEnd INSTANCE = new TickEnd();
}
