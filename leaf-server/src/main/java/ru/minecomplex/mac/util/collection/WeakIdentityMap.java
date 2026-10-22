package ru.minecomplex.mac.util.collection;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A thread-safe map from objects, by identity and weakly held, to values.
 */
public final class WeakIdentityMap<K, V> {
    private final Map<Key, V> map = new HashMap<>();
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    public synchronized void put(K key, V value) {
        expunge();
        map.put(new Key(key, queue), value);
    }

    public synchronized V get(K key) {
        expunge();
        Key probe = new Key(key, null);
        return map.get(probe);
    }

    private void expunge() {
        for (Object stale; (stale = queue.poll()) != null; ) map.remove(stale);
    }

    private static final class Key extends WeakReference<Object> {
        final int hash;

        Key(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key) || key.hash != hash) return false;
            Object referent = get();
            return referent != null && referent == key.get();
        }
    }
}
