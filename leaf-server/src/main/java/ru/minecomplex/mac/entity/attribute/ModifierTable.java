package ru.minecomplex.mac.entity.attribute;

import java.util.ArrayList;
import java.util.List;

/**
 * The modifiers of one operation, stored exactly like vanilla's {@code Object2ObjectOpenHashMap<Identifier,
 * AttributeModifier>} (fastutil 8.5): linear probing on {@code HashCommon.mix(hashCode)}, backward-shift deletion,
 * growth past the 0.75 fill, and iteration from the last slot down. Only the iteration order is observable, and it
 * is what makes {@code AttributeInstance.calculateValue} sum and multiply in vanilla's order.
 */
final class ModifierTable {
    private static final float LOAD_FACTOR = 0.75F;
    private static final int DEFAULT_EXPECTED = 16;

    private final int minN;
    private int n;
    private int mask;
    private int maxFill;
    private Identifier[] keys;
    private AttributeModifier[] values;
    private int size;

    ModifierTable() {
        minN = n = arraySize(DEFAULT_EXPECTED, LOAD_FACTOR);
        mask = n - 1;
        maxFill = maxFill(n, LOAD_FACTOR);
        keys = new Identifier[n + 1];
        values = new AttributeModifier[n + 1];
    }

    private ModifierTable(ModifierTable other) {
        minN = other.minN;
        n = other.n;
        mask = other.mask;
        maxFill = other.maxFill;
        keys = other.keys.clone();
        values = other.values.clone();
        size = other.size;
    }

    /** Same slots, so the same iteration order. */
    ModifierTable copy() {
        return new ModifierTable(this);
    }

    /**
     * {@code new Object2ObjectOpenHashMap<>()} followed by {@code putAll(other)}: {@code tryCapacity(size + m.size())},
     * then the entries are put in {@code other}'s iteration order.
     */
    static ModifierTable putAllOf(ModifierTable other) {
        ModifierTable table = new ModifierTable();
        table.tryCapacity(other.size);
        for (AttributeModifier modifier : other.values()) {
            table.put(modifier.id(), modifier);
        }
        return table;
    }

    /** {@code Object2ObjectOpenHashMap.tryCapacity}. */
    private void tryCapacity(long capacity) {
        int needed = (int) Math.min(1 << 30, Math.max(2L, nextPowerOfTwo((long) Math.ceil(capacity / LOAD_FACTOR))));
        if (needed > n) {
            rehash(needed);
        }
    }

    int size() {
        return size;
    }

    void put(Identifier key, AttributeModifier value) {
        int pos = find(key);
        if (pos >= 0) {
            values[pos] = value;
            return;
        }
        pos = -pos - 1;
        keys[pos] = key;
        values[pos] = value;
        if (size++ >= maxFill) {
            rehash(arraySize(size + 1, LOAD_FACTOR));
        }
    }

    void remove(Identifier key) {
        int pos = mix(key.hashCode()) & mask;
        Identifier current = keys[pos];
        if (current == null) {
            return;
        }
        while (!key.equals(current)) {
            pos = pos + 1 & mask;
            current = keys[pos];
            if (current == null) {
                return;
            }
        }
        values[pos] = null;
        size--;
        shiftKeys(pos);
        if (n > minN && size < maxFill / 4 && n > DEFAULT_EXPECTED) {
            rehash(n / 2);
        }
    }

    /** Values in fastutil iteration order: slots from {@code n - 1} down to 0. */
    List<AttributeModifier> values() {
        List<AttributeModifier> result = new ArrayList<>(size);
        for (int pos = n - 1; pos >= 0 && result.size() < size; pos--) {
            if (keys[pos] != null) {
                result.add(values[pos]);
            }
        }
        return result;
    }

    private int find(Identifier key) {
        int pos = mix(key.hashCode()) & mask;
        Identifier current = keys[pos];
        if (current == null) {
            return -(pos + 1);
        }
        if (key.equals(current)) {
            return pos;
        }
        while ((current = keys[pos = pos + 1 & mask]) != null) {
            if (key.equals(current)) {
                return pos;
            }
        }
        return -(pos + 1);
    }

    private void shiftKeys(int pos) {
        while (true) {
            int last = pos;
            Identifier current;
            boolean moved = false;
            for (pos = pos + 1 & mask; (current = keys[pos]) != null; pos = pos + 1 & mask) {
                int slot = mix(current.hashCode()) & mask;
                if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos) {
                    keys[last] = current;
                    values[last] = values[pos];
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                keys[last] = null;
                values[last] = null;
                return;
            }
        }
    }

    private void rehash(int newN) {
        int newMask = newN - 1;
        Identifier[] newKeys = new Identifier[newN + 1];
        AttributeModifier[] newValues = new AttributeModifier[newN + 1];
        int i = n;
        for (int j = size; j-- != 0; ) {
            while (keys[--i] == null) {
                // skip empty slots, scanning down like fastutil
            }
            int pos = mix(keys[i].hashCode()) & newMask;
            if (newKeys[pos] != null) {
                while (newKeys[pos = pos + 1 & newMask] != null) {
                    // probe
                }
            }
            newKeys[pos] = keys[i];
            newValues[pos] = values[i];
        }
        n = newN;
        mask = newMask;
        maxFill = maxFill(n, LOAD_FACTOR);
        keys = newKeys;
        values = newValues;
    }

    /** {@code HashCommon.mix(int)}. */
    static int mix(int x) {
        int h = x * -1640531527;
        return h ^ h >>> 16;
    }

    /** {@code HashCommon.arraySize(int, float)}. */
    static int arraySize(int expected, float f) {
        long s = Math.max(2L, nextPowerOfTwo((long) Math.ceil(expected / f)));
        if (s > 1 << 30) {
            throw new IllegalArgumentException("Too large (" + expected + " expected elements with load factor " + f + ")");
        }
        return (int) s;
    }

    /** {@code HashCommon.maxFill(int, float)}. */
    static int maxFill(int n, float f) {
        return Math.min((int) Math.ceil(n * f), n - 1);
    }

    private static long nextPowerOfTwo(long x) {
        return 1L << 64 - Long.numberOfLeadingZeros(x - 1L);
    }
}
