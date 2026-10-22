package ru.minecomplex.mac.replay;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Keeps the most recent inputs of a session in memory. When capped, the oldest entries are dropped, so a snapshot is
 * replayable only if it starts at a session start; use an unbounded buffer (cap {@code 0}) for complete recordings.
 */
public final class RecordingBuffer implements SessionRecorder {
    private final ArrayDeque<Recording.Entry> entries = new ArrayDeque<>();
    private final int cap;
    private boolean truncated;

    /** @param cap maximum number of entries kept, {@code 0} for unbounded */
    public RecordingBuffer(int cap) {
        this.cap = cap;
    }

    @Override
    public void packet(Recording.Packet packet) {
        add(packet);
    }

    @Override
    public void markerAllocated(long nanos, int id) {
        add(new Recording.MarkerAllocated(nanos, id));
    }

    private void add(Recording.Entry entry) {
        if (cap > 0 && entries.size() == cap) {
            entries.removeFirst();
            truncated = true;
        }
        entries.addLast(entry);
    }

    /** Whether entries were dropped (the snapshot does not start at the session start). */
    public boolean truncated() {
        return truncated;
    }

    public Recording snapshot(String description) {
        return new Recording(description, List.copyOf(entries));
    }
}
