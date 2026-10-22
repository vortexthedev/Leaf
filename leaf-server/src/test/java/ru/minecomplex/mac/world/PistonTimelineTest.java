package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.*;

import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.world.piston.PistonProperties;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PistonTimelineTest {

    static final BlockPosition BASE = new BlockPosition(0, 64, 0), HEAD = new BlockPosition(1, 64, 0), MOVED = new BlockPosition(2, 64, 0);

    static ChunkData chunk() {
        return new ChunkData(0, 0, new int[8], new int[] { 1, 1, 1, 1, 1, 1, 1, 1 }, new int[8], new long[0]);
    }

    static WorldModel setup() {
        var model = beforeEvent();
        var registry = model.registry();
        model.sent(4, Wire.of(new BlockEvent(0, 64, 0, 0, 5, registry.block("minecraft:dirt").registryId())));
        return model;
    }

    static WorldModel beforeEvent() {
        var registry = BlockRegistry.get();
        var model = new WorldModel(registry);
        var props = PistonProperties.get();
        model.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 128, 2)));
        model.sent(1, Wire.of(chunk()));
        model.sent(2, Wire.of(new BlockUpdate(0, 64, 0, props.with(props.defaultState("minecraft:piston"), "facing", "east"))));
        model.sent(3, Wire.of(new BlockUpdate(1, 64, 0, props.defaultState("minecraft:stone"))));
        return model;
    }

    @Test
    void lateAcknowledgementCannotResetOrReplayLocalTicks() {
        var model = setup();
        var window = new SyncWindow(1, 5);
        try (var h = model.hypothesis(window, 4);
            var fork = h.fork()) {
            assertFalse(h.world().dynamicGaps().contains(Unmodeled.PISTON_TICK_TIMING));
            assertEquals(0.0F, h.world().piston(MOVED).progress());
            h.world().tickPistons(20, (p, m) -> {
            });
            assertEquals(0.5F, h.world().piston(MOVED).progress());
            assertEquals(0.0F, fork.world().piston(MOVED).progress());
            model.settle(new SyncWindow(4, 5));
            h.apply(new SyncWindow(4, 5), 4);
            assertEquals(0.5F, h.world().piston(MOVED).progress());
            assertEquals(0.0F, model.acknowledged(new SyncWindow(4, 5)).piston(MOVED).progress());
            assertTrue(model.acknowledged(new SyncWindow(4, 5)).dynamicGaps().contains(Unmodeled.PISTON_TICK_TIMING));
            for (int t = 21; t <= 27; t++) h.world().tickPistons(t, (p, m) -> {
            });
            assertNull(h.world().piston(MOVED));
            assertEquals(PistonProperties.get().defaultState("minecraft:stone"), h.world().getBlock(2, 64, 0));
        }
    }

    @Test
    void newlyCertainInputsStayAvailableToExistingHypotheses() {
        var model = setup();
        try (var h = model.hypothesis(new SyncWindow(1, 5), 3)) {
            model.settle(new SyncWindow(4, 5));
            h.apply(new SyncWindow(4, 5), 4);
            assertNotNull(h.world().piston(MOVED));
            model.sent(5, Wire.of(new BlockUpdate(2, 64, 0, 0)));
            model.settle(new SyncWindow(5, 6));
            h.apply(new SyncWindow(5, 6), 5);
            assertNull(h.world().piston(MOVED));
        }
    }

    @Test
    void unloadAndReplacementRetireTickersButCenterHidingDoesNot() {
        var model = setup();
        try (var h = model.hypothesis(new SyncWindow(1, 5), 4)) {
            var world = h.world();
            world.setCenter(100, 100);
            assertNull(world.piston(MOVED));
            world.tickPistons(20, (p, m) -> {
            });
            world.setCenter(0, 0);
            assertEquals(0.5F, world.piston(MOVED).progress());
            world.forget(0, 0);
            assertNull(world.piston(MOVED));
            model.sent(5, Wire.of(chunk()));
            h.apply(new SyncWindow(1, 6), 5);
            assertNull(world.piston(MOVED));
            world.tickPistons(21, (p, m) -> {
            });
            assertEquals(0, world.getBlock(2, 64, 0));
        }
    }

    @Test
    void pistonEventSeesTheBorderAtItsOwnPrefix() {
        var model = setup();
        try (var h = model.hypothesis(new SyncWindow(1, 5), 3);
            var fork = h.fork()) {
            h.world().border(Wire.of(new WorldBorder(WorldBorder.Operation.SIZE, 0, 0, 0, 2, 0, 0)));
            h.apply(new SyncWindow(1, 5), 4);
            fork.apply(new SyncWindow(1, 5), 4);
            assertNull(h.world().piston(MOVED));
            assertNotNull(fork.world().piston(MOVED));
        }
    }

    @Test
    void missingEntityQueriesRemainUncertainAfterUnload() {
        var model = setup();
        try (var h = model.hypothesis(new SyncWindow(1, 5), 4)) {
            h.world().tickPistons(20, null);
            assertTrue(h.world().dynamicGaps().contains(Unmodeled.PISTON_ENTITIES));
            h.world().forget(0, 0);
            assertTrue(h.world().dynamicGaps().contains(Unmodeled.PISTON_ENTITIES));
        }
    }

    @Test
    void typedUpdatesReportInvalidRootsAndApplyEmptyCompoundsByCapability() {
        var model = setup();
        try (var h = model.hypothesis(new SyncWindow(1, 5), 4)) {
            var world = h.world();
            world.tickPistons(20, (p, m) -> {
            });
            var before = world.piston(MOVED);
            world.pistonData(new BlockEntityData(2, 64, 0, "minecraft:shulker_box", new byte[] { 10, 0 }));
            assertEquals(before, world.piston(MOVED));
            world.pistonData(new BlockEntityData(2, 64, 0, "minecraft:piston", new byte[] { 0 }));
            assertEquals(before, world.piston(MOVED));
            assertTrue(world.dynamicGaps().contains(Unmodeled.PISTON_BLOCK_ENTITY));
            world.pistonData(new BlockEntityData(2, 64, 0, "minecraft:piston", new byte[] { 10, 0 }));
            {
                assertEquals(0, world.piston(MOVED).movedState());
                assertEquals(0.0F, world.piston(MOVED).progress());
                assertEquals(before.lastTicked(), world.piston(MOVED).lastTicked());
            }
        }
    }

    @Test
    void borderPacketsAndPistonEventsShareWireOrder() {
        var model = beforeEvent();
        model.sent(4, Wire.of(new WorldBorder(WorldBorder.Operation.INITIALIZE, 0, 0, 2, 2, 0, 29999984)));
        model.sent(5, Wire.of(new BlockEvent(0, 64, 0, 0, 5, 0)));
        assertTrue(WorldModel.isWorldChange(Wire.of(new WorldBorder(WorldBorder.Operation.CENTER, 0, 0, 0, 0, 0, 0))));
        try (var h = model.hypothesis(new SyncWindow(1, 6), 3)) {
            assertArrayEquals(new long[] { 1, 2, 3, 4, 5 }, model.prefixEnds(new SyncWindow(1, 6)));
            h.apply(new SyncWindow(1, 6), 5);
            assertNull(h.world().piston(MOVED));
        }
        model.sent(6, Wire.of(new WorldBorder(WorldBorder.Operation.SIZE, 0, 0, 0, 10, 0, 0)));
        model.sent(7, Wire.of(new BlockEvent(0, 64, 0, 0, 5, 0)));
        try (var h = model.hypothesis(new SyncWindow(1, 8), 7)) {
            assertNotNull(h.world().piston(MOVED));
        }
    }

    @Test
    void interpolatingBorderNeedsClientTickHistory() {
        var model = beforeEvent();
        model.sent(4, Wire.of(new WorldBorder(WorldBorder.Operation.LERP, 0, 0, 2, 10, 1000, 0)));
        model.sent(5, Wire.of(new BlockEvent(0, 64, 0, 0, 5, 0)));
        var window = new SyncWindow(1, 6);
        try (var h = model.hypothesis(window, 3)) {
            h.apply(window, 4);
            {
                h.world().tickBorder();
                h.world().tickBorder();
            }
            h.apply(window, 5);
            assertNotNull(h.world().piston(MOVED));
            assertFalse(h.world().dynamicGaps().contains(Unmodeled.WORLD_BORDER_CLOCK));
        }
        // An acknowledged handler baseline has lost all intervening border-clock/tick phases.
        try (var late = model.hypothesis(new SyncWindow(4, 6), 5)) {
            assertTrue(late.world().dynamicGaps().contains(Unmodeled.WORLD_BORDER_CLOCK));
        }
    }
}
