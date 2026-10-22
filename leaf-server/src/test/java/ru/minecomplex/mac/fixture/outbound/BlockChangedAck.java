package ru.minecomplex.mac.fixture.outbound;

/** {@code block_changed_ack}: the server processed the client's block interactions up to {@code sequence}. */
public record BlockChangedAck(int sequence) implements OutboundEvent {
}
