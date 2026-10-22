package ru.minecomplex.mac.fixture.inbound;

/** {@code pong}. Whether it answers one of our markers is decided by the synchronization timeline. */
public record Pong(int id) implements InboundEvent {
}
