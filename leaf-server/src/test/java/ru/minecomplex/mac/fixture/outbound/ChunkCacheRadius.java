package ru.minecomplex.mac.fixture.outbound;

/** {@code set_chunk_cache_radius}: the server view distance; resizes the client's chunk storage. */
public record ChunkCacheRadius(int radius) implements OutboundEvent {
}
