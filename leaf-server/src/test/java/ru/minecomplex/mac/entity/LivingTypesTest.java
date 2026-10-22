package ru.minecomplex.mac.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

/** MAET v6 living section: every living type of every protocol is built by the client generator. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LivingTypesTest {
    @Test
    void everyLivingTypeIsConstructed() {
        var types = EntityTypes.get();
        var missing = new ArrayList<String>();
        int living = 0;
        for (String key : types.keys()) {
            var type = types.type(key);
            if (type == null || !type.living()) continue;
            living++;
            // players have no factory: their dimensions are the player table
            if (types.living(key) == null && !key.equals("minecraft:player")) missing.add(key);
        }
        assertTrue(living > 70, "" + living);
        assertEquals(java.util.List.of(), missing);
    }

    @Test
    void babyAndSizeItemsAreKnown() {
        var types = EntityTypes.get();
        for (String key : new String[]{"minecraft:zombie", "minecraft:cow", "minecraft:piglin", "minecraft:villager"}) {
            var living = types.living(key);
            assertNotNull(living, key);
            assertTrue(living.canBeBaby(), "" + key);
            assertTrue(living.item("DATA_BABY_ID") > 0, "" + key);
        }
        assertTrue(types.living("minecraft:slime").item("ID_SIZE") > 0);
        assertTrue(types.living("minecraft:pufferfish").item("PUFF_STATE") > 0);
    }
}
