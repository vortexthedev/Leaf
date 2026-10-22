package ru.minecomplex.mac.util.collection;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Open-addressing {@code long → V} map without boxing (linear probing, backward-shift deletion). Not thread-safe.
 */
public final class LongMap<V> {
    private long[] keys;
    private Object[] values;
    private int size;
    private int mask;

    public LongMap() {
        this(16);
    }

    public LongMap(int expected) {
        int capacity = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
        keys = new long[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
    }

    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        int i = hash(key) & mask;
        while (values[i] != null) {
            if (keys[i] == key) {
                return (V) values[i];
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public V put(long key, V value) {
        if (value == null) {
            throw new NullPointerException("null values are not supported");
        }
        int i = hash(key) & mask;
        while (values[i] != null) {
            if (keys[i] == key) {
                V old = (V) values[i];
                values[i] = value;
                return old;
            }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        values[i] = value;
        if (++size * 2 > keys.length) {
            resize();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public V remove(long key) {
        int i = hash(key) & mask;
        while (values[i] != null) {
            if (keys[i] == key) {
                V old = (V) values[i];
                shiftBack(i);
                size--;
                return old;
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    private void shiftBack(int hole) {
        int i = hole;
        while (true) {
            i = (i + 1) & mask;
            if (values[i] == null) {
                break;
            }
            int home = hash(keys[i]) & mask;
            // Move the entry into the hole unless its home lies cyclically in (hole, i]
            boolean between = hole <= i ? home > hole && home <= i : home > hole || home <= i;
            if (!between) {
                keys[hole] = keys[i];
                values[hole] = values[i];
                hole = i;
            }
        }
        values[hole] = null;
    }

    private void resize() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new Object[oldKeys.length * 2];
        mask = keys.length - 1;
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldValues[i] != null) {
                put(oldKeys[i], oldValues[i] == null ? null : cast(oldValues[i]));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private V cast(Object value) {
        return (V) value;
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(values, null);
        size = 0;
    }

    @SuppressWarnings("unchecked")
    public void forEachValue(Consumer<V> action) {
        for (Object value : values) {
            if (value != null) {
                action.accept((V) value);
            }
        }
    }
}
