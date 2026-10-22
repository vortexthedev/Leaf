package ru.minecomplex.mac.player.transaction;

/**
 * What the client could have processed when it produced a serverbound packet, expressed in outbound sequence
 * numbers (wire positions of clientbound packets). See {@code docs/research/architecture.md} §2.
 *
 * <ul>
 *     <li>{@code seq <= certainThrough}: processed (ACKNOWLEDGED_BY_CLIENT)</li>
 *     <li>{@code certainThrough < seq < possibleBefore}: a prefix of these was processed (UNCERTAIN)</li>
 *     <li>{@code seq >= possibleBefore}: not processed</li>
 * </ul>
 *
 * @param certainThrough outbound sequence of the last acknowledged marker, {@code -1} if none
 * @param possibleBefore sequence of the first unacknowledged marker, or one past the last observed packet
 */
public record SyncWindow(long certainThrough, long possibleBefore) {
    public static final SyncWindow NOTHING_SENT = new SyncWindow(-1, 0);

    public SyncWindow {
        if (possibleBefore <= certainThrough) {
            throw new IllegalArgumentException("Empty window " + certainThrough + ".." + possibleBefore);
        }
    }

    public boolean isCertain(long seq) {
        return seq <= certainThrough;
    }

    public boolean isPossible(long seq) {
        return seq < possibleBefore;
    }

    public boolean isUncertain(long seq) {
        return seq > certainThrough && seq < possibleBefore;
    }

    /** The least window containing both (used when a frame's packets straddle an anomalous acknowledgement). */
    public SyncWindow span(SyncWindow later) {
        return new SyncWindow(Math.min(certainThrough, later.certainThrough), Math.max(possibleBefore, later.possibleBefore));
    }
}
