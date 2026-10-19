package org.dreeam.leaf.orebfuscator.player;

import org.jspecify.annotations.NullMarked;

/**
 * Proximity hidden blocks of a chunk that was sent to a player. Only used by one proximity worker at
 * a time, see {@link OrebfuscatorPlayer}.
 */
@NullMarked
public final class ProximityChunk {

    private long[] positions;
    private boolean[] lavaObfuscated;
    private int size;

    ProximityChunk(final long[] positions, final boolean[] lavaObfuscated) {
        this.positions = positions;
        this.lavaObfuscated = lavaObfuscated;
        this.size = positions.length;
    }

    public boolean isEmpty() {
        return this.size <= 0;
    }

    public int size() {
        return this.size;
    }

    public long position(final int index) {
        return this.positions[index];
    }

    public boolean lavaObfuscated(final int index) {
        return this.lavaObfuscated[index];
    }

    /**
     * Removes the blocks marked in {@code removed} (indices below {@link #size()}).
     */
    public void removeAll(final boolean[] removed) {
        int newSize = 0;
        for (int i = 0; i < this.size; i++) {
            if (!removed[i]) {
                newSize++;
            }
        }
        if (newSize == this.size) {
            return;
        }

        final long[] newPositions = new long[newSize];
        final boolean[] newLava = new boolean[newSize];
        int index = 0;
        for (int i = 0; i < this.size; i++) {
            if (!removed[i]) {
                newPositions[index] = this.positions[i];
                newLava[index++] = this.lavaObfuscated[i];
            }
        }

        this.positions = newPositions;
        this.lavaObfuscated = newLava;
        this.size = newSize;
    }
}
