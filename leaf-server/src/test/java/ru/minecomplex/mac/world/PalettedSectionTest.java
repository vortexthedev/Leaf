package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import ru.minecomplex.mac.util.collection.LongMap;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PalettedSectionTest {
    private static final int GLOBAL_BITS = 16;

    /** Packs values like vanilla {@code SimpleBitStorage}: no value spans two longs. */
    private static long[] pack(int bits, int[] values) {
        int perLong = 64 / bits;
        long[] data = new long[(values.length + perLong - 1) / perLong];
        for (int i = 0; i < values.length; i++) {
            data[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
        }
        return data;
    }

    @Test
    void readsWireData() {
        Random random = new Random(1);
        for (int bits : new int[]{4, 5, 6, 7, 8}) {
            int[] palette = new int[1 << bits];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = random.nextInt(30_000);
            }
            int[] ids = new int[PalettedSection.SIZE];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = random.nextInt(palette.length);
            }
            PalettedSection section = PalettedSection.of(bits, palette, pack(bits, ids), GLOBAL_BITS);
            for (int i = 0; i < ids.length; i++) {
                assertEquals(palette[ids[i]], section.get(i));
            }
        }
        int[] direct = new int[PalettedSection.SIZE];
        for (int i = 0; i < direct.length; i++) {
            direct[i] = random.nextInt(1 << 15);
        }
        PalettedSection section = PalettedSection.of(15, null, pack(15, direct), GLOBAL_BITS);
        assertTrue(section.isDirect());
        for (int i = 0; i < direct.length; i++) {
            assertEquals(direct[i], section.get(i));
        }
    }

    @Test
    void indexOrderIsVanilla() {
        assertEquals(0x123, PalettedSection.index(3, 1, 2), "(y << 8) | (z << 4) | x");
    }

    @Test
    void writesGrowFromSingleValueToDirect() {
        Random random = new Random(7);
        PalettedSection section = PalettedSection.single(0, GLOBAL_BITS);
        int[] reference = new int[PalettedSection.SIZE];
        for (int step = 0; step < 20_000; step++) {
            int index = random.nextInt(PalettedSection.SIZE);
            // Few distinct states first (palette growth), then many (switch to direct storage)
            int state = step < 5_000 ? random.nextInt(40) : random.nextInt(30_000);
            section.set(index, state);
            reference[index] = state;
        }
        assertTrue(section.isDirect());
        for (int i = 0; i < reference.length; i++) {
            assertEquals(reference[i], section.get(i));
        }
    }

    @Test
    void copiesAreIndependent() {
        PalettedSection section = PalettedSection.single(5, GLOBAL_BITS);
        PalettedSection copy = section.copy();
        copy.set(1, 2, 3, 9);
        assertEquals(5, section.get(1, 2, 3));
        assertEquals(9, copy.get(1, 2, 3));
    }

    @Test
    void rejectsWrongDataLength() {
        assertThrows(IllegalArgumentException.class, () -> PalettedSection.of(4, new int[]{0, 1}, new long[3], GLOBAL_BITS));
    }

    @Test
    void longMapMatchesHashMap() {
        Random random = new Random(3);
        LongMap<Long> map = new LongMap<>();
        Map<Long, Long> reference = new HashMap<>();
        for (int i = 0; i < 200_000; i++) {
            long key = random.nextInt(2_000) - 1_000L + ((long) random.nextInt(3) << 40);
            switch (random.nextInt(3)) {
                case 0 -> assertEquals(reference.put(key, (long) i), map.put(key, (long) i));
                case 1 -> assertEquals(reference.remove(key), map.remove(key));
                default -> assertEquals(reference.get(key), map.get(key));
            }
            assertEquals(reference.size(), map.size());
        }
        for (Map.Entry<Long, Long> entry : reference.entrySet()) {
            assertEquals(entry.getValue(), map.get(entry.getKey()));
        }
        map.clear();
        assertFalse(map.get(reference.keySet().iterator().next()) != null);
    }
}
