package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import java.util.List;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import static org.junit.jupiter.api.Assertions.*;
import ru.minecomplex.mac.replica.WorldModel;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class VehicleTimelineTest {
    @Test
    void entityPromotionInvalidatesEphemeralViewsButPreservesPersistentHypotheses() {
        for (OutboundEvent event : List.of(spawn(19, "minecraft:pig"), new EntityRemove(new int[]{18}),
                new EntityPassengers(17, new int[]{}))) {
            var model = model(); model.sent(4, Wire.of(event));
            var view = model.viewAt(new SyncWindow(3, 5), 4);
            try (var persistent = model.hypothesis(new SyncWindow(3, 5), 4)) {
                model.settle(new SyncWindow(4, 5));
                assertThrows(IllegalStateException.class, view::entities);
                assertNotNull(persistent.world().entities().passengers().get(1));
            }
        }
    }
    static EntitySpawn spawn(int id, String type) { return new EntitySpawn(id, type, 0.5, 64, 0.5, 90, 0, 90, 0, false, 0, 0, 0); }
    static WorldModel model() {
        var model = new WorldModel(BlockRegistry.get()); model.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 128, 2)));
        model.sent(1, Wire.of(spawn(17, "minecraft:oak_boat"))); model.sent(2, Wire.of(spawn(18, "minecraft:horse")));
        model.sent(3, Wire.of(new EntityPassengers(17, new int[]{1}))); return model;
    }
    @Test
    void passengerPrefixAndIdReusePreserveEntityGenerations() {
        var model = model(); var window = new SyncWindow(0, 4);
        assertArrayEquals(new long[]{0, 1, 2, 3}, model.prefixEnds(window));
        try (var before = model.hypothesis(window, 2); var mounted = model.hypothesis(window, 3)) {
            var graph = mounted.world().entities().passengers(); Object generation = graph.get(17).generation();
            assertEquals(1, before.world().entities().passengers().root(1)); assertEquals(17, graph.root(1));
            model.sent(4, Wire.of(new EntityRemove(new int[]{17}))); model.sent(5, Wire.of(spawn(17, "minecraft:oak_boat")));
            mounted.apply(new SyncWindow(0, 6), 5); assertEquals(1, graph.root(1)); assertNotSame(generation, graph.get(17).generation());
            assertEquals(List.of(), graph.get(17).passengers()); assertEquals(0, graph.get(1).boardingCooldown());
        }
    }
    @Test
    void latePongKeepsLocalDismountAndForksIndependent() {
        var model = model(); var window = new SyncWindow(0, 4);
        try (var h = model.hypothesis(window, 3); var fork = h.fork()) {
            h.world().entities().passengers().stopRiding(1);
            model.settle(new SyncWindow(3, 4)); h.apply(new SyncWindow(3, 4), 3);
            assertEquals(1, h.world().entities().passengers().root(1)); assertEquals(17, fork.world().entities().passengers().root(1));
            assertEquals(17, model.acknowledged(new SyncWindow(3, 4)).entities().passengers().root(1));
        }
    }
    @Test
    void sameLevelRespawnReplacesPlayerAndDimensionRespawnClearsForeignEntities() {
        var model = model();
        try (var h = model.hypothesis(new SyncWindow(0, 4), 3)) {
            Object generation = h.world().entities().passengers().get(1).generation();
            model.sent(4, Wire.of(new Respawn("minecraft:overworld", 0, 0, 128))); h.apply(new SyncWindow(0, 5), 4);
            assertNotSame(generation, h.world().entities().passengers().get(1).generation());
            assertEquals(1, h.world().entities().passengers().root(1)); assertNotNull(h.world().entities().passengers().get(17));
            model.sent(5, Wire.of(new Respawn("minecraft:the_nether", 0, 0, 128))); h.apply(new SyncWindow(0, 6), 5);
            assertEquals(1, h.world().entities().passengers().root(1)); assertNull(h.world().entities().passengers().get(17));
        }
    }
}
