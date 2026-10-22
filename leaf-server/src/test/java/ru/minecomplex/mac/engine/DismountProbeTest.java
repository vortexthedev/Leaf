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
import ru.minecomplex.mac.entity.EntityAttachment;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.engine.movementtick.BoatPlayerTick;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;

/**
 * Wiring of a dismount in {@link MovementCheckRunner}: a "client" made of the same engines rides a boat, gets the server's
 * dismount ({@code set_passengers} without it, then a teleport that keeps its velocity and rotation, as
 * {@code ServerPlayer.removeVehicle} and {@code dismountTo} send them) and falls. The probe must follow the fall with zero
 * offset. Not a vanilla test.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class DismountProbeTest {
    private static final int PLAYER = 7;
    private static final int BOAT = 20;
    private static final int KEEP = PlayerPosition.RELATIVE_DELTA_X | PlayerPosition.RELATIVE_DELTA_Y | PlayerPosition.RELATIVE_DELTA_Z
            | PlayerPosition.RELATIVE_Y_ROT | PlayerPosition.RELATIVE_X_ROT;

    /**
     * Leaving a raft where no block beside it can take the player puts it on the raft's top centre
     * ({@code Entity.getDismountLocationForPassenger}); it then jumps on the raft.
     */
    @Test
    void jumpsOnTheRaftAfterLeavingIt() {
        ride(false, "minecraft:bamboo_raft", true);
    }

    /**
     * The teleport-first order leaves the player on its seat inside the boat's push box: the boat's pushes need a vanilla
     * client, so both orders are compared with one in {@code DismountVehicleProbeOracleTest} (MADV).
     */
    @Test
    void followsTheFallAfterADismount() {
        ride(false);
    }

    private static void ride(boolean teleportFirst) {
        ride(teleportFirst, "minecraft:oak_boat", false);
    }

    /** @param teleportFirst the dismount teleport precedes {@code set_passengers} on the wire (ignored while riding) */
    private static void ride(boolean teleportFirst, String type, boolean onTop) {
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));

        int ice = registry.block("minecraft:packed_ice").firstStateId();
        worldModel.sent(0, Wire.of(new JoinGame(PLAYER, "minecraft:overworld", 0, 64, 8)));
        playerModel.sent(0, Wire.of(new JoinGame(PLAYER, "minecraft:overworld", 0, 64, 8)));
        long seq = 1;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                worldModel.sent(seq++, Wire.of(new ChunkData(x, z, new int[]{0, 0, 0, 0}, new int[]{1, 1, 1, 1}, new int[]{ice, 0, 0, 0},
                        new long[0], ChunkBlockEntities.encode(List.of()))));
            }
        }
        playerModel.sent(seq++, Wire.of(new PlayerPosition(1, 8.5, 16.0, 8.5, 0, 0, 0, 0.0F, 10.0F, 0)));
        worldModel.sent(seq++, Wire.of(new EntitySpawn(BOAT, type, 8.5, 16.0, 8.5, 0.0F, 0.0F, 0.0F, 0, false, 0, 0, 0)));
        worldModel.sent(seq, Wire.of(new EntityPassengers(BOAT, new int[]{PLAYER})));
        SyncWindow window = new SyncWindow(seq, seq + 1);

        PlayerModel.Hypothesis client = playerModel.start(new ru.minecomplex.mac.player.state.PlayerState(),
                new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(window, seq);
        CompensatedWorld level = worldModel.viewAt(window, seq);
        BoatState[] clientState = new BoatState[1];
        ru.minecomplex.mac.player.state.PlayerState[] clientPlayer = new ru.minecomplex.mac.player.state.PlayerState[1];
        MovementEnvironment env = new MovementEnvironment() {
            @Override public boolean fluidsResolved() { return true; }
            @Override public Tri fastLava() { return Tri.FALSE; }
            @Override public BlockSource blocks() { return level; }
            @Override public BlockRegistry registry() { return registry; }
            @Override public BlockTags tags() { return BlockTags.NONE; }
            @Override public boolean hasChunkAt(int x, int z) { return level.isLoaded(x >> 4, z >> 4); }
            @Override public int minY() { return level.minY(); }
            @Override public List<VoxelShape> entityCollisions(Aabb box) {
                // the client's own raft, once it no longer carries the player
                if (!onTop || clientState[0] == null || clientPlayer[0] == null || clientPlayer[0].passenger) return List.of();
                Aabb raft = clientState[0].body.boundingBox(), search = box.inflate(1.0E-7);
                return raft.intersects(search.minX(), search.minY(), search.minZ(), search.maxX(), search.maxY(), search.maxZ())
                        ? List.of(ru.minecomplex.mac.collision.Shapes.create(raft)) : List.of();
            }
            @Override public VoxelShape borderShape(Aabb box) { return null; }
        };
        var graph = new PassengerGraph();
        graph.spawn(PLAYER, true);
        graph.spawn(BOAT);
        graph.startRiding(PLAYER, BOAT, true);
        var types = EntityTypes.get();
        var dimensions = types.type(type).dimensions();
        var state = new BoatState(graph.get(BOAT).generation(), dimensions.width(), dimensions.height(), dimensions.eyeHeight());
        state.body.position = new Vec3d(8.5, 16.0, 8.5);
        state.body.onGround = true;
        state.body.firstTick = false;
        clientState[0] = state;
        clientPlayer[0] = client.player();
        var simulation = new BoatPlayerTick(env, graph, PLAYER, BOAT, state, client.player(), client.local(), FixturePlayers.loaded(), type.endsWith("_raft"), false,
                rider -> types.playerDimensions(rider.pose, rider.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, rider.yRot),
                box -> List.of());
        int[] keys = {0, 1, 1, 1 | 4, 1, 1, 1 | 8, 1, 1, 1};
        var p = client.player();
        String context0 = teleportFirst ? "teleport first" : "";
        double startY = 0;
        for (int tick = 0; tick < 40; tick++) {
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0) packets.add(PlayerLoaded.INSTANCE);
            int mask;
            Movement movement = null;
            if (tick < 10) {
                mask = keys[tick];
                p.yRot = tick * 2.5F - 20.0F;
                p.xRot = 10.0F;
                var result = simulation.tick(PlayerInput.fromMask(mask), Tri.TRUE, true, true);
                for (InboundEvent packet : Wire.in(result.packets())) {
                    packets.add(packet);
                    if (packet instanceof Movement move) movement = move;
                }
            } else {
                if (tick == 10) {
                    // the server dismounts the player and moves it off the boat, three blocks up: velocity and rotation kept
                    var leave = new EntityPassengers(BOAT, new int[0]);
                    var top = state.body.boundingBox();
                    var teleport = onTop ? new PlayerPosition(2, state.body.x(), top.maxY(), state.body.z(), 0, 0, 0, 0.0F, 0.0F, KEEP)
                            : new PlayerPosition(2, 10.5, 19.0, 8.5, 0, 0, 0, 0.0F, 0.0F, KEEP);
                    if (teleportFirst) {
                        playerModel.sent(++seq, Wire.of(teleport));
                        // the client is still a passenger: the teleport is acknowledged, not applied
                        client.apply(new SyncWindow(seq, seq + 1), seq);
                        worldModel.sent(++seq, Wire.of(leave));
                        dismount(graph, client);
                    } else {
                        worldModel.sent(++seq, Wire.of(leave));
                        dismount(graph, client);
                        playerModel.sent(++seq, Wire.of(teleport));
                        client.apply(new SyncWindow(seq, seq + 1), seq);
                    }
                    window = new SyncWindow(seq, seq + 1);
                    startY = p.y();
                }
                mask = onTop ? (tick < 30 ? 16 : 16 | 1) : tick < 25 ? 1 : 1 | 32;
                p.yRot = -90.0F;
                p.xRot = 10.0F;
                var ticker = new MovementTickerPlayer(env, p, client.local(), FixturePlayers.loaded());
                ticker.tick(PlayerInput.fromMask(mask));
                for (InboundEvent packet : Wire.in(ticker.packets())) {
                    packets.add(packet);
                    if (packet instanceof Movement move) movement = move;
                }
            }
            var frame = new ClientTickFrame(tick, tick, tick, window, Wire.ofAll(packets),
                    (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
            MovementCheckRunner.TickReport report = probe.frame(frame);
            String context = context0 + " tick " + tick + " " + report;
            if (tick == 0) continue;
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
            assertEquals(0.0, report.offset(), context);
            if (tick >= 10) assertTrue(report.gaps().isEmpty(), context);
        }
        // with the teleport ignored the player stays by the boat on the ice; applied, it falls three blocks
        if (!teleportFirst && !onTop) assertTrue(p.y() < startY - 2.0, context0 + ": the player fell from " + startY + " to " + p.y());
    }

    /**
     * A ride the probe does not simulate (a minecart, which the server drives): the velocity the passenger's own ticks left
     * is not known, so the fall after the dismount is reported until the player stands still on the ground.
     */
    @Test
    void reportsTheVelocityAnUnfollowedRideLeft() {
        for (String type : List.of("minecart", "chest_minecart", "llama", "trader_llama")) unfollowedRide(false,type);
    }

    /** A teleport with an absolute velocity makes it known at once. */
    @Test
    void anAbsoluteVelocityEndsTheReport() {
        for (String type : List.of("minecart", "chest_minecart", "llama", "trader_llama")) unfollowedRide(true,type);
    }

    private static void unfollowedRide(boolean absolute,String type) {
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
        int ice = registry.block("minecraft:stone").firstStateId();
        worldModel.sent(0, Wire.of(new JoinGame(PLAYER, "minecraft:overworld", 0, 64, 8)));
        playerModel.sent(0, Wire.of(new JoinGame(PLAYER, "minecraft:overworld", 0, 64, 8)));
        long seq = 1;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                worldModel.sent(seq++, Wire.of(new ChunkData(x, z, new int[]{0, 0, 0, 0}, new int[]{1, 1, 1, 1}, new int[]{ice, 0, 0, 0},
                        new long[0], ChunkBlockEntities.encode(List.of()))));
            }
        }
        playerModel.sent(seq++, Wire.of(new PlayerPosition(1, 8.5, 16.0, 8.5, 0, 0, 0, 0.0F, 10.0F, 0)));
        worldModel.sent(seq++, Wire.of(new EntitySpawn(BOAT, "minecraft:"+type, 8.5, 16.0, 8.5, 0.0F, 0.0F, 0.0F, 0, false, 0, 0, 0)));
        worldModel.sent(seq, Wire.of(new EntityPassengers(BOAT, new int[]{PLAYER})));
        SyncWindow window = new SyncWindow(seq, seq + 1);
        PlayerModel.Hypothesis client = playerModel.start(new ru.minecomplex.mac.player.state.PlayerState(),
                new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(window, seq);
        CompensatedWorld level = worldModel.viewAt(window, seq);
        MovementEnvironment env = new MovementEnvironment() {
            @Override public boolean fluidsResolved() { return true; }
            @Override public Tri fastLava() { return Tri.FALSE; }
            @Override public BlockSource blocks() { return level; }
            @Override public BlockRegistry registry() { return registry; }
            @Override public BlockTags tags() { return BlockTags.NONE; }
            @Override public boolean hasChunkAt(int x, int z) { return level.isLoaded(x >> 4, z >> 4); }
            @Override public int minY() { return level.minY(); }
            @Override public List<VoxelShape> entityCollisions(Aabb box) { return List.of(); }
            @Override public VoxelShape borderShape(Aabb box) { return null; }
        };
        var p = client.player();
        p.passenger = true;
        boolean reported = false, resolved = false;
        for (int tick = 0; tick < 60; tick++) {
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0) packets.add(PlayerLoaded.INSTANCE);
            Movement movement;
            int mask = 0;
            if (tick < 10) {
                // a passenger sends its rotation; its own tick (rideTick: zero, then travel in the air with a key held)
                // left a velocity like this one
                p.deltaMovement = new Vec3d(0.0182, -0.0784000015258789, 0.0);
                movement = Movement.rot(0.0F, 10.0F, false, false);
                packets.add(movement);
            } else {
                if (tick == 10) {
                    worldModel.sent(++seq, Wire.of(new EntityPassengers(BOAT, new int[0])));
                    p.passenger = false;
                    playerModel.sent(++seq, Wire.of(new PlayerPosition(2, 10.5, 19.0, 8.5, 0, 0, 0, 0.0F, 0.0F,
                            absolute ? PlayerPosition.RELATIVE_Y_ROT | PlayerPosition.RELATIVE_X_ROT : KEEP)));
                    client.apply(new SyncWindow(seq, seq + 1), seq);
                    window = new SyncWindow(seq, seq + 1);
                }
                mask = tick < 20 ? 1 : 0;
                p.yRot = -90.0F;
                p.xRot = 10.0F;
                movement = null;
                var ticker = new MovementTickerPlayer(env, p, client.local(), FixturePlayers.loaded());
                ticker.tick(PlayerInput.fromMask(mask));
                for (InboundEvent packet : Wire.in(ticker.packets())) {
                    packets.add(packet);
                    if (packet instanceof Movement move) movement = move;
                }
            }
            var report = probe.frame(new ClientTickFrame(tick, tick, tick, window, Wire.ofAll(packets),
                    (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of()));
            if (tick < 10) continue;
            String context = "tick " + tick + " " + report;
            if (absolute) {
                assertTrue(!report.gaps().contains(Unmodeled.RIDING), context);
                assertEquals(0.0, report.offset(), context);
                continue;
            }
            if (report.gaps().contains(Unmodeled.RIDING)) {
                assertTrue(!resolved, "once known it stays known: " + context);
                assertEquals("скорость, оставленная поездкой, неизвестна", report.note(), context);
                reported = true;
            } else {
                assertTrue(reported, "the fall right after the dismount is reported: " + context);
                resolved = true;
                assertEquals(0.0, report.offset(), "known again, the probe follows: " + context);
            }
        }
        if (!absolute) assertTrue(reported && resolved);
    }

    /** {@code handleSetEntityPassengersPacket} without the player: {@code removeVehicle}, the hands freed. */
    private static void dismount(PassengerGraph graph, PlayerModel.Hypothesis client) {
        graph.stopRiding(PLAYER);
        client.player().passenger = false;
        client.local().handsBusy = false;
        client.local().rootVehicle = null;
    }
}
