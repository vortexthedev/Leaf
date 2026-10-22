package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.EnumSet;

import ru.minecomplex.mac.fixture.inbound.VehicleMovement;

/** The rest certificate must not confuse two stationary packet targets with a stationary physical body. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class VehicleRestEvidenceTest {
    @Test
    void anUnknownResumeCannotKeepThePreviousRidesInterpolationTarget() throws Exception {
        var probe = new VehicleCheckRunner();
        var body = new ru.minecomplex.mac.player.state.PlayerState();
        body.interpolation = new ru.minecomplex.mac.entity.interpolation.EntityInterpolation(true, 3);
        body.interpolation.interpolateTo(body, new ru.minecomplex.mac.util.math.Vec3d(100, 16, 100), null, 90F, 0F);
        var entities = new ru.minecomplex.mac.replica.CompensatedEntities(1);
        var seed = VehicleCheckRunner.class.getDeclaredMethod("seedTransform", ru.minecomplex.mac.replica.CompensatedEntities.class,
                int.class, ru.minecomplex.mac.player.state.PlayerState.class, net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket.class);
        seed.setAccessible(true);
        seed.invoke(probe, entities, 2, body, ru.minecomplex.mac.Wire.of(new VehicleMovement(12.5, 16, 8.5, 0F, 0F, true)));
        assertNull(body.interpolation);
        assertEquals(new ru.minecomplex.mac.util.math.Vec3d(12.5, 16, 8.5), body.position);
    }

    @Test
    void bothEndpointsMustReportPhysicalPositionsBeforeUnknownVelocityCanResolve() throws Exception {
        var probe = new VehicleCheckRunner();
        var unknown = VehicleCheckRunner.class.getDeclaredField("seedVelocityUnknown");
        unknown.setAccessible(true);
        unknown.setBoolean(probe, true);
        var evidence = VehicleCheckRunner.class.getDeclaredMethod("startUncertainty", java.util.Set.class, java.util.List.class,
                net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket.class, net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket.class, boolean.class);
        evidence.setAccessible(true);
        var target = ru.minecomplex.mac.Wire.of(new VehicleMovement(12.5, 16.0, 8.5, 0F, 0F, true));
        // Check both orders: physical -> target must not resolve, nor may target -> physical.
        for (boolean physical : new boolean[]{true, false, false, true}) {
            var gaps = EnumSet.noneOf(Unmodeled.class);
            evidence.invoke(probe, gaps, new ArrayList<String>(), target, target, physical);
            assertTrue(gaps.contains(Unmodeled.RIDING));
            assertTrue(unknown.getBoolean(probe));
        }
        var gaps = EnumSet.noneOf(Unmodeled.class);
        evidence.invoke(probe, gaps, new ArrayList<String>(), target, target, true);
        assertFalse(gaps.contains(Unmodeled.RIDING));
        assertFalse(unknown.getBoolean(probe));
    }
}
