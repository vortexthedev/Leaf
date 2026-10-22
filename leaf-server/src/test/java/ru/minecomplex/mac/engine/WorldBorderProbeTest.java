package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameAssembler;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.inbound.TickEnd;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.fixture.outbound.WorldBorder;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;

/** Native tick-based border phase wiring; vanilla geometry is checked by WorldBorderOracleTest. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class WorldBorderProbeTest {

    /**
     * The tick before which a lerp is sent.
     */
    private static final int LERP_TICK = 20;

    private static final int TICKS = 140;

    /**
     * A static border stops the player walking into it, with every tick known.
     */
    @Test
    void followsThePlayerStoppedByAStaticBorder() {
        var run = run(null, false, 11.5, 1);
        assertEquals(12.7, run.maxX, 1.0E-6, "the border did not stop the player");
        for (var report : run.reports) assertKnown(report);
    }

    /** A growing border lets the player follow its rounded interior with a known phase. */
    @Test
    void followsThePlayerAtAGrowingBorder() {
        var run = run(12.0, false, 11.5, 1);
        for (var report : run.reports) assertKnown(report);
        assertEquals(13.7, run.maxX, 1.0E-6, "the player did not follow the border");
    }

    /**
     * Away from the border its unknown phase does not matter: every tick is known.
     */
    @Test
    void followsThePlayerAwayFromAShrinkingBorder() {
        var run = run(8.0, false, 8.5, 0);
        for (var report : run.reports) assertKnown(report);
    }

    /**
     * The client handles the lerp a tick before the probe knows it has: the probe's border may be a tick behind,
     * reported while that matters, never a silent difference; known again once the lerp has ended.
     */
    @Test
    void reportsALerpTheClientHandledEarlier() {
        var run = run(12.0, true, 11.5, 1);
        boolean reported = false;
        for (int tick = 0; tick < run.reports.size(); tick++) {
            var report = run.reports.get(tick);
            if (tick < LERP_TICK || tick >= LERP_TICK + 40 + 22 + 1)
                assertKnown(report);
            else
                assertNoSilentDifference(report);
            reported |= report.gaps().contains(Unmodeled.WORLD_BORDER_CLOCK);
        }
        assertTrue(reported, "the phase a tick ahead was not reported");
        assertFalse(run.reports.get(LERP_TICK).inFlight().isEmpty(), "the lerp was not in flight");
    }

    private static void assertKnown(MovementCheckRunner.TickReport report) {
        String name = "tick " + report.frame();
        assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), name + " " + report.note());
        assertTrue(report.gaps().isEmpty() && !report.windowOpen(), name + " " + report);
        assertEquals(0.0, report.offset(), name + " " + report);
    }

    private static void assertNoSilentDifference(MovementCheckRunner.TickReport report) {
        String name = "tick " + report.frame();
        assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), name + " " + report.note());
        if (report.gaps().isEmpty() && !report.windowOpen())
            assertEquals(0.0, report.offset(), name + " " + report);
    }

    private record Run(List<MovementCheckRunner.TickReport> reports, double maxX) {
    }

    /**
     * @param lerpTo a lerp from size 10 to this over 40 client ticks is sent before {@link #LERP_TICK}
     *               ({@code null}: none)
     * @param late   the client handles it a tick before the marker after it is certain
     * @param keys   the key mask held every tick, facing east
     */
    private static Run run(Double lerpTo, boolean late, double x, int keys) {
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
        int stone = registry.block("minecraft:stone").firstStateId();
        JoinGame join = new JoinGame(7, "minecraft:overworld", 0, 64, 2);
        worldModel.sent(0, Wire.of(join));
        playerModel.sent(0, Wire.of(join));
        worldModel.sent(1, Wire.of(new ChunkData(0, 0, new int[] { 0, 0, 0, 0 }, new int[] { 1, 1, 1, 1 }, new int[] { stone, 0, 0, 0 }, new long[0], ChunkBlockEntities.encode(List.of()))));
        long seq = 1;
        // bounds 3..13 around the chunk's middle
        worldModel.sent(++seq, Wire.of(new WorldBorder(WorldBorder.Operation.INITIALIZE, 8.0, 8.0, 10.0, 10.0, 0L, 29999984)));
        playerModel.sent(++seq, Wire.of(new PlayerPosition(1, x, 16.0, 8.5, 0, 0, 0, -90.0F, 0.0F, 0)));
        PlayerModel.Hypothesis client = playerModel.start(new ru.minecomplex.mac.player.state.PlayerState(), new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(new SyncWindow(seq, seq + 1), seq);
        WorldModel.Hypothesis clientWorld = worldModel.hypothesis(new SyncWindow(seq, seq + 1), seq);
        CompensatedWorld level = clientWorld.world();
        Set<Unmodeled> clientGaps = EnumSet.noneOf(Unmodeled.class);
        MovementEnvironment env = new MovementEnvironment() {

            @Override
            public boolean fluidsResolved() {
                return true;
            }

            @Override
            public BlockSource blocks() {
                return level;
            }

            @Override
            public BlockRegistry registry() {
                return registry;
            }

            @Override
            public BlockTags tags() {
                return BlockTags.NONE;
            }

            @Override
            public boolean hasChunkAt(int bx, int bz) {
                return level.isLoaded(bx >> 4, bz >> 4);
            }

            @Override
            public int minY() {
                return level.minY();
            }

            @Override
            public List<VoxelShape> entityCollisions(Aabb area) {
                return List.of();
            }

            @Override
            public VoxelShape borderShape(Aabb area) {
                var p = client.player();
                return level.borderShape(p.x(), p.z(), area, clientGaps);
            }
        };
        List<ClientTickFrame> built = new ArrayList<>();
        var assembler = new FrameAssembler(new FrameListener() {

            @Override
            public void betweenTicks(BetweenTicks event) {
            }

            @Override
            public void frame(ClientTickFrame frame) {
                built.add(frame);
            }
        });
        List<MovementCheckRunner.TickReport> reports = new ArrayList<>();
        double maxX = Double.NEGATIVE_INFINITY;
        long inbound = 0;
        for (int tick = 0; tick < TICKS; tick++) {
            SyncWindow window = new SyncWindow(seq, seq + 1);
            if (lerpTo != null && tick == LERP_TICK) {
                worldModel.sent(++seq, Wire.of(new WorldBorder(WorldBorder.Operation.LERP, 8.0, 8.0, 10.0, lerpTo, 40L, 0)));

                clientWorld.apply(new SyncWindow(seq, seq + 1), seq);
                window = late ? new SyncWindow(seq - 1, seq + 1) : new SyncWindow(seq, seq + 1);
            }
            int mask = keys;
            var physics = new MovementTickerPlayer(env, client.player(), client.local(), FixturePlayers.loaded());
            {
                physics.simulateTick(PlayerInput.fromMask(mask));
                physics.sendAfterEntityTicks();
            }
            // ClientLevel.tick: the border after the entities
            level.tickBorder();
            level.advanceGameTime();
            String name = "tick " + tick;
            assertTrue(physics.unmodeled().isEmpty() && clientGaps.isEmpty(), name + " client " + physics.unmodeled() + clientGaps);
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0)
                packets.add(PlayerLoaded.INSTANCE);
            for (InboundEvent packet : Wire.in(physics.packets())) {
                packets.add(packet);
                if (packet instanceof Movement move && move.hasPosition())
                    maxX = Math.max(maxX, move.x());
            }
            for (InboundEvent packet : packets) {

                assembler.accept(inbound++, Wire.of(packet), window);
            }

            assembler.accept(inbound++, Wire.of(TickEnd.INSTANCE), window);
            reports.add(probe.frame(built.removeLast()));
        }
        clientWorld.close();
        return new Run(reports, maxX);
    }
}
