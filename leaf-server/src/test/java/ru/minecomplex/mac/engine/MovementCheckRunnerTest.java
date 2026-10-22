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
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.replica.WorldModel;

/**
 * Wiring of {@link MovementCheckRunner}: a "client" made of the same engine on the same world sends its packets; the probe
 * must follow it with zero offset (window application, load gate, keys, rotation, position hand-over). Not a vanilla
 * test: the engine itself is verified by the movement oracles.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class MovementCheckRunnerTest {
    @Test
    void followsAClientRunningTheSameEngine() {
        follow(true);
    }

    @Test
    void movementShowsALoadedClientWithoutPlayerLoaded() {
        follow(false);
    }

    private static void follow(boolean playerLoaded) {
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));

        int stone = registry.block("minecraft:stone").firstStateId();
        JoinGame join = new JoinGame(7, "minecraft:overworld", 0, 32, 2);
        // section 0 stone (y 0-15), section 1 air
        ChunkData chunk = new ChunkData(0, 0, new int[]{0, 0}, new int[]{1, 1}, new int[]{stone, 0}, new long[0],
                ChunkBlockEntities.encode(List.of()));
        PlayerPosition spawn = new PlayerPosition(1, 8.5, 16.0, 8.5, 0, 0, 0, 30.0F, 10.0F, 0);
        worldModel.sent(0, Wire.of(join));
        playerModel.sent(0, Wire.of(join));
        worldModel.sent(1, Wire.of(chunk));
        playerModel.sent(2, Wire.of(spawn));
        SyncWindow window = new SyncWindow(2, 3);

        // the "client": its own hypothesis of the same changes, ticked by the engine
        PlayerModel.Hypothesis client = playerModel.start(new ru.minecomplex.mac.player.state.PlayerState(),
                new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(window, 2);
        CompensatedWorld level = worldModel.viewAt(window, 2);
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
        int[] keys = {0, 1, 1, 1 | 64, 1 | 64 | 16, 1 | 64, 1 | 4, 2, 2 | 32, 0, 0, 16, 0};
        int compared = 0;
        for (int tick = 0; tick < 60; tick++) {
            int mask = keys[tick % keys.length];
            float yaw = 30.0F + tick * 3.5F, pitch = 10.0F;
            client.player().yRot = yaw;
            client.player().xRot = pitch;
            var ticker = new MovementTickerPlayer(env, client.player(), client.local(), FixturePlayers.loaded());
            ticker.tick(PlayerInput.fromMask(mask));
            Movement movement = null;
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0 && playerLoaded) packets.add(PlayerLoaded.INSTANCE);
            for (InboundEvent packet : Wire.in(ticker.packets())) {
                packets.add(packet);
                if (packet instanceof Movement move) movement = move;
            }
            var frame = new ClientTickFrame(tick, tick, tick, window, Wire.ofAll(packets),
                    (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
            MovementCheckRunner.TickReport report = probe.frame(frame);
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), "tick " + tick + " " + report.note());
            assertEquals(0.0, report.offset(), "tick " + tick + " " + report);
            assertTrue(report.gaps().isEmpty(), "tick " + tick + " " + report.gaps());
            compared++;
        }
        assertEquals(60, compared);
    }
}
