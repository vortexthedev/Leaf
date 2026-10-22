package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.entity.effect.MobEffect;
import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.packet.NativeRuntime;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec2f;
import ru.minecomplex.mac.world.BlockRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.item.ItemStack;

/**
 * LocalPlayer's sprint predicates ({@code canStartSprinting}, {@code isSprintingPossible}, {@code shouldStopRunSprinting},
 * {@code shouldStopSwimSprinting}) against vanilla 26.3 over every combination of what they read: each sample sets the
 * player's fields, its item in use and its vehicle the way the client holds them.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LocalPlayerSprintOracleTest {
    /** A vehicle the player may sprint on ({@code vehicleCanSprint}). */
    private static final LocalPlayerRide SPRINTING_VEHICLE = new LocalPlayerRide() {
        public FluidWorld.BoatPassenger boatFluidFacts() { return null; }
        public VehiclePacketState rootVehicle() { return null; }
        public Set<Unmodeled> unmodeled() { return Set.of(); }
        public void positionRider(PlayerState player, LocalPlayerState local) { }
        public boolean controlsBoat() { return false; }
        public void setBoatInput(PlayerInput input) { }
        public boolean vehicleCanSprint() { return true; }
    };

    @Test
    void directPredicatesMatchVanilla() throws Exception {
        String fixture = "local-sprint";
        NativeRuntime.ensure();
        var player = new PlayerState();
        var local = new LocalPlayerState();
        var data = FixturePlayers.loaded();
        var sprint = new MovementTickerPlayer(LocalPlayerFluidOracleTest.environment(BlockRegistry.get(), List.of()),
                player, local, data);
        ItemStack sprintingItem = usedItem(true), slowingItem = usedItem(false);
        var resource = getClass().getResourceAsStream("/vanilla/777/" + fixture + ".bin.gz");
        assertNotNull(resource);
        int starts = 0, stopsRun = 0, stopsSwim = 0;
        try (var in = new DataInputStream(new GZIPInputStream(resource))) {
            assertEquals(0x4d414353, in.readInt()); assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            boolean hasPossible = in.readBoolean();
            assertEquals(true, hasPossible);
            boolean hasStops = in.readBoolean();
            assertEquals(true, hasStops);
            assertEquals(49152, in.readInt());
            for (int sample = 0; sample < 49152; sample++) {
                int flags = in.readInt(); float forward = in.readFloat(); int food = in.readInt();
                player.sprinting = bit(flags, 0);
                local.vector = new Vec2f(0.0F, forward);
                data.food = food;
                player.abilities.mayfly = bit(flags, 11);
                player.abilities.flying = bit(flags, 10);
                player.passenger = bit(flags, 1);
                sprint.ride = bit(flags, 2) && bit(flags, 17) ? SPRINTING_VEHICLE : null;
                if (bit(flags, 3)) player.effects.set(MobEffect.BLINDNESS, 0);
                else player.effects.remove(MobEffect.BLINDNESS);
                data.usingItem = bit(flags, 4);
                data.useItem = bit(flags, 5) ? sprintingItem : slowingItem;
                player.fallFlying = bit(flags, 6);
                local.crouching = bit(flags, 8);
                player.pose = Pose.STANDING;
                player.wasTouchingWater = bit(flags, 9);
                player.wasUnderwater = bit(flags, 7);
                player.onGround = bit(flags, 14);
                local.input = bit(flags, 13) ? PlayerInput.fromMask(32) : PlayerInput.EMPTY;
                player.horizontalCollision = bit(flags, 15);
                player.minorHorizontalCollision = bit(flags, 16);
                String context = fixture + " case " + sample + " flags " + flags + " impulse " + forward;
                boolean start = in.readBoolean();
                assertEquals(start, sprint.canStartSprinting(), context);
                if (start) starts++;
                if (hasPossible) {
                    assertEquals(in.readBoolean(), sprint.isSprintingPossible(false), context + " shallow false");
                    assertEquals(in.readBoolean(), sprint.isSprintingPossible(true), context + " shallow true");
                } else if (sample == 0) {
                    assertThrows(UnsupportedOperationException.class, () -> sprint.isSprintingPossible(true));
                }
                if (hasStops) {
                    boolean run = in.readBoolean(), swim = in.readBoolean();
                    assertEquals(run, sprint.shouldStopRunSprinting(), context + " stop run");
                    assertEquals(swim, sprint.shouldStopSwimSprinting(), context + " stop swim");
                    if (run) stopsRun++;
                    if (swim) stopsSwim++;
                } else if (sample == 0) {
                    assertThrows(UnsupportedOperationException.class, sprint::shouldStopRunSprinting);
                    assertThrows(UnsupportedOperationException.class, sprint::shouldStopSwimSprinting);
                }
            }
            assertEquals(-1, in.read());
            assertTrue(starts > 100, fixture + " start coverage");
            if (hasStops) {
                assertTrue(stopsRun > 100 && stopsRun < 49152);
                assertTrue(stopsSwim > 100 && stopsSwim < 49152);
            }
        }
    }

    /** A used item whose {@code use_effects} allows or prevents sprinting. */
    private static ItemStack usedItem(boolean canSprint) {
        ItemStack stack = FixtureItems.stack(WireItem.of("minecraft:stone", 1));
        stack.set(DataComponents.USE_EFFECTS, new UseEffects(canSprint, true, 0.2F));
        return stack;
    }

    private static boolean bit(int flags, int bit) { return (flags & (1 << bit)) != 0; }
}
