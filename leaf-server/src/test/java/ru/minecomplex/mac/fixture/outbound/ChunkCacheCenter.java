package ru.minecomplex.mac.fixture.outbound;

/** {@code set_chunk_cache_center}: moves the client's chunk storage window (hides, does not drop, chunks). */
public record ChunkCacheCenter(int x, int z) implements OutboundEvent {
}
