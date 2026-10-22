package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import java.util.List;
import java.util.ArrayList;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import static org.junit.jupiter.api.Assertions.*;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.world.piston.PistonProperties;
import ru.minecomplex.mac.world.piston.PistonPush;
import ru.minecomplex.mac.world.shulker.ShulkerAnimation;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ShulkerTimelineTest {
    static final BlockPosition POS = new BlockPosition(0, 64, 0);
    static WorldModel model() {
        var model = new WorldModel(BlockRegistry.get());
        model.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 128, 2)));
        model.sent(1, Wire.of(PistonTimelineTest.chunk()));
        model.sent(2, Wire.of(new BlockUpdate(0, 64, 0, PistonProperties.get().defaultState("minecraft:shulker_box"))));
        model.sent(3, Wire.of(new BlockEvent(0, 64, 0, 1, 1, 0)));
        return model;
    }
    static void tick(CompensatedWorld world, long time) { world.tickBlockEntities(time, null, null, area -> List.of()); }

    @Test
    void mixedTickersKeepCreationOrderAcrossForks() {
        var props = PistonProperties.get();
        for (boolean shulkerFirst : new boolean[]{true, false}) {
            var model = PistonTimelineTest.beforeEvent();
            var pistonEvent = new BlockEvent(0, 64, 0, 0, 5, 0);
            var shulkerBlock = new BlockUpdate(0, 64, 3, props.defaultState("minecraft:shulker_box"));
            model.sent(4, shulkerFirst ? Wire.<Object>of(shulkerBlock) : Wire.<Object>of(pistonEvent));
            model.sent(5, shulkerFirst ? Wire.<Object>of(pistonEvent) : Wire.<Object>of(shulkerBlock));
            model.sent(6, Wire.of(new BlockEvent(0, 64, 3, 1, 1, 0)));
            try (var hypothesis = model.hypothesis(new SyncWindow(1, 7), 6); var fork = hypothesis.fork()) {
                for (var world : List.of(hypothesis.world(), fork.world())) {
                    var order = new ArrayList<String>();
                    var push = new PistonPush(model.registry(),
                            BlockCollisions.emptyContext(model.registry()));
                    world.tickBlockEntities(20, push,
                            area -> { order.add("piston"); return List.of(); },
                            area -> { order.add("shulker"); return List.of(); });
                    assertTrue(order.contains("piston")); assertTrue(order.contains("shulker"));
                    assertEquals(shulkerFirst ? "shulker" : "piston", order.getFirst());
                    assertEquals(shulkerFirst ? "piston" : "shulker", order.getLast());
                }
            }
        }
    }

    @Test
    void latePongAndForkPreserveIndependentLidHistories() {
        var model = model(); var window = new SyncWindow(1, 4);
        try (var h = model.hypothesis(window, 3); var fork = h.fork()) {
            tick(h.world(), 20); tick(h.world(), 21);
            assertEquals(0.2F, h.world().shulker(POS, false).progress());
            assertNotNull(fork.world().shulker(POS, false));
            assertEquals(0.0F, fork.world().shulker(POS, false).progress());
            model.settle(new SyncWindow(3, 4)); h.apply(new SyncWindow(3, 4), 3);
            assertEquals(0.2F, h.world().shulker(POS, false).progress());
            assertFalse(h.world().dynamicGaps().contains(Unmodeled.SHULKER_TICK_TIMING));
            assertTrue(model.acknowledged(new SyncWindow(3, 4)).dynamicGaps().contains(Unmodeled.SHULKER_TICK_TIMING));
        }
    }

    @Test
    void facingAndTypedDataPreserveLidButBlockReplacementResetsIt() {
        var model = model(); var props = PistonProperties.get();
        try (var h = model.hypothesis(new SyncWindow(1, 4), 3)) {
            var world = h.world(); tick(world, 20); var lid = world.shulker(POS, false);
            world.setBlock(0, 64, 0, props.with(props.defaultState("minecraft:shulker_box"), "facing", "east"));
            // block entity data finds the existing block entity: the lid survives
            world.shulker(POS, true);
            assertEquals(lid, world.shulker(POS, false));
            world.setBlock(0, 64, 0, props.defaultState("minecraft:red_shulker_box"));
            assertEquals(ShulkerAnimation.closed(), world.shulker(POS, false));
        }
    }

    @Test
    void cacheCenterHidesReadsButDoesNotSuspendBoundTickers() {
        var model = model();
        try (var h = model.hypothesis(new SyncWindow(1, 4), 3)) {
            var world = h.world(); world.setCenter(100, 100); tick(world, 20);
            assertEquals(CompensatedWorld.UNLOADED, world.getBlock(0, 64, 0));
            world.setCenter(0, 0); assertEquals(0.1F, world.shulker(POS, false).progress());
            world.forget(0, 0); assertNull(world.shulker(POS, false));
            model.sent(4, Wire.of(PistonTimelineTest.chunk())); h.apply(new SyncWindow(1, 5), 4);
            assertNull(world.shulker(POS, false)); tick(world, 21);
        }
    }

    @Test
    void borderGatesTickingAndOpeningAloneQueriesEntities() {
        var model = model();
        try (var h = model.hypothesis(new SyncWindow(1, 4), 3)) {
            var world = h.world(); var calls = new ArrayList<Object>();
            world.border(Wire.of(new WorldBorder(WorldBorder.Operation.CENTER, 100, 100, 0, 0, 0, 0)));
            world.border(Wire.of(new WorldBorder(WorldBorder.Operation.SIZE, 0, 0, 0, 2, 0, 0)));
            world.tickBlockEntities(20, null, null, area -> { calls.add(area); return List.of(); });
            assertEquals(0.0F, world.shulker(POS, false).progress()); assertTrue(calls.isEmpty());
            world.border(Wire.of(new WorldBorder(WorldBorder.Operation.CENTER, 0, 0, 0, 0, 0, 0)));
            world.tickBlockEntities(21, null, null, area -> { calls.add(area); return List.of(); });
            assertEquals(1, calls.size()); assertEquals(0.1F, world.shulker(POS, false).progress());
            world.shulkerEvent(new BlockEvent(0, 64, 0, 1, 0, 0)); world.tickBlockEntities(22, null, null, null);
            assertEquals(0.0F, world.shulker(POS, false).progress());
            assertFalse(world.dynamicGaps().contains(Unmodeled.SHULKER_ENTITIES));
        }
    }

    @Test
    void unknownOpeningQueriesRemainUncertainAfterUnload() {
        var model = model();
        try (var h = model.hypothesis(new SyncWindow(1, 4), 3)) {
            h.world().tickBlockEntities(20, null, null, null); h.world().forget(0, 0);
            assertTrue(h.world().dynamicGaps().contains(Unmodeled.SHULKER_ENTITIES));
        }
    }
}
