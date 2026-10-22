package ru.minecomplex.mac.fixture.inbound;

/**
 * {@code chunk_batch_received}. Sent from the client's netty thread while the chunks of the batch may still be
 * queued: not a barrier for chunk data.
 */
public record ChunkBatchReceived(float desiredChunksPerTick) implements InboundEvent {
    @Override
    public boolean nettyThread() {
        return true;
    }
}
