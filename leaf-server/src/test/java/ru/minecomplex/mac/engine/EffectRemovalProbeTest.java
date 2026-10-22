package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EffectRemove;
import ru.minecomplex.mac.fixture.outbound.EffectUpdate;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;

/**
 * Effect changes in {@link MovementCheckRunner} when the client handles them before it answers the marker after them: a
 * "client" made of the same engines gets the change in one frame and the marker some frames later (the frames between
 * still see the change in flight). Before that frame the change was in flight while the client had not handled it yet.
 * The probe must follow every tick with zero offset, the removed levitation's fall included (its effect shows only in
 * the velocity the handling tick leaves). Not a vanilla test.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class EffectRemovalProbeTest {
    private static final int PLAYER = 7;

    @Test
    void followsTheFallAfterLevitationIsRemoved() {
        for (int markerDelay = 0; markerDelay <= 2; markerDelay++) {
            run(markerDelay, 16.0, new EffectUpdate(PLAYER, "minecraft:levitation", 1, 400, false, true, true, true),
                    new EffectRemove(PLAYER, "minecraft:levitation"), -1);
        }
    }

    @Test
    void followsTheRiseAfterLevitationIsGiven() {
        for (int markerDelay = 0; markerDelay <= 2; markerDelay++) {
            run(markerDelay, 16.0, null, new EffectUpdate(PLAYER, "minecraft:levitation", 0, 400, false, true, true, true), -1);
        }
    }

    @Test
    void followsTheFallAfterSlowFallingIsRemoved() {
        for (int markerDelay = 0; markerDelay <= 2; markerDelay++) {
            run(markerDelay, 40.0, new EffectUpdate(PLAYER, "minecraft:slow_falling", 0, 400, false, true, true, true),
                    new EffectRemove(PLAYER, "minecraft:slow_falling"), -1);
        }
    }

    /** A jump in the handling tick: the first without the boost, while the removal is still in flight. */
    @Test
    void followsJumpsAfterJumpBoostIsRemoved() {
        for (int markerDelay = 0; markerDelay <= 2; markerDelay++) {
            run(markerDelay, 16.0, new EffectUpdate(PLAYER, "minecraft:jump_boost", 2, 400, false, true, true, true),
                    new EffectRemove(PLAYER, "minecraft:jump_boost"), HANDLED);
        }
    }

    /**
     * @param initial     an effect certainly applied before the first tick, {@code null} for none
     * @param change      the change sent at {@link #SENT}, handled by the client before tick {@link #HANDLED}
     * @param markerDelay frames after the handling one until the client answers the marker after the change
     * @param jumpTick    the tick in which the jump key is down, {@code -1} for none
     */
    private static void run(int markerDelay, double startY, EffectUpdate initial, OutboundEvent change,
                            int jumpTick) {
        String name = "delay " + markerDelay + " " + change;
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));

        int stone = registry.block("minecraft:stone").firstStateId();
        JoinGame join = new JoinGame(PLAYER, "minecraft:overworld", 0, 64, 2);
        worldModel.sent(0, Wire.of(join));
        playerModel.sent(0, Wire.of(join));
        worldModel.sent(1, Wire.of(new ChunkData(0, 0, new int[]{0, 0, 0, 0}, new int[]{1, 1, 1, 1}, new int[]{stone, 0, 0, 0},
                new long[0], ChunkBlockEntities.encode(List.of()))));
        playerModel.sent(2, Wire.of(new PlayerPosition(1, 8.5, startY, 8.5, 0, 0, 0, 30.0F, 10.0F, 0)));
        long seq = 2;
        if (initial != null) playerModel.sent(++seq, Wire.of(initial));
        long certain = seq;
        long changeSeq = seq + 1;

        PlayerModel.Hypothesis client = playerModel.start(new ru.minecomplex.mac.player.state.PlayerState(),
                new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(new SyncWindow(certain, certain + 1), certain);
        CompensatedWorld level = worldModel.viewAt(new SyncWindow(certain, certain + 1), 1);
        MovementEnvironment env = new MovementEnvironment() {
            @Override public boolean fluidsResolved() { return true; }
            @Override public BlockSource blocks() { return level; }
            @Override public BlockRegistry registry() { return registry; }
            @Override public BlockTags tags() { return BlockTags.NONE; }
            @Override public boolean hasChunkAt(int x, int z) { return level.isLoaded(x >> 4, z >> 4); }
            @Override public int minY() { return level.minY(); }
            @Override public List<VoxelShape> entityCollisions(Aabb box) { return List.of(); }
            @Override public VoxelShape borderShape(Aabb box) { return null; }
        };
        boolean noted = false;
        for (int tick = 0; tick < 80; tick++) {
            if (tick == SENT) {
                // the change, then the marker after it
                playerModel.sent(changeSeq, Wire.of(change));
            }
            SyncWindow window;
            if (tick < SENT) window = new SyncWindow(certain, certain + 1);
            else if (tick < HANDLED + markerDelay) window = new SyncWindow(certain, changeSeq + 1);
            else window = new SyncWindow(changeSeq + 1, changeSeq + 2);
            if (tick == HANDLED) client.applyStep(changeSeq, ru.minecomplex.mac.listener.ClientPlayerActions.Target.UNKNOWN);
            int keys = tick == jumpTick ? 16 : 0;
            var ticker = new MovementTickerPlayer(env, client.player(), client.local(), FixturePlayers.loaded());
            ticker.tick(PlayerInput.fromMask(keys));
            Movement movement = null;
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0) packets.add(PlayerLoaded.INSTANCE);
            for (InboundEvent packet : Wire.in(ticker.packets())) {
                packets.add(packet);
                if (packet instanceof Movement move) movement = move;
            }
            var frame = new ClientTickFrame(tick, tick, tick, window, Wire.ofAll(packets),
                    (new ru.minecomplex.mac.fixture.inbound.PlayerInput(keys)).flags(), tick == 0, Wire.of(movement), Set.of());
            MovementCheckRunner.TickReport report = probe.frame(frame);
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), name + " tick " + tick + " " + report.note());
            assertEquals(0.0, report.offset(), name + " tick " + tick + " " + report);
            assertTrue(report.gaps().isEmpty(), name + " tick " + tick + " " + report.gaps());
            noted |= report.note().contains("до ответа на маркер");
        }
        // the frames between the handling and the marker agree only with the client that handled the change
        if (markerDelay > 0 && change instanceof EffectRemove) {
            assertTrue(noted, name + ": the probe never followed the early handling");
        }
    }

    /** The server sends the change before tick {@code SENT}; the client handles it before tick {@code HANDLED}. */
    private static final int SENT = 25, HANDLED = 28;
}
