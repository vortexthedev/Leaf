package ru.minecomplex.mac.fixture.outbound;

import java.util.Arrays;

/**
 * {@code section_blocks_update}: entries as on the wire, {@code state << 12 | x << 8 | z << 4 | y} with section-local
 * coordinates ({@code SectionPos.sectionRelativePos}).
 */
public record SectionBlocksUpdate(int sectionX, int sectionY, int sectionZ, long[] entries) implements OutboundEvent {

    public static int state(long entry) {
        return (int) (entry >>> 12);
    }

    public static int localX(long entry) {
        return (int) (entry >>> 8 & 15);
    }

    public static int localZ(long entry) {
        return (int) (entry >>> 4 & 15);
    }

    public static int localY(long entry) {
        return (int) (entry & 15);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SectionBlocksUpdate that && sectionX == that.sectionX && sectionY == that.sectionY
                && sectionZ == that.sectionZ && Arrays.equals(entries, that.entries);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * sectionX + sectionY) + sectionZ) + Arrays.hashCode(entries);
    }

    @Override
    public String toString() {
        return "SectionBlocksUpdate[" + sectionX + ", " + sectionY + ", " + sectionZ + ", " + entries.length + " blocks]";
    }
}
