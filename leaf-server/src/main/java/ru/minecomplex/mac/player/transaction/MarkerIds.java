package ru.minecomplex.mac.player.transaction;

/**
 * Allocates ping ids for markers. Ids carry a fixed high byte ({@code 0x4D}) so they are easy to spot in captures;
 * recognition of pongs never relies on the prefix but on the FIFO of observed markers.
 */
public final class MarkerIds {
    public static final int PREFIX = 0x4D00_0000;
    private static final int COUNTER_MASK = 0x00FF_FFFF;

    private int next;

    public int next() {
        int id = PREFIX | (next & COUNTER_MASK);
        next++;
        return id;
    }

    public static boolean hasPrefix(int id) {
        return (id & ~COUNTER_MASK) == PREFIX;
    }
}
