package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.engine.movementtick.HorseOracle;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.List;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.engine.movementtick.HorsePassengerPhysics;
import ru.minecomplex.mac.engine.movementtick.HorsePlayerRide;

import ru.minecomplex.mac.engine.movementtick.LocalPlayerFluidOracleTest;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.engine.movementtick.VehiclePacketState;
import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Marker promotion preserves simulated clocks; forks and reused entity ids cannot share them.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class HorseRideTimelineTest {

    static EntitySpawn spawn() {
        return new EntitySpawn(2, "minecraft:horse", 0.5, 64, 0.5, 0, 0, 0, 0, false, 0, 0, 0);
    }

    static HorsePlayerRide ride(ru.minecomplex.mac.replica.CompensatedEntities entities, HorseState s) {
        return new HorsePlayerRide(entities.passengers(), 1, 2, ticker(s), () -> new HorsePassengerPhysics.Facts(s.kind, Vec3d.ZERO, 0, 0, 1.6F, 1, false, false, 0, 1, false, 100, 0, 0, Vec3d.ZERO), () -> new VehiclePacketState(s.generation, Tri.TRUE, s.body.position, s.body.yRot, s.body.xRot, null, false, true), p -> Vec3d.ZERO);
    }

    static ru.minecomplex.mac.engine.movementtick.MovementTickerHorse ticker(HorseState state) {
        state.body.fixedBodyDimensions=true;
        return HorseOracle.body(LocalPlayerFluidOracleTest.environment(BlockRegistry.get(),List.of()),state,1.52F,true,true,true,false,new HorseOracle.Dimensions(1.3964844F,1.6F,1.52F));
    }

    @Test
    void prefixesForksChargeDismountAndIdentityReuse() {
        var model = new WorldModel(BlockRegistry.get());
        model.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", -64, 384, 8)));
        model.sent(1, Wire.of(spawn()));
        model.sent(2, Wire.of(new EntityPassengers(2, new int[] { 1 })));
        try (var before = model.hypothesis(new SyncWindow(1, 3), 1);
            var mounted = model.hypothesis(new SyncWindow(1, 3), 2)) {
            var entities = mounted.world().entities();
            var body = new PlayerState();
            body.position = new Vec3d(0.5, 64, 0.5);
            var state = new HorseState(entities.passengers().get(2).generation(), HorseState.Kind.HORSE, body);
            entities.horseState(2, state);
            var context = ride(entities, state);
            assertTrue(context.onPlayerJump(90));
            assertEquals(1.0F, state.pendingJump);
            state.lastPoseChangeTick = 37;
            state.oldStandAnimation = 0.3F;
            state.standAnimation = 0.5F;
            state.bodyYaw = 73;
            var player = new PlayerState();
            var local = new LocalPlayerState();
            context.positionRider(player, local);
            assertEquals(73, local.bodyYaw);
            assertNotEquals(body.position, player.position);
            assertTrue(context.nonBoatVehicleResolved());
            assertEquals(Set.of(Unmodeled.RIDING), context.unmodeled());
            {
                local.rootVehicle = context.rootVehicle();
                player.passenger = true;
                body.position = body.position.add(2.0, 0.0, 0.0);
                body.yRot = 41;
                var physics = new MovementTickerPlayer(LocalPlayerFluidOracleTest.environment(BlockRegistry.get(), List.of()), player, local, FixturePlayers.loaded());
                var late = physics.sendAfterEntityTicks(context);
                assertTrue(Wire.in(physics.packets()).contains(Wire.in(context.rootVehicle().packet())));
                assertEquals(Set.of(Unmodeled.RIDING), late);
            }
            try (var fork = mounted.fork()) {
                var other = fork.world().entities().horseState(2);
                assertNotSame(state, other);
                assertNotSame(state.body, other.body);

                assertNotSame(state, other);
                assertSame(state.generation, other.generation);
                assertEquals(1.0F, other.pendingJump);
                assertEquals(37, other.lastPoseChangeTick);
                assertEquals(0.3F, other.oldStandAnimation);
                assertEquals(0.5F, other.standAnimation);
                assertEquals(73, other.bodyYaw);
                assertEquals(body.position, other.body.position);
                model.settle(new SyncWindow(2, 3));
                mounted.apply(new SyncWindow(2, 3), 2);
                fork.apply(new SyncWindow(2, 3), 2);
                assertSame(state, mounted.world().entities().horseState(2));
                assertSame(other, fork.world().entities().horseState(2));
                assertNull(before.world().entities().horseState(2));
                assertEquals(1.0F, state.pendingJump);
                other.pendingJump = 0;
                other.oldStandAnimation = 0;
                other.body.position = Vec3d.ZERO;
                assertEquals(1.0F, state.pendingJump);
                assertEquals(0.3F, state.oldStandAnimation);
                assertNotEquals(state.body.position, other.body.position);
                model.sent(3, Wire.of(new EntityPassengers(2, new int[0])));
                mounted.apply(new SyncWindow(2, 4), 3);
                assertFalse(assertDoesNotThrow(() -> context.onPlayerJump(0)));
                assertNull(context.rootVehicle());
                assertFalse(context.nonBoatVehicleResolved());
                model.sent(4, Wire.of(new EntityRemove(new int[] { 2 })));
                mounted.apply(new SyncWindow(2, 5), 4);
                assertNull(mounted.world().entities().horseState(2));
                model.sent(5, Wire.of(spawn()));
                mounted.apply(new SyncWindow(2, 6), 5);
                assertThrows(IllegalArgumentException.class, () -> mounted.world().entities().horseState(2, state));
                model.sent(6, Wire.of(new EntityPassengers(2, new int[] { 1 })));
                mounted.apply(new SyncWindow(2, 7), 6);
                assertFalse(assertDoesNotThrow(() -> context.onPlayerJump(0)));
                assertNull(context.rootVehicle());
                assertEquals(1.0F, state.pendingJump);
                var forkContext = ride(fork.world().entities(), other);
                fork.world().entities().respawnLocalPlayer();
                fork.world().entities().passengers().startRiding(1, 2, true);
                assertFalse(assertDoesNotThrow(() -> forkContext.onPlayerJump(90)));
                assertNull(forkContext.rootVehicle());
                assertEquals(0.0F, other.pendingJump);
                // Spawn without an intervening removal must clear a previous body too.
                var replacement = fork.world().entities().copy();
                assertNotNull(replacement.horseState(2));
                replacement.spawn(Wire.of(spawn()), chunk -> false);
                assertNull(replacement.horseState(2));
            }
        }
    }
}
