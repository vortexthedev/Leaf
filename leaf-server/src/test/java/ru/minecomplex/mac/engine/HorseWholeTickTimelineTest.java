package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.engine.movementtick.HorseOracle;
import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.replica.CompensatedEntities;
import ru.minecomplex.mac.world.BlockRegistry;

import ru.minecomplex.mac.engine.movementtick.HorsePlayerTick;

import ru.minecomplex.mac.engine.movementtick.LocalPlayerFluidOracleTest;
import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/** A complete root tick certifies only its resolved phases; graph changes cannot advance stale solvers. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class HorseWholeTickTimelineTest {
    @Test
    void phaseOrderingCopiesAndUncertainty() {
        var entities = new CompensatedEntities(1);
        entities.spawn(Wire.of(HorseRideTimelineTest.spawn()), chunk -> false);
        entities.passengers().startRiding(1,2,true);
        var p = new PlayerState(); p.position = new Vec3d(0.5,64,0.5);
        p.fixedBodyDimensions = true; p.bodyWidth = 1.3964844F; p.bodyHeight = 1.6F;
        p.fluidShapes = FluidShapeCache.empty();
        var state = new HorseState(entities.passengers().get(2).generation(),HorseState.Kind.HORSE,p);
        entities.horseState(2,state);
        var env = LocalPlayerFluidOracleTest.environment(BlockRegistry.get(),List.of());
        var body = HorseOracle.body(env,state,1.52F,true,true,true,false,
                new HorseOracle.Dimensions(p.bodyWidth,p.bodyHeight,1.52F));
        var ride = HorseRideTimelineTest.ride(entities,state);
        var own = new PlayerState(); own.position = p.position; own.fluidShapes = FluidShapeCache.empty();
        var local = new LocalPlayerState();
        var tick = new HorsePlayerTick(env,body,ride,own,local, FixturePlayers.loaded());
        var control = new HorseOracle.Control(state.kind,true,true,
                true,
                false,true,100,0.225,0.7,1,1,0);
        control.apply(state);
        assertThrows(IllegalStateException.class,() -> tick.simulatePassengerTick(PlayerInput.EMPTY));
        assertEquals(Set.of(),HorseOracle.simulate(tick,state,control,75,true,false,false));
        assertTrue(state.completeTick); assertEquals(Set.of(),ride.unmodeled());
        assertThrows(IllegalStateException.class,() -> HorseOracle.simulate(tick,state,control,75,true,false,false));
        tick.simulatePassengerTick(PlayerInput.EMPTY);
        state.bodyRotation.headStableTime = 17; state.bodyRotation.lastStableHeadYaw = 31;
        var copy = entities.copy().horseState(2);
        assertTrue(copy.completeTick); assertEquals(state.tickGaps,copy.tickGaps);
        assertNotSame(state.bodyRotation,copy.bodyRotation);
        assertEquals(17,copy.bodyRotation.headStableTime); assertEquals(31,copy.bodyRotation.lastStableHeadYaw);
        copy.bodyRotation.headStableTime = 0; assertEquals(17,state.bodyRotation.headStableTime);
        assertEquals(Set.of(Unmodeled.VEHICLE_ENTITIES),HorseOracle.simulate(tick,state,control,75,false,false,false));
        assertEquals(Set.of(Unmodeled.VEHICLE_ENTITIES),ride.unmodeled());
        assertEquals(Set.of(Unmodeled.VEHICLE_ENTITIES),state.copy().tickGaps);
        tick.simulatePassengerTick(PlayerInput.EMPTY);
        assertEquals(Set.of(Unmodeled.VEHICLE_ENTITIES),HorseOracle.simulate(tick,state,control,75,true,false,false));
        assertTrue(ride.unmodeled().contains(Unmodeled.VEHICLE_ENTITIES));
        tick.simulatePassengerTick(PlayerInput.EMPTY);
        assertTrue(HorseOracle.simulate(tick,state,control,75,true,true,false).contains(Unmodeled.RIDING));
        assertTrue(ride.unmodeled().contains(Unmodeled.RIDING));
        tick.simulatePassengerTick(PlayerInput.EMPTY);
        // A selected boundary cannot retain a previous whole-tick certificate.
        state.tickGaps = Set.of(); state.completeTick = true;
        ride.advanceRiddenMovement(new HorseOracle.Rider(0,0,0,0,false).player(),false);
        assertFalse(state.completeTick); assertTrue(ride.unmodeled().contains(Unmodeled.RIDING));
        state.completeTick = true;
        ride.advanceTickTail(100);
        assertFalse(state.completeTick); assertTrue(ride.unmodeled().contains(Unmodeled.RIDING));
        var oldPosition = p.position; var oldMovement = p.deltaMovement;
        entities.spawn(Wire.of(HorseRideTimelineTest.spawn()), chunk -> false); entities.passengers().startRiding(1,2,true);
        assertEquals(Set.of(Unmodeled.RIDING),assertDoesNotThrow(() -> HorseOracle.simulate(tick,state,control,75,true,false,false)));
        var stopped = assertDoesNotThrow(() -> tick.simulatePassengerTick(PlayerInput.EMPTY));
        assertEquals(Set.of(Unmodeled.RIDING),stopped.unmodeled()); assertEquals(List.of(),stopped.packets());
        assertEquals(oldPosition,p.position); assertEquals(oldMovement,p.deltaMovement); assertNull(local.rootVehicle);
    }
}
