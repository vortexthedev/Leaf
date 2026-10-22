package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;

import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.GameEvent;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * What the riptide and firework oracles cannot reach: the auto spin attack touching a living entity
 * ({@code checkAutoSpinAttack}), the client's rain level from {@code game_event}, and the wire forms of
 * actual enchantment levels in native 26.3 item packets.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class RiptideModelTest {
    @org.junit.jupiter.api.BeforeAll static void initializeNativePackets() { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }

    private static MovementEnvironment air(Tri living) {
        return world(living, 0);
    }

    /** Air, with {@code wall} at x 1 (and above). */
    private static MovementEnvironment world(Tri living, int wall) {
        var registry = BlockRegistry.get();
        return new MovementEnvironment() {
            @Override public boolean fluidsResolved() { return true; }
            @Override public BlockSource blocks() { return (x, y, z) -> x == 1 ? wall : 0; }
            @Override public BlockRegistry registry() { return registry; }
            @Override public BlockTags tags() { return BlockTags.NONE; }
            @Override public boolean hasChunkAt(int x, int z) { return true; }
            @Override public int minY() { return -64; }
            @Override public List<VoxelShape> entityCollisions(Aabb box) { return List.of(); }
            @Override public VoxelShape borderShape(Aabb box) { return null; }
            @Override public Tri livingIn(Aabb area) { return living; }
        };
    }

    private static PlayerState spinning(boolean mayHaveEnded) {
        PlayerState p = new PlayerState();
        p.position = new Vec3d(0.5, 100.0, 0.5);
        p.deltaMovement = new Vec3d(0.3, 0.4, -0.2);
        p.autoSpinAttackTicks = 2;
        p.autoSpinMayHaveEnded = mayHaveEnded;
        return p;
    }

    private static MovementTickerPlayer tick(MovementEnvironment env, PlayerState p) {
        var physics = new MovementTickerPlayer(env, p, new LocalPlayerState(), FixturePlayers.loaded());
        physics.tick(PlayerInput.fromMask(0));
        return physics;
    }

    @Test
    void aTouchedLivingEntityEndsTheSpinAndTurnsTheVelocityBack() {
        PlayerState none = spinning(false);
        var physics = tick(air(Tri.FALSE), none);
        assertEquals(1, none.autoSpinAttackTicks, "the spin counts down in aiStep");
        assertFalse(physics.unmodeled().contains(Unmodeled.RIPTIDE));
        assertFalse(none.autoSpinMayHaveEnded);

        // checkAutoSpinAttack: the attack changes nothing on the client (hurtClient), then setDeltaMovement(scale(-0.2))
        PlayerState touched = spinning(false);
        physics = tick(air(Tri.TRUE), touched);
        assertFalse(physics.unmodeled().contains(Unmodeled.RIPTIDE));
        assertEquals(0, touched.autoSpinAttackTicks);
        assertEquals(none.deltaMovement.scale(-0.2), touched.deltaMovement);
        assertEquals(none.position, touched.position);

        // a touch not decided (a player, an entity not followed exactly), or a spin that may have ended
        for (PlayerState p : new PlayerState[]{spinning(false), spinning(true)}) {
            physics = tick(air(p.autoSpinMayHaveEnded ? Tri.TRUE : Tri.EITHER), p);
            assertTrue(physics.unmodeled().contains(Unmodeled.RIPTIDE));
            assertTrue(p.autoSpinMayHaveEnded);
            assertTrue(p.riptideUnknownCause.contains("живой сущностью"));
            assertEquals(p.riptideUnknownCause, p.copy().riptideUnknownCause);
        }

        // no spin, no check
        PlayerState idle = new PlayerState();
        idle.position = new Vec3d(0.5, 100.0, 0.5);
        physics = tick(air(Tri.EITHER), idle);
        assertFalse(physics.unmodeled().contains(Unmodeled.RIPTIDE));
    }

    @Test
    void aHorizontalCollisionMayEndTheSpinWhereEntitiesAreNotFollowed() {
        int stone = BlockRegistry.get().stateId("minecraft:stone", "");
        PlayerState p = spinning(false);
        p.deltaMovement = new Vec3d(0.5, 0.0, 0.0);
        var physics = tick(world(Tri.FALSE, stone), p);
        assertTrue(p.horizontalCollision);
        assertFalse(physics.unmodeled().contains(Unmodeled.RIPTIDE));
        assertEquals(1, p.autoSpinAttackTicks);
        assertTrue(p.autoSpinMayHaveEnded, "an item or arrow there keeps the spin, nothing ends it");
        // the last tick: either way it ends
        physics = tick(world(Tri.FALSE, stone), p);
        assertEquals(0, p.autoSpinAttackTicks);
        assertFalse(p.autoSpinMayHaveEnded);
    }

    @Test
    void aLiveSessionRequestsMarkersForRainChanges() {
        var session = new ru.minecomplex.mac.player.MacPlayer(new ru.minecomplex.mac.frame.FrameListener() {
                    public void betweenTicks(ru.minecomplex.mac.frame.BetweenTicks event) {}
                    public void frame(ru.minecomplex.mac.frame.ClientTickFrame frame) {}
                });
        var join = (net.minecraft.network.protocol.game.ClientboundLoginPacket)
                Wire.of(new JoinGame(1, "minecraft:overworld", 0, 64, 2, 0, "", Tri.TRUE));
        ru.minecomplex.mac.fixture.FixturePackets.send(session, join, 1);
        assertTrue(Wire.send(session, new GameEvent(1, 0F), 2));
        assertTrue(Wire.send(session, new GameEvent(7, 1F), 3));
        assertTrue(Wire.send(session, new GameEvent(2, 0F), 4));
        assertFalse(Wire.send(session, new GameEvent(8, 1F), 5), "thunder level does not decide a launch");
    }

    @Test
    void dimensionWeatherFollowsPeDimensionFactsOnLoginAndRespawn() {
        var model = new WorldModel(BlockRegistry.get());
        model.sent(0, Wire.of(new JoinGame(1, "custom:open", 0, 64, 2, 0, "", Tri.TRUE)));
        model.sent(1, Wire.of(new GameEvent(7, 1F)));
        model.sent(2, Wire.of(new ru.minecomplex.mac.fixture.outbound.Respawn("custom:open", 0, 0, 64, 0, Tri.FALSE)));
        model.sent(3, Wire.of(new ru.minecomplex.mac.fixture.outbound.Respawn("custom:ceiling", 0, 0, 64, 0, Tri.FALSE)));
        model.sent(4, Wire.of(new GameEvent(7, 1F)));
        model.sent(5, Wire.of(new ru.minecomplex.mac.fixture.outbound.Respawn("custom:unknown", 0, 0, 64)));
        model.sent(6, Wire.of(new GameEvent(7, 1F)));
        Tri[] rain = {Tri.FALSE, Tri.TRUE, Tri.TRUE, Tri.FALSE, Tri.FALSE, Tri.FALSE, Tri.TRUE};
        for (int through = 0; through < rain.length; through++) {
            try (var hypothesis = model.hypothesis(new SyncWindow(through, through + 1), through)) {
                assertEquals(rain[through], hypothesis.world().raining(), "through " + through);
                assertEquals(rain[through], hypothesis.world().detachedCopy().raining());
            }
        }
    }

    @Test
    void theRainLevelFollowsTheGameEvents() {
        var registry = BlockRegistry.get();
        var model = new WorldModel(registry);
        model.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 64, 2)));
        model.sent(1, Wire.of(new ChunkData(0, 0, new int[4], new int[]{1, 1, 1, 1}, new int[4], new long[0])));
        // START_RAINING (level 0), RAIN_LEVEL_CHANGE 0.19 and 0.2 (isRaining: (double) level > 0.2, and 0.2F is above it),
        // STOP_RAINING (level 1!)
        model.sent(2, Wire.of(new GameEvent(1, 0.0F)));
        model.sent(3, Wire.of(new GameEvent(7, 0.19F)));
        model.sent(4, Wire.of(new GameEvent(7, 0.2F)));
        model.sent(5, Wire.of(new GameEvent(2, 0.0F)));
        model.sent(6, Wire.of(new GameEvent(7, 0.0F)));
        boolean[] dry = {true, true, true, false, false, true};
        for (int through = 1; through <= 6; through++) {
            try (var h = model.hypothesis(new SyncWindow(through, through + 1), through)) {
                assertEquals(dry[through - 1], h.world().surelyDry(), "through " + through);
            }
        }
        assertTrue(WorldModel.isWorldChange(Wire.of(new GameEvent(7, 1.0F))));
        assertFalse(WorldModel.isWorldChange(Wire.of(new GameEvent(GameEvent.CHANGE_GAME_MODE, 1.0F))));
    }

    @Test
    void enchantmentLevelsCrossThePeWire() {
        var stack = ru.minecomplex.mac.inventory.item.Stacks.of("minecraft:trident", 1);
        stack = ru.minecomplex.mac.inventory.item.Stacks.withComponent(stack,
                net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                ru.minecomplex.mac.fixture.FixtureItems.enchantments(net.minecraft.world.item.enchantment.Enchantments.RIPTIDE,5));
        var packet = new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(0, 1, 36, stack);
        var bytes = ru.minecomplex.mac.fixture.FixturePackets.packet(1, ru.minecomplex.mac.replay.Recording.Stage.CLIENTBOUND, packet);
        var connection = new ru.minecomplex.mac.replay.ReplayConnection(Wire.FACTS);
        var decoded = (net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket) connection.send(bytes).value();
        assertEquals(Float.floatToIntBits(4.5F), Float.floatToIntBits(ru.minecomplex.mac.inventory.item.ItemMechanics.spinAttackStrength(decoded.getItem())));
    }
}
