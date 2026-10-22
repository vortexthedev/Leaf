package ru.minecomplex.mac.player.transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * A client-side value that the server replaces with packets (last writer wins): e.g. abilities, game mode, food.
 * Keeps the value the client certainly has ({@code ACKNOWLEDGED_BY_CLIENT}) and the values sent after it
 * ({@code SENT_TO_CLIENT}); for a window, the client has one of the prefix results ({@code UNCERTAIN}).
 *
 * <p>Windows passed in must not go backwards: acknowledged values are folded in and forgotten.
 */
public final class Synced<T> {
    private final ChangeLog<T> pending = new ChangeLog<>();
    private T settled;
    private T latestSent;
    private long settledThrough = -1;

    public Synced(T initial) {
        this.settled = initial;
        this.latestSent = initial;
    }

    /** A packet carrying {@code value} was observed at wire position {@code seq}. */
    public void sent(long seq, T value) {
        pending.append(seq, value);
        latestSent = value;
    }

    /** The value the client certainly has for serverbound packets with this window. */
    public T settled(SyncWindow window) {
        settle(window);
        return settled;
    }

    /**
     * Every value the client may have: the settled value followed by the result of each longer admissible prefix.
     * A single element means the value is certain.
     */
    public List<T> candidates(SyncWindow window) {
        settle(window);
        List<T> result = new ArrayList<>(1 + pending.size());
        result.add(settled);
        for (ChangeLog.Entry<T> entry : pending.between(window.certainThrough(), window.possibleBefore())) {
            result.add(entry.change());
        }
        return result;
    }

    public boolean isCertain(SyncWindow window) {
        settle(window);
        return pending.isEmpty() || !window.isPossible(pending.first().seq());
    }

    /** Most recent value sent; server intent, not client knowledge. For diagnostics. */
    public T latestSent() {
        return latestSent;
    }

    private void settle(SyncWindow window) {
        if (window.certainThrough() < settledThrough) {
            throw new IllegalStateException("Window " + window + " is older than the settled state (" + settledThrough + ")");
        }
        settledThrough = window.certainThrough();
        while (!pending.isEmpty() && pending.first().seq() <= settledThrough) {
            settled = pending.first().change();
            pending.truncateThrough(pending.first().seq());
        }
    }
}
