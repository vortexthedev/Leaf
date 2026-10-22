package ru.minecomplex.mac.fixture.outbound;

import java.util.Arrays;

/**
 * Block states of a {@code level_chunk_with_light} as the client decodes them (client registry ids), flattened for
 * compact recording. For section {@code i} (from the bottom of the world): {@code sectionBits[i]} bits per entry
 * ({@code 0} = single value), {@code paletteSizes[i]} palette entries taken in order from {@code palette}
 * ({@code -1} = direct storage without palette), and {@code 4096 / (64 / bits)} rounded up longs taken in order from
 * {@code data}. Block entities use {@link ChunkBlockEntities}' typed payload. Biomes, light and heightmaps are not kept.
 */
public record ChunkData(int x, int z, int[] sectionBits, int[] paletteSizes, int[] palette, long[] data, byte[] blockEntities)
        implements OutboundEvent {

    public ChunkData {
        blockEntities = blockEntities.clone();
    }

    public ChunkData(int x, int z, int[] sectionBits, int[] paletteSizes, int[] palette, long[] data) {
        this(x, z, sectionBits, paletteSizes, palette, data, new byte[0]);
    }

    @Override public byte[] blockEntities() { return blockEntities.clone(); }

    public int sectionCount() {
        return sectionBits.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChunkData that && x == that.x && z == that.z
                && Arrays.equals(sectionBits, that.sectionBits) && Arrays.equals(paletteSizes, that.paletteSizes)
                && Arrays.equals(palette, that.palette) && Arrays.equals(data, that.data)
                && Arrays.equals(blockEntities, that.blockEntities);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * x + z) + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "ChunkData[" + x + ", " + z + ", " + sectionBits.length + " sections]";
    }
}
