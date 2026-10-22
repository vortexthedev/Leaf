package ru.minecomplex.mac.world;

import java.util.Arrays;

/**
 * The 16×16×16 block states of a chunk section, stored like vanilla's {@code PalettedContainer} so wire data can be
 * adopted as is: single value, indirect palette (entries packed into longs without spanning, as
 * {@code SimpleBitStorage}) or direct global ids. Index order is vanilla's {@code (y << 8) | (z << 4) | x}.
 *
 * <p>Mutable (block updates); confined to the session thread.
 */
public final class PalettedSection {
    public static final int SIZE = 4096;
    private static final int MAX_INDIRECT_BITS = 8;

    /** Palette entries; {@code null} in direct mode. */
    private int[] palette;
    private int paletteSize;
    private int bits;
    private long[] data;
    /** Bits of a global id, used when the palette overflows. */
    private final int globalBits;

    private PalettedSection(int[] palette, int paletteSize, int bits, long[] data, int globalBits) {
        this.palette = palette;
        this.paletteSize = paletteSize;
        this.bits = bits;
        this.data = data;
        this.globalBits = globalBits;
    }

    /** A section where every block is {@code state}. */
    public static PalettedSection single(int state, int globalBits) {
        return new PalettedSection(new int[]{state}, 1, 0, null, globalBits);
    }

    /**
     * Adopts wire data.
     *
     * @param bits    bits per entry as sent ({@code 0} = single value)
     * @param palette palette entries ({@code null} for direct storage)
     * @param data    packed entries ({@code null} when {@code bits == 0}); not copied
     */
    public static PalettedSection of(int bits, int[] palette, long[] data, int globalBits) {
        if (bits == 0) {
            return single(palette[0], globalBits);
        }
        int expected = longsFor(bits);
        if (data.length != expected) {
            throw new IllegalArgumentException("Expected " + expected + " longs for " + bits + " bits, got " + data.length);
        }
        if (palette == null) {
            return new PalettedSection(null, 0, bits, data, globalBits);
        }
        return new PalettedSection(Arrays.copyOf(palette, Math.max(palette.length, 1 << bits)), palette.length, bits, data, globalBits);
    }

    public static int longsFor(int bits) {
        int perLong = 64 / bits;
        return (SIZE + perLong - 1) / perLong;
    }

    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public int get(int x, int y, int z) {
        return get(index(x, y, z));
    }

    public int get(int index) {
        if (bits == 0) {
            return palette[0];
        }
        int value = read(data, bits, index);
        return palette == null ? value : palette[value];
    }

    public void set(int x, int y, int z, int state) {
        set(index(x, y, z), state);
    }

    public void set(int index, int state) {
        if (palette == null) {
            if (state >= 1 << bits) {
                grow(Math.max(globalBits, 32 - Integer.numberOfLeadingZeros(state)), true);
            }
            write(data, bits, index, state);
            return;
        }
        int id = paletteId(state);
        if (id < 0) {
            if (paletteSize < 1 << bits) {
                id = paletteSize++;
                palette[id] = state;
            } else if (bits + 1 <= MAX_INDIRECT_BITS || bits == 0) {
                grow(bits == 0 ? 4 : bits + 1, false);
                id = paletteSize++;
                palette[id] = state;
            } else {
                grow(globalBits, true);
                write(data, bits, index, state);
                return;
            }
        }
        if (bits == 0) {
            return;
        }
        write(data, bits, index, id);
    }

    private int paletteId(int state) {
        for (int i = 0; i < paletteSize; i++) {
            if (palette[i] == state) {
                return i;
            }
        }
        return -1;
    }

    /** Re-packs into {@code newBits}, indirect (keeping palette ids) or direct (global ids). */
    private void grow(int newBits, boolean direct) {
        long[] newData = new long[longsFor(newBits)];
        for (int i = 0; i < SIZE; i++) {
            int state = get(i);
            write(newData, newBits, i, direct ? state : paletteId(state));
        }
        if (direct) {
            palette = null;
            paletteSize = 0;
        } else {
            palette = Arrays.copyOf(palette, 1 << newBits);
        }
        bits = newBits;
        data = newData;
    }

    private static int read(long[] data, int bits, int index) {
        int perLong = 64 / bits;
        int word = index / perLong;
        int shift = (index - word * perLong) * bits;
        return (int) ((data[word] >>> shift) & ((1L << bits) - 1));
    }

    private static void write(long[] data, int bits, int index, int value) {
        int perLong = 64 / bits;
        int word = index / perLong;
        int shift = (index - word * perLong) * bits;
        long mask = ((1L << bits) - 1) << shift;
        data[word] = (data[word] & ~mask) | (((long) value << shift) & mask);
    }

    /** Bits per entry currently used ({@code 0} for a single value). */
    public int bits() {
        return bits;
    }

    public boolean isDirect() {
        return palette == null;
    }

    public PalettedSection copy() {
        return new PalettedSection(palette == null ? null : palette.clone(), paletteSize, bits,
                data == null ? null : data.clone(), globalBits);
    }
}
