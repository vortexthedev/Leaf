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
import ru.minecomplex.mac.entity.EntityMotions;
import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameAssembler;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.ContainerClosed;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.inbound.TickEnd;
import ru.minecomplex.mac.fixture.outbound.BlockEvent;
import ru.minecomplex.mac.fixture.outbound.BlockUpdate;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.GameTime;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.Marker;
import ru.minecomplex.mac.player.transaction.PongResult;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.world.piston.PistonProperties;
import ru.minecomplex.mac.world.piston.PistonPush;
import ru.minecomplex.mac.world.shulker.ShulkerPush;
import ru.minecomplex.mac.replica.WorldModel;

/**
 * Wiring of block entities in {@link MovementCheckRunner}: a "client" made of the same engines and its own world hypothesis
 * stands on or beside a shulker box that opens and closes, or in front of a piston that extends and retracts
 * ({@code block_event}). Its client tick ticks the block entities after the player as vanilla does
 * ({@code level.tickBlockEntities()} after {@code tickEntities}, before {@code sendChanges}); the opening lid or the moving
 * block pushes it ({@code moveCollidedEntities}) and their shapes change around it. The probe must follow every tick with
 * zero offset. Not a vanilla test: lids, pistons, their shapes and pushes are verified by their oracles (MASH, MAHS,
 * piston fixtures).
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BlockEntityProbeTest {

    private static final int BOX_X = 8, Y = 16, Z = 8;

    /**
     * Standing on a shulker box that faces up: the lid lifts the player half a block and lowers its floor again.
     */
    @Test
    void followsThePlayerOnAnOpeningShulkerBox() {
        double top = run(Scene.shulker("up"), 8.5, 17.0, 8.5, 0, true);
        assertTrue(top > 17.49, "the lid did not lift the player (" + top + ")");
    }

    /**
     * Beside a shulker box that faces east, walking back into it: the lid pushes the player away.
     */
    @Test
    void followsThePlayerBesideAnOpeningShulkerBox() {
        double x = run(Scene.shulker("east"), 9.31, 16.0, 8.5, 2, false);
        assertTrue(x > 9.75, "the lid did not push the player (" + x + ")");
    }

    /**
     * Standing on an open shulker box and closing its screen: escape sends {@code container_close} from input handling,
     * before the drain where the client handles the closing {@code block_event} and answers the marker after it; the
     * tick then lowers the player with the lid. The frames go through {@link FrameAssembler}: the tick's window must
     * be the one after that pong, not one widened back to the close (that left the lid a tick behind the client).
     */
    @Test
    void followsThePlayerLoweredByAClosingShulkerBoxAfterItsScreenCloses() {
        double top = run(Scene.shulker("up"), 8.5, 17.0, 8.5, 0, true, true);
        assertTrue(top > 17.49, "the lid did not lift the player (" + top + ")");
    }

    /**
     * In front of a piston pushing stone east, walking back into it: the stone pushes the player a block.
     */
    @Test
    void followsThePlayerPushedByAPiston() {
        double x = run(Scene.PISTON, 9.31, 16.0, 8.5, 2, false);
        assertTrue(x > 10.2, "the piston did not push the player (" + x + ")");
    }

    /**
     * What stands in the world and what the server triggers before ticks 10 and 40.
     */
    private record Scene(String facing, boolean piston) {

        static final Scene PISTON = new Scene("east", true);

        static Scene shulker(String facing) {
            return new Scene(facing, false);
        }
    }

    /**
     * @param keys the key mask held every tick
     * @param up   whether to return the highest y (else the highest x) reported before the closing event
     */
    private static double run(Scene scene, double x, double y, double z, int keys, boolean up) {
        return run(scene, x, y, z, keys, up, false);
    }

    /**
     * @param screenClose the client closes a screen from input handling before the drain with the closing event
     */
    private static double run(Scene scene, double x, double y, double z, int keys, boolean up, boolean screenClose) {
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
        var props = PistonProperties.get();
        int stone = registry.block("minecraft:stone").firstStateId();
        JoinGame join = new JoinGame(7, "minecraft:overworld", 0, 64, 2);
        worldModel.sent(0, Wire.of(join));
        playerModel.sent(0, Wire.of(join));
        worldModel.sent(1, Wire.of(new ChunkData(0, 0, new int[] { 0, 0, 0, 0 }, new int[] { 1, 1, 1, 1 }, new int[] { stone, 0, 0, 0 }, new long[0], ChunkBlockEntities.encode(List.of()))));
        long seq = 1;
        // a piston at x 7 pushes the stone at x 8 (where the box would be) east, into the player
        int pistonX = BOX_X - 1;
        if (scene.piston()) {
            worldModel.sent(++seq, Wire.of(new BlockUpdate(pistonX, Y, Z, props.with(props.defaultState("minecraft:piston"), "facing", "east"))));
            worldModel.sent(++seq, Wire.of(new BlockUpdate(BOX_X, Y, Z, props.defaultState("minecraft:stone"))));
        } else {
            worldModel.sent(++seq, Wire.of(new BlockUpdate(BOX_X, Y, Z, props.with(props.defaultState("minecraft:shulker_box"), "facing", scene.facing()))));
        }
        worldModel.sent(++seq, Wire.of(new GameTime(1000L)));
        playerModel.sent(++seq, Wire.of(new PlayerPosition(1, x, y, z, 0, 0, 0, -90.0F, 0.0F, 0)));
        int pistonId = registry.block("minecraft:piston").registryId();
        // piston events: 0 extend, 1 retract, the parameter is the facing (east 5); a shulker's event 1 sets its open count
        OutboundEvent open = scene.piston() ? new BlockEvent(pistonX, Y, Z, 0, 5, pistonId) : new BlockEvent(BOX_X, Y, Z, 1, 1, 0);
        OutboundEvent close = scene.piston() ? new BlockEvent(pistonX, Y, Z, 1, 5, pistonId) : new BlockEvent(BOX_X, Y, Z, 1, 0, 0);
        PlayerModel.Hypothesis client = playerModel.start(new ru.minecomplex.mac.player.state.PlayerState(), new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(new SyncWindow(seq, seq + 1), seq);
        WorldModel.Hypothesis clientWorld = worldModel.hypothesis(new SyncWindow(seq, seq + 1), seq);
        CompensatedWorld level = clientWorld.world();
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
                return null;
            }
        };
        double extreme = Double.NEGATIVE_INFINITY;
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
        long inbound = 0;
        for (int tick = 0; tick < 70; tick++) {
            // the server triggers before tick 10 and before tick 40, each with a marker after it
            OutboundEvent change = tick == 10 ? open : tick == 40 ? close : null;
            if (change != null) {
                worldModel.sent(++seq, Wire.of(change));
                clientWorld.apply(new SyncWindow(seq, seq + 1), seq);
            }
            SyncWindow window = new SyncWindow(seq, seq + 1);
            var physics = new MovementTickerPlayer(env, client.player(), client.local(), FixturePlayers.loaded());
            long time = level.gameTime();
            Set<Unmodeled> clientGaps = java.util.EnumSet.noneOf(Unmodeled.class);
            ShulkerPush.Query shulkers = area -> {
                var p = client.player();
                return EntityMotions.listed(area, p.position, p.boundingBox()) ? List.of(physics.shulkerEntity()) : List.of();
            };
            PistonPush.Query pistons = area -> {
                var p = client.player();
                return EntityMotions.listed(area, p.position, p.boundingBox()) ? List.of(physics.pistonEntity(time)) : List.of();
            };
            var push = new PistonPush(registry, BlockCollisions.emptyContext(registry, level, clientGaps::add));
            {
                physics.simulateTick(PlayerInput.fromMask(keys));
                level.tickBlockEntities(time, push, pistons, shulkers);
                physics.sendAfterEntityTicks();
            }
            // ClientLevel.tickTime after the block entities
            level.advanceGameTime();
            String name = "" + scene + " tick " + tick;
            assertTrue(physics.unmodeled().isEmpty() && clientGaps.isEmpty(), name + " client " + physics.unmodeled() + clientGaps);
            Movement movement = null;
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0)
                packets.add(PlayerLoaded.INSTANCE);
            for (InboundEvent packet : Wire.in(physics.packets())) {
                packets.add(packet);
                if (packet instanceof Movement move)
                    movement = move;
            }
            if (screenClose && tick == 40) {
                // escape in the previous render frame, then the drain: the event and the marker after it, its pong
                assembler.accept(inbound++, Wire.of(new ContainerClosed(0)), new SyncWindow(seq - 1, seq + 1));
                assembler.pong(inbound++, 1, PongResult.acknowledged(new Marker(1, seq, 0), 0));
            }
            for (InboundEvent packet : packets) assembler.accept(inbound++, Wire.of(packet), window);
            assembler.accept(inbound++, Wire.of(TickEnd.INSTANCE), window);
            var frame = built.removeLast();
            MovementCheckRunner.TickReport report = probe.frame(frame);
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), name + " " + report.note());
            assertEquals(0.0, report.offset(), name + " " + report);
            assertTrue(report.gaps().isEmpty(), name + " " + report.gaps());
            if (movement != null && movement.hasPosition() && tick < 40)
                extreme = Math.max(extreme, up ? movement.y() : movement.x());
        }
        clientWorld.close();
        return extreme;
    }
}
