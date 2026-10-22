package ru.minecomplex.mac.fixture.outbound;

/** {@code ping}. Whether it is one of our markers is decided by the session (marker id allocation). */
public record Ping(int id) implements OutboundEvent {
}
