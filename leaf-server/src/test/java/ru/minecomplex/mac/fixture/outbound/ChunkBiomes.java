package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code chunks_biomes}: the client replaces the biomes of these columns ({@code ChunkPos.asLong}: x in the low, z in
 * the high 32 bits). Only the columns are kept: the biomes the model reads come from the server's own chunks
 * ({@code world.Precipitation}), which a change still in flight may have made newer than the client's.
 */
public record ChunkBiomes(long[] chunks) implements OutboundEvent {

    public ChunkBiomes {
        chunks = chunks.clone();
    }

    @Override public long[] chunks() { return chunks.clone(); }

    public static int x(long chunk) { return (int) chunk; }

    public static int z(long chunk) { return (int) (chunk >>> 32); }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChunkBiomes that && java.util.Arrays.equals(chunks, that.chunks);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(chunks);
    }

    @Override
    public String toString() {
        return "ChunkBiomes" + java.util.Arrays.toString(chunks);
    }
}
