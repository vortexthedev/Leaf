package ru.minecomplex.mac.entity;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import ru.minecomplex.mac.replica.CompensatedEntities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;

/** The data ids {@link MobData} reads match the native MAET registry. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class MobDataTest {
    @Test
    void idsMatchNativeRegistry() {
        var types = EntityTypes.get();
        var entities = new CompensatedEntities(1);
        var data = new MobData(entities, 2);
        assertEquals(types.dataId("DATA_ID_FLAGS"), data.horseFlagsId());
        assertEquals(types.dataId("DASH"), data.camelDashId());
        assertEquals(types.dataId("LAST_POSE_CHANGE_TICK"), data.camelPoseTickId());
        assertEquals(15, types.dataId("DATA_MOB_FLAGS_ID"));
        assertEquals(16, types.dataId("DATA_BABY_ID"));
    }

    @Test
    void itemsMergeAndDefault() {
        var entities = new CompensatedEntities(1);
        entities.spawn(Wire.of(new EntitySpawn(2, "minecraft:camel", 0, 0, 0, 0, 0, 0, 0, false, 0, 0, 0)), chunk -> false);
        var data = new MobData(entities, 2);
        assertFalse(data.baby());
        assertEquals(0L, data.camelLastPoseChangeTick());
        assertEquals(Pose.STANDING, data.pose());
        entities.syncedData(Wire.of(new EntitySyncedData(2, EntitySyncedData.POSE, (byte) 0, false, "SITTING", (byte) 0, 0.0F,
                EntitySyncedData.encodeMobItems(Map.of(16, true, 18, (byte) 34, 20, -40L)))));
        entities.syncedData(Wire.of(new EntitySyncedData(2, 0, (byte) 0, false, "", (byte) 0, 0.0F,
                EntitySyncedData.encodeMobItems(Map.of(19, true)))));
        data = new MobData(entities, 2);
        assertTrue(data.baby());
        assertTrue(data.horseFlag(2));
        assertTrue(data.horseFlag(32));
        assertFalse(data.horseFlag(16));
        assertTrue(data.camelDash());
        assertEquals(-40L, data.camelLastPoseChangeTick());
        assertEquals(Pose.SITTING, data.pose());
        assertTrue(entities.assigned(2, 19) > entities.assigned(2, 18));
        assertEquals(entities.assigned(2, 18), entities.assigned(2, CompensatedEntities.POSE_DATA_ID));
        // the local player's data belongs to the player model; unknown entities drop it
        entities.syncedData(Wire.of(new EntitySyncedData(9, 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(Map.of(16, true)))));
        assertEquals(Map.of(), entities.mobItems(9));
    }
}
