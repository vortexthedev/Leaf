package ru.minecomplex.mac.entity;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.inventory.item.Stacks;

import ru.minecomplex.mac.Wire;

import ru.minecomplex.mac.replica.CompensatedEntities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.fixture.outbound.EffectRemove;
import ru.minecomplex.mac.fixture.outbound.EffectUpdate;
import ru.minecomplex.mac.fixture.outbound.EntityAttributes;
import ru.minecomplex.mac.fixture.outbound.EntityEquipment;
import ru.minecomplex.mac.fixture.outbound.EntityRemove;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;

/** Other entities' data in {@link CompensatedEntities}: handler semantics, forks, generations. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ClientEntitiesDataTest {
    private static EntitySpawn horse(int id) {
        return new EntitySpawn(id, "minecraft:horse", 0, 0, 0, 0, 0, 0, 0, false, 0, 0, 0);
    }

    @Test
    void attributesEffectsAndSaddleFollowTheHandlers() {
        var entities = new CompensatedEntities(1);
        entities.spawn(Wire.of(horse(2)), chunk -> false);
        entities.attributes(Wire.of(EntityAttributes.of(2, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:movement_speed", 0.2, List.of()),
                new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:jump_strength", 0.7, List.of())))));
        entities.attributes(Wire.of(EntityAttributes.of(2, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:movement_speed", 0.3,
                List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot.Modifier("minecraft:test", 0.1, 0)))))));
        var attributes = entities.attributes(2);
        assertEquals(0.3, attributes.get("minecraft:movement_speed").base());
        assertEquals(1, attributes.get("minecraft:movement_speed").modifiers().size());
        assertEquals(0.7, attributes.get("minecraft:jump_strength").base());
        entities.effect(Wire.of(new EffectUpdate(2, "minecraft:jump_boost", 1, 100, false, true, true, false)));
        assertEquals(1, entities.effects(2).size());
        entities.effect(Wire.of(new EffectRemove(2, "minecraft:jump_boost")));
        assertTrue(entities.effects(2).isEmpty());
        entities.equipment(Wire.of(EntityEquipment.of(2, WireItem.of("minecraft:saddle", 1), null)));
        assertEquals("minecraft:saddle", Stacks.key(entities.saddle(2)));
        // a packet without the slot keeps it, an empty stack clears it
        entities.equipment(Wire.of(EntityEquipment.of(2, null, null)));
        assertEquals("minecraft:saddle", Stacks.key(entities.saddle(2)));
        entities.equipment(Wire.of(EntityEquipment.of(2, WireItem.of("minecraft:air", 0), null)));
        assertNull(entities.saddle(2));
        // the own player's data belongs to the player model
        entities.attributes(Wire.of(EntityAttributes.of(1, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:scale", 2.0, List.of())))));
        assertTrue(entities.attributes(1).isEmpty());
    }

    @Test
    void forksAreIndependentAndGenerationsForget() {
        var entities = new CompensatedEntities(1);
        entities.spawn(Wire.of(horse(2)), chunk -> false);
        entities.syncedData(Wire.of(new EntitySyncedData(2, 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(Map.of(16, true)))));
        entities.equipment(Wire.of(EntityEquipment.of(2, WireItem.of("minecraft:saddle", 1), null)));
        var fork = entities.copy();
        fork.syncedData(Wire.of(new EntitySyncedData(2, 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(Map.of(16, false)))));
        fork.attributes(Wire.of(EntityAttributes.of(2, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:scale", 2.0, List.of())))));
        assertEquals(true, entities.mobItems(2).get(16));
        assertEquals(false, fork.mobItems(2).get(16));
        assertTrue(entities.attributes(2).isEmpty());
        assertTrue(fork.assigned(2, 16) > entities.assigned(2, 16));
        // removal and id reuse forget everything of the old generation
        entities.remove(Wire.of(new EntityRemove(new int[]{2})));
        entities.spawn(Wire.of(horse(2)), chunk -> false);
        assertTrue(entities.mobItems(2).isEmpty());
        assertNull(entities.saddle(2));
        assertEquals(0L, entities.assigned(2, 16));
        fork.spawn(Wire.of(horse(2)), chunk -> false);
        assertNull(fork.saddle(2));
    }

    @Test
    void mobItemsRoundTrip() {
        Map<Integer, Object> items = Map.of(15, (byte) 1, 16, true, 17, 7, 19, -40L, 20, 0.5F);
        var data = new EntitySyncedData(3, 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(items));
        assertEquals(items, data.mobItemValues());
        assertEquals(data, new EntitySyncedData(3, 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(items)));
    }
}
