package ru.minecomplex.mac.player.transaction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Clientbound changes in wire order, kept until every consumer has moved past them. Consumers hold their own
 * cursor (the sequence up to which they applied changes) and read ranges; the owner truncates what nobody needs.
 */
public final class ChangeLog<E> {
    private final ArrayDeque<Entry<E>> entries = new ArrayDeque<>();
    private long lastSeq = -1;

    public record Entry<E>(long seq, E change) {
    }

    public void append(long seq, E change) {
        if (seq <= lastSeq) {
            throw new IllegalStateException("Changes must be appended in wire order: " + seq + " after " + lastSeq);
        }
        lastSeq = seq;
        entries.addLast(new Entry<>(seq, change));
    }

    /** Entries with {@code afterExclusive < seq < beforeExclusive}, in wire order. */
    public List<Entry<E>> between(long afterExclusive, long beforeExclusive) {
        List<Entry<E>> result = new ArrayList<>();
        for (Entry<E> entry : entries) {
            if (entry.seq() >= beforeExclusive) {
                break;
            }
            if (entry.seq() > afterExclusive) {
                result.add(entry);
            }
        }
        return result;
    }

    /** Drops entries with {@code seq <= through}. */
    public void truncateThrough(long through) {
        while (!entries.isEmpty() && entries.peekFirst().seq() <= through) {
            entries.removeFirst();
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public Entry<E> first() {
        return entries.peekFirst();
    }

    public Entry<E> last() {
        return entries.peekLast();
    }

    public Iterator<Entry<E>> iterator() {
        return entries.iterator();
    }
}
