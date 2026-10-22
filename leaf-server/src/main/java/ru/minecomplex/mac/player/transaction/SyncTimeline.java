package ru.minecomplex.mac.player.transaction;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Tracks our markers on the clientbound wire and their acknowledgements. Pure bookkeeping: markers enter only when
 * observed by the native encoder, and a pong acknowledges its marker and every earlier one (the client processes
 * pings in wire order, {@code transactions.md} §1.3).
 *
 * <p>Not thread-safe; confined to the player's event loop like the rest of the session.
 */
public final class SyncTimeline {
    private final ArrayDeque<Marker> outstanding = new ArrayDeque<>();
    private long lastObservedMarkerSeq = -1;
    private long certainThrough = -1;
    private Marker lastAcknowledged;
    private long lastRoundTripNanos = -1;
    private long acknowledgedCount;
    private long skippedCount;

    /** Our ping {@code id} was seen leaving at wire position {@code outboundSeq}. */
    public void markerObserved(int id, long outboundSeq, long nanos) {
        if (outboundSeq <= lastObservedMarkerSeq) {
            throw new IllegalStateException("Markers must be observed in wire order: " + outboundSeq + " after " + lastObservedMarkerSeq);
        }
        lastObservedMarkerSeq = outboundSeq;
        outstanding.addLast(new Marker(id, outboundSeq, nanos));
    }

    /** True if {@code id} is one of our markers still waiting for its pong. */
    public boolean isOutstanding(int id) {
        for (Marker marker : outstanding) {
            if (marker.id() == id) {
                return true;
            }
        }
        return false;
    }

    public PongResult pong(int id, long nanos) {
        int skipped = 0;
        Iterator<Marker> iterator = outstanding.iterator();
        Marker match = null;
        while (iterator.hasNext()) {
            Marker marker = iterator.next();
            if (marker.id() == id) {
                match = marker;
                break;
            }
            skipped++;
        }
        if (match == null) {
            return PongResult.foreign();
        }
        for (int i = 0; i <= skipped; i++) {
            outstanding.removeFirst();
        }
        certainThrough = Math.max(certainThrough, match.outboundSeq());
        lastAcknowledged = match;
        lastRoundTripNanos = nanos - match.sentNanos();
        acknowledgedCount += skipped + 1;
        skippedCount += skipped;
        return PongResult.acknowledged(match, skipped);
    }

    /**
     * The client handled the clientbound packet at wire position {@code outboundSeq} and so every earlier one: a reply it
     * sends from that packet's main-thread handler, in wire order like a pong (a teleport's {@code accept_teleportation},
     * {@code transactions.md} §2).
     */
    public void handledThrough(long outboundSeq) {
        certainThrough = Math.max(certainThrough, outboundSeq);
    }

    /**
     * The window for a serverbound packet processed now.
     *
     * @param lastObservedOutboundSeq wire position of the last clientbound packet observed so far; later packets
     *                                cannot have been processed by the client (causality)
     */
    public SyncWindow window(long lastObservedOutboundSeq) {
        Marker first = outstanding.peekFirst();
        long possibleBefore = first != null ? first.outboundSeq() : lastObservedOutboundSeq + 1;
        return new SyncWindow(certainThrough, Math.max(possibleBefore, certainThrough + 1));
    }

    public long certainThrough() {
        return certainThrough;
    }

    public Marker lastAcknowledged() {
        return lastAcknowledged;
    }

    public Marker oldestOutstanding() {
        return outstanding.peekFirst();
    }

    public int outstandingCount() {
        return outstanding.size();
    }

    /** Round trip of the last acknowledged marker; telemetry only, never evidence of state. */
    public long lastRoundTripNanos() {
        return lastRoundTripNanos;
    }

    public long acknowledgedCount() {
        return acknowledgedCount;
    }

    /** Markers acknowledged implicitly by a later pong (impossible for a vanilla client). */
    public long skippedCount() {
        return skippedCount;
    }
}
