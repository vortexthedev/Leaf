package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntityRemove;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.engine.movementtick.BoatPlayerTick;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerFluidOracleTest;

import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/** Timeline invariants: a processed passenger packet is not an entity tick or a simulated-body reset. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BoatRideTimelineTest {
    static EntitySpawn spawn() { return new EntitySpawn(2,"minecraft:oak_boat",0.5,64,0.5,0,0,0,0,false,0,0,0); }
    @Test
    void prefixesForksDismountAndIdReuseRetainIndependentClocks() {
        var registry = BlockRegistry.get();
        var model = new WorldModel(registry);
        model.sent(0,Wire.of(new JoinGame(1,"minecraft:overworld",-64,384,8))); model.sent(1,Wire.of(spawn()));
        model.sent(2,Wire.of(new EntityPassengers(2,new int[]{1})));
        var window = new SyncWindow(1,3);
        try (var before = model.hypothesis(window,1); var mounted = model.hypothesis(window,2)) {
            assertNull(before.world().entities().passengers().get(1).vehicle());
            var entities = mounted.world().entities(); assertEquals(2,entities.passengers().get(1).vehicle());
            var body = new BoatState(entities.passengers().get(2).generation(),1.375F,0.5625F,0.5625F);
            body.body.position = new Vec3d(0.5,64,0.5); body.body.fluidShapes = FluidShapeCache.empty();
            entities.boatState(2,body);
            var player = new PlayerState(); player.position = new Vec3d(0.5,64.25,0.5); player.fluidShapes = FluidShapeCache.empty();
            var local = new LocalPlayerState();
            var simulation = new BoatPlayerTick(LocalPlayerFluidOracleTest.environment(registry,List.of()),entities,2,
                    player,local, FixturePlayers.loaded(),false,false,p -> Vec3d.ZERO,box -> List.of());
            var result = simulation.tick(PlayerInput.fromMask(5), Tri.TRUE,true,true);
            assertEquals(Set.of(),result.unmodeled()); assertEquals(1,body.tickCount);
            assertTrue(body.input.forward()); assertTrue(body.input.left()); assertTrue(local.handsBusy);
            // Independent copy invariants must cover nonzero hidden look fields too.
            local.yawOld = 31.0F; local.headYaw = -53.0F; local.bodyYaw = 57.0F;
            var hiddenFork = local.copy(); assertEquals(local.handsBusy,hiddenFork.handsBusy);
            assertEquals(local.yawOld,hiddenFork.yawOld); assertEquals(local.headYaw,hiddenFork.headYaw); assertEquals(local.bodyYaw,hiddenFork.bodyYaw);
            try (var fork = mounted.fork()) {
                var forkBody = fork.world().entities().boatState(2); assertNotSame(body,forkBody); assertNotSame(body.body,forkBody.body);
                assertSame(body.generation,forkBody.generation); assertEquals(body.body.position,forkBody.body.position);
                assertEquals(body.input,forkBody.input); assertEquals(body.tickCount,forkBody.tickCount);
                var replaced = entities.copy(); replaced.spawn(Wire.of(spawn()), chunk -> false);
                assertNull(replaced.boatState(2)); assertNotSame(body.generation,replaced.passengers().get(2).generation());
                model.settle(new SyncWindow(2,3)); mounted.apply(new SyncWindow(2,3),2); fork.apply(new SyncWindow(2,3),2);
                assertSame(body,mounted.world().entities().boatState(2)); assertEquals(1,body.tickCount);
                assertEquals(1,forkBody.tickCount); assertNull(before.world().entities().boatState(2));
                model.sent(3,Wire.of(new EntityPassengers(2,new int[0])));
                mounted.apply(new SyncWindow(2,4),3);
                assertNull(mounted.world().entities().passengers().get(1).vehicle());
                assertEquals(2,fork.world().entities().passengers().get(1).vehicle());
                var stopped = assertDoesNotThrow(() -> simulation.tick(PlayerInput.EMPTY, Tri.TRUE,true,true));
                assertEquals(Set.of(Unmodeled.RIDING),stopped.unmodeled()); assertEquals(List.of(),stopped.packets());
                assertEquals(1,body.tickCount); assertNull(local.rootVehicle);
                model.sent(4,Wire.of(new EntityRemove(new int[]{2}))); model.sent(5,Wire.of(spawn()));
                mounted.apply(new SyncWindow(2,6),5);
                assertNull(mounted.world().entities().boatState(2));
                assertNotSame(body.generation,mounted.world().entities().passengers().get(2).generation());
                assertThrows(IllegalArgumentException.class,() -> mounted.world().entities().boatState(2,body));
                assertSame(forkBody,fork.world().entities().boatState(2));
                // Even mounting the reused id cannot make an old solver advance the replacement generation.
                model.sent(6,Wire.of(new EntityPassengers(2,new int[]{1}))); mounted.apply(new SyncWindow(2,7),6);
                assertEquals(Set.of(Unmodeled.RIDING),assertDoesNotThrow(() -> simulation.tick(PlayerInput.EMPTY, Tri.TRUE,true,true)).unmodeled());
                assertEquals(1,body.tickCount);
                forkBody.input = PlayerInput.EMPTY; forkBody.body.position = Vec3d.ZERO;
                assertTrue(body.input.forward()); assertNotEquals(body.body.position,forkBody.body.position);
                var forkLocal = local.copy();
                var forkSimulation = new BoatPlayerTick(LocalPlayerFluidOracleTest.environment(registry,List.of()),
                        fork.world().entities(),2,player.copy(),forkLocal, FixturePlayers.loaded(),false,false,p -> Vec3d.ZERO,box -> List.of());
                var oldPlayerGeneration = fork.world().entities().passengers().get(1).generation();
                fork.world().entities().respawnLocalPlayer(); fork.world().entities().passengers().startRiding(1,2,true);
                assertNotSame(oldPlayerGeneration,fork.world().entities().passengers().get(1).generation());
                assertEquals(Set.of(Unmodeled.RIDING),assertDoesNotThrow(() -> forkSimulation.tick(PlayerInput.EMPTY,Tri.TRUE,true,true)).unmodeled());
                assertEquals(1,forkBody.tickCount);
            }
        }
    }
}
