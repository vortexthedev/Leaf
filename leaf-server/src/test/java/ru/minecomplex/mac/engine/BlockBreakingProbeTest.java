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
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerAction;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.outbound.BlockChangedAck;
import ru.minecomplex.mac.fixture.outbound.BlockUpdate;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
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
 * Client block predictions in {@link MovementCheckRunner}: a "client" made of the same engines and its own world hypothesis
 * breaks a block in its key handling ({@code MultiPlayerGameMode.destroyBlock} inside {@code startPrediction}) before it
 * moves, sends the action, and the server answers some ticks later with the block's state and {@code block_changed_ack}.
 * The probe must follow every tick with zero offset: the hole exists from the breaking tick (not from the server's update),
 * the server's update for the predicted block waits for the acknowledgement, and a refused break sets the block back and
 * puts the player colliding with it where it stood ({@code syncBlockState}). Not a vanilla test.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BlockBreakingProbeTest {
    private static final int ACTION = 10, ANSWER = 14;

    /** Creative: the block under the player breaks at once and it falls into the hole. */
    @Test
    void fallsIntoTheHoleACreativeBreakLeaves() {
        double lowest = run(1, 8, 15, 8, false, 0, false);
        assertTrue(lowest < 15.01, "the player did not fall into the hole (" + lowest + ")");
    }

    /** Survival: a start, then a stop some ticks later, of the block in front; walking into the gap it leaves. */
    @Test
    void walksThroughTheGapASurvivalBreakLeaves() {
        double furthest = run(0, 9, 16, 8, false, ru.minecomplex.mac.fixture.inbound.PlayerInput.FORWARD, true);
        assertTrue(furthest > 9.5, "the player did not walk through the gap (" + furthest + ")");
    }

    /** The server refuses the creative break: the block comes back on the acknowledgement and the player is put back. */
    @Test
    void isPutBackWhenTheServerRefusesTheBreak() {
        run(1, 8, 15, 8, true, 0, false);
    }

    /**
     * @param gameMode 0 survival, 1 creative
     * @param refused  the server answers with the old block
     * @param survival a start at {@link #ACTION}, the stop eight ticks later
     * @return the lowest reported y, or in the survival case the furthest reported x
     */
    private static double run(int gameMode, int bx, int by, int bz, boolean refused, int keys, boolean survival) {
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));

        int stone = registry.block("minecraft:stone").firstStateId();
        JoinGame join = new JoinGame(7, "minecraft:overworld", 0, 64, 2, gameMode);
        worldModel.sent(0, Wire.of(join));
        playerModel.sent(0, Wire.of(join));
        worldModel.sent(1, Wire.of(new ChunkData(0, 0, new int[]{0, 0, 0, 0}, new int[]{1, 1, 1, 1}, new int[]{stone, 0, 0, 0},
                new long[0], ChunkBlockEntities.encode(List.of()))));
        long seq = 1;
        // a block in front of the player (east), higher than a step, for the survival case
        if (survival) worldModel.sent(++seq, Wire.of(new BlockUpdate(9, 16, 8, stone)));
        playerModel.sent(++seq, Wire.of(new PlayerPosition(1, 8.5, 16.0, 8.5, 0, 0, 0, -90.0F, 0.0F, 0)));

        PlayerModel.Hypothesis client = playerModel.start(new ru.minecomplex.mac.player.state.PlayerState(),
                new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(new SyncWindow(seq, seq + 1), seq);
        WorldModel.Hypothesis clientWorld = worldModel.hypothesis(new SyncWindow(seq, seq + 1), seq);
        CompensatedWorld level = clientWorld.world();
        MovementEnvironment env = new MovementEnvironment() {
            @Override public boolean fluidsResolved() { return true; }
            @Override public BlockSource blocks() { return level; }
            @Override public BlockRegistry registry() { return registry; }
            @Override public BlockTags tags() { return BlockTags.NONE; }
            @Override public boolean hasChunkAt(int x, int z) { return level.isLoaded(x >> 4, z >> 4); }
            @Override public int minY() { return level.minY(); }
            @Override public List<VoxelShape> entityCollisions(Aabb area) { return List.of(); }
            @Override public VoxelShape borderShape(Aabb area) { return null; }
        };
        int stopTick = ACTION + 8;
        int lastSequence = survival ? 2 : 1;
        int answerTick = survival ? stopTick + 4 : ANSWER;
        double lowest = Double.POSITIVE_INFINITY, furthest = Double.NEGATIVE_INFINITY;
        for (int tick = 0; tick < 50; tick++) {
            String name = "mode " + gameMode + (refused ? " refused" : "") + " tick " + tick;
            // the server's answers: the block (air, or the old one when refused), then the acknowledgement
            List<OutboundEvent> answers = new ArrayList<>();
            if (survival && tick == ACTION + 4) answers.add(new BlockChangedAck(1));
            if (tick == answerTick) {
                answers.add(new BlockUpdate(bx, by, bz, refused ? stone : 0));
                answers.add(new BlockChangedAck(lastSequence));
            }
            for (OutboundEvent answer : answers) {
                worldModel.sent(++seq, Wire.of(answer));
                clientWorld.apply(new SyncWindow(seq, seq + 1), seq);
            }
            for (var block : level.takeRestored()) {
                // ClientLevel.syncBlockState: a colliding player is put back
                var physics = new MovementTickerPlayer(env, client.player(), client.local(), FixturePlayers.loaded());
                if (physics.isColliding(block.pos().x(), block.pos().y(), block.pos().z(), block.state())) {
                    client.player().position = block.playerPos();
                    client.player().oldPosition = block.playerPos();
                }
            }
            SyncWindow window = new SyncWindow(seq, seq + 1);
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0) packets.add(PlayerLoaded.INSTANCE);
            // handleKeybinds, before the player moves: the action inside its prediction
            PlayerAction.Action action = tick == ACTION ? PlayerAction.Action.START_DESTROY_BLOCK
                    : survival && tick == stopTick ? PlayerAction.Action.STOP_DESTROY_BLOCK : null;
            if (action != null) {
                int sequence = action == PlayerAction.Action.STOP_DESTROY_BLOCK ? 2 : 1;
                level.startPrediction(sequence, client.player().position);
                if (gameMode == 1 || action == PlayerAction.Action.STOP_DESTROY_BLOCK) level.destroyBlock(bx, by, bz);
                level.endPrediction();
                packets.add(new PlayerAction(action, bx, by, bz, 1, sequence));
            }
            var ticker = new MovementTickerPlayer(env, client.player(), client.local(), FixturePlayers.loaded());
            ticker.tick(PlayerInput.fromMask(keys));
            Movement movement = null;
            for (InboundEvent packet : Wire.in(ticker.packets())) {
                packets.add(packet);
                if (packet instanceof Movement move) movement = move;
            }
            level.advanceGameTime();
            var frame = new ClientTickFrame(tick, tick, tick, window, Wire.ofAll(packets),
                    (new ru.minecomplex.mac.fixture.inbound.PlayerInput(keys)).flags(), tick == 0, Wire.of(movement), Set.of());
            MovementCheckRunner.TickReport report = probe.frame(frame);
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), name + " " + report.note());
            assertEquals(0.0, report.offset(), name + " " + report);
            // a survival start is uncertain until its acknowledgement (whether it broke at once is not computed)
            Set<Unmodeled> allowed = survival && tick >= ACTION && tick < ACTION + 4 ? Set.of(Unmodeled.BLOCK_PREDICTION) : Set.of();
            assertTrue(allowed.containsAll(report.gaps()), name + " " + report.gaps());
            if (movement != null && movement.hasPosition()) {
                lowest = Math.min(lowest, movement.y());
                furthest = Math.max(furthest, movement.x());
            }
            if (refused && tick > answerTick) {
                assertEquals(stone, probe.clientWorld().getBlock(bx, by, bz), name + ": the refused block came back");
            }
        }
        if (refused) assertEquals(new Vec3d(8.5, 16.0, 8.5), client.player().position, "the client stands where it broke");
        clientWorld.close();
        return survival ? furthest : lowest;
    }
}
