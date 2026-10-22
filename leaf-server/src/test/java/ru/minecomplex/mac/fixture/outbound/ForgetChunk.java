package ru.minecomplex.mac.fixture.outbound;

/** {@code forget_level_chunk}: the client drops the column. */
public record ForgetChunk(int x, int z) implements OutboundEvent {
}
