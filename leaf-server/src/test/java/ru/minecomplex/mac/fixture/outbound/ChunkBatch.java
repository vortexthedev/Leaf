package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code chunk_batch_start} ({@code finished = false}) or {@code chunk_batch_finished}. The client answers the
 * finish from its netty thread, before the chunks are processed: not a barrier ({@code transactions.md} §1.2).
 */
public record ChunkBatch(boolean finished, int batchSize) implements OutboundEvent {
}
