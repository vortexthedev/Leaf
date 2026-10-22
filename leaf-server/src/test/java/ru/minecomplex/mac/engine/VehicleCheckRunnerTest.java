package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Wiring of the ride in {@link MovementCheckRunner}: a "client" made of the same boat engine drives a boat on the same world;
 * after the seeded first tick the probe must follow its {@code move_vehicle} with zero offset. Not a vanilla test: the
 * engines are verified by their oracles (the world border by {@code WorldBorderOracleTest}).
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class VehicleCheckRunnerTest {

    private static final int PLAYER = 7;

    private static final int BOAT = 20;

    @Test
    void followsDrivenBoatsAfterServerVelocityReplacement() {
        drive("minecraft:bamboo_chest_raft", null, null, true);
    }

    @Test
    void followsABoatDrivenByTheSameEngine() {
        drive("minecraft:oak_boat", null, null);
        drive("minecraft:bamboo_chest_raft", null, null);
    }

    /**
     * The boat drives into a static world border around it: the border query is the boat's own while it moves.
     */
    @Test
    void followsABoatStoppedByTheWorldBorder() {
        drive("minecraft:oak_boat", 4.0, null);
        drive("minecraft:bamboo_chest_raft", 4.0, null);
    }

    /**
     * A boat resting over a potent sulfur geyser that starts erupting (26.3): the geyser lifts the driven boat in the
     * block-entity phase of the client tick (not its passenger), and the ride continues from that velocity.
     */
    @Test
    void followsABoatLiftedByAGeyser() {
        String type = "minecraft:oak_boat";
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
        int ice = registry.block("minecraft:packed_ice").firstStateId();
        int water = registry.stateId("minecraft:water", "level=0");
        int dormant = registry.stateId("minecraft:potent_sulfur", "potent_sulfur_state=dormant");
        int erupting = registry.stateId("minecraft:potent_sulfur", "potent_sulfur_state=erupting");
        JoinGame join = new JoinGame(PLAYER, "minecraft:overworld", 0, 32, 8);
        worldModel.sent(0, Wire.of(join));
        playerModel.sent(0, Wire.of(join));
        long seq = 1;
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                worldModel.sent(seq++, Wire.of(new ChunkData(x, z, new int[] { 0, 0 }, new int[] { 1, 1 }, new int[] { ice, 0 }, new long[0], ChunkBlockEntities.encode(List.of()))));
            }
        }
        // a dormant geyser under two water blocks in the ice, right under the boat
        worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.BlockUpdate(8, 13, 8, dormant)));
        worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.BlockUpdate(8, 14, 8, water)));
        worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.BlockUpdate(8, 15, 8, water)));
        playerModel.sent(seq++, Wire.of(new PlayerPosition(1, 8.5, 16.0, 8.5, 0, 0, 0, 0.0F, 10.0F, 0)));
        worldModel.sent(seq++, Wire.of(new EntitySpawn(BOAT, type, 8.5, 16.0, 8.5, 0.0F, 0.0F, 0.0F, 0, false, 0, 0, 0)));
        long last = seq;
        worldModel.sent(last, Wire.of(new EntityPassengers(BOAT, new int[] { PLAYER })));
        SyncWindow window = new SyncWindow(last, last + 1);
        PlayerModel.Hypothesis client = playerModel.start(new ru.minecomplex.mac.player.state.PlayerState(), new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(window, last);
        CompensatedWorld level = worldModel.viewAt(window, last).detachedCopy();
        MovementEnvironment env = new MovementEnvironment() {

            @Override
            public boolean fluidsResolved() {
                return true;
            }

            @Override
            public Tri fastLava() {
                return Tri.FALSE;
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
            public boolean hasChunkAt(int x, int z) {
                return level.isLoaded(x >> 4, z >> 4);
            }

            @Override
            public int minY() {
                return level.minY();
            }

            @Override
            public List<VoxelShape> entityCollisions(Aabb box) {
                return List.of();
            }

            @Override
            public VoxelShape borderShape(Aabb box) {
                return null;
            }
        };
        var graph = new PassengerGraph();
        graph.spawn(PLAYER, true);
        graph.spawn(BOAT);
        graph.startRiding(PLAYER, BOAT, true);
        var dimensions = EntityTypes.get().type(type).dimensions();
        var state = new BoatState(graph.get(BOAT).generation(), dimensions.width(), dimensions.height(), dimensions.eyeHeight());
        state.body.position = new Vec3d(8.5, 16.0, 8.5);
        state.body.onGround = true;
        state.body.firstTick = false;
        var types = EntityTypes.get();
        var simulation = new BoatPlayerTick(env, graph, PLAYER, BOAT, state, client.player(), client.local(), FixturePlayers.loaded(), false, false, rider -> types.playerDimensions(rider.pose, rider.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, rider.yRot), box -> List.of());
        double highest = 16.0;
        for (int tick = 0; tick < 40; tick++) {
            if (tick == 5) {
                var eruption = new ru.minecomplex.mac.fixture.outbound.BlockUpdate(8, 13, 8, erupting);
                worldModel.sent(++last, Wire.of(eruption));
                level.setServerVerifiedBlock(8, 13, 8, erupting);
                window = new SyncWindow(last, last + 1);
            }
            client.player().yRot = 0.0F;
            client.player().xRot = 10.0F;
            var result = simulation.tick(PlayerInput.fromMask(0), Tri.TRUE, true, true);
            assertEquals(Set.of(), result.unmodeled(), "client tick " + tick);
            // the client's block entities: the geyser lifts the boat it simulates, its passenger is not lifted
            var boatBody = state.body;
            var rider = client.player();
            level.tickBlockEntities(0L, null, null, area -> List.of(), area -> {
                List<ru.minecomplex.mac.engine.blockeffects.GeyserLaunch.Entity> listed = new ArrayList<>();
                for (var body : List.of(boatBody, rider)) {
                    if (!ru.minecomplex.mac.entity.EntityMotions.listed(area, body.position, body.boundingBox()))
                        continue;
                    listed.add(new ru.minecomplex.mac.engine.blockeffects.GeyserLaunch.Entity() {

                        @Override
                        public Vec3d velocity() {
                            return body.deltaMovement;
                        }

                        @Override
                        public void velocity(Vec3d value) {
                            body.deltaMovement = value;
                        }

                        @Override
                        public double fallDistance() {
                            return body.fallDistance;
                        }

                        @Override
                        public void fallDistance(double value) {
                            body.fallDistance = value;
                        }

                        @Override
                        public boolean launchable() {
                            return body == boatBody;
                        }
                    });
                }
                return listed;
            });
            highest = Math.max(highest, state.body.position.y());
            Movement movement = null;
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0)
                packets.add(PlayerLoaded.INSTANCE);
            for (InboundEvent packet : Wire.in(result.packets())) {
                packets.add(packet);
                if (packet instanceof Movement move)
                    movement = move;
            }
            var frame = new ClientTickFrame(tick, tick, tick, window, Wire.ofAll(packets), (new ru.minecomplex.mac.fixture.inbound.PlayerInput(0)).flags(), true, Wire.of(movement), Set.of());
            MovementCheckRunner.TickReport report = probe.frame(frame);
            String context = "tick " + tick + " " + report;
            if (tick == 0)
                continue;
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
            assertNotNull(report.reported(), context);
            assertEquals(0.0, report.offset(), context);
            assertTrue(report.gaps().isEmpty(), context);
        }
        assertTrue(highest > 16.5, "the geyser did not lift the boat: " + highest);
    }

    /**
     * @param border the size of a static border centred on the boat ({@code null}: none)
     * @param lerpTo the size it grows to over 40 client ticks from tick 20 ({@code null}: it stays)
     */
    private static void drive(String type, Double border, Double lerpTo) {
        drive(type, border, lerpTo, false);
    }

    private static void drive(String type, Double border, Double lerpTo, boolean replaceVelocity) {
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
        int ice = registry.block("minecraft:packed_ice").firstStateId();
        JoinGame join = new JoinGame(PLAYER, "minecraft:overworld", 0, 32, 8);
        PlayerPosition spawn = new PlayerPosition(1, 8.5, 16.0, 8.5, 0, 0, 0, 0.0F, 10.0F, 0);
        EntitySpawn boat = new EntitySpawn(BOAT, type, 8.5, 16.0, 8.5, 0.0F, 0.0F, 0.0F, 0, false, 0, 0, 0);
        EntityPassengers mount = new EntityPassengers(BOAT, new int[] { PLAYER });
        worldModel.sent(0, Wire.of(join));
        playerModel.sent(0, Wire.of(join));
        long seq = 1;
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                worldModel.sent(seq++, Wire.of(new ChunkData(x, z, new int[] { 0, 0 }, new int[] { 1, 1 }, new int[] { ice, 0 }, new long[0], ChunkBlockEntities.encode(List.of()))));
            }
        }
        playerModel.sent(seq++, Wire.of(spawn));
        if (border != null) {
            worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.WorldBorder(ru.minecomplex.mac.fixture.outbound.WorldBorder.Operation.INITIALIZE, 8.5, 8.5, border, border, 0L, 29999984)));
        }
        worldModel.sent(seq++, Wire.of(boat));
        long last = seq;
        worldModel.sent(last, Wire.of(mount));
        SyncWindow window = new SyncWindow(last, last + 1);
        // the "client": its own player hypothesis and boat, ticked by the engine
        PlayerModel.Hypothesis client = playerModel.start(new ru.minecomplex.mac.player.state.PlayerState(), new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(window, last);
        CompensatedWorld level = worldModel.viewAt(window, last);
        // the boat moves itself, then its rider's own tick queries as the player
        boolean[] riderMoving = { false };
        BoatState[] moving = { null };
        MovementEnvironment env = new MovementEnvironment() {

            @Override
            public boolean fluidsResolved() {
                return true;
            }

            @Override
            public Tri fastLava() {
                return Tri.FALSE;
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
            public boolean hasChunkAt(int x, int z) {
                return level.isLoaded(x >> 4, z >> 4);
            }

            @Override
            public int minY() {
                return level.minY();
            }

            @Override
            public List<VoxelShape> entityCollisions(Aabb box) {
                return List.of();
            }

            @Override
            public VoxelShape borderShape(Aabb box) {
                Vec3d at = riderMoving[0] ? client.player().position : moving[0].body.position;
                Set<ru.minecomplex.mac.engine.Unmodeled> gaps = java.util.EnumSet.noneOf(ru.minecomplex.mac.engine.Unmodeled.class);
                VoxelShape shape = level.borderShape(at.x(), at.z(), box, gaps);
                assertTrue(gaps.isEmpty(), gaps.toString());
                return shape;
            }
        };
        var graph = new PassengerGraph();
        graph.spawn(PLAYER, true);
        graph.spawn(BOAT);
        graph.startRiding(PLAYER, BOAT, true);
        var dimensions = EntityTypes.get().type(type).dimensions();
        var state = new BoatState(graph.get(BOAT).generation(), dimensions.width(), dimensions.height(), dimensions.eyeHeight());
        state.body.position = new Vec3d(8.5, 16.0, 8.5);
        state.body.onGround = true;
        state.body.firstTick = false;
        moving[0] = state;
        var types = EntityTypes.get();
        var simulation = new BoatPlayerTick(env, graph, PLAYER, BOAT, state, client.player(), client.local(), FixturePlayers.loaded(), type.endsWith("_raft"), type.contains("_chest_"), rider -> types.playerDimensions(rider.pose, rider.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, rider.yRot), box -> List.of());
        simulation.beforeRider = ignored -> riderMoving[0] = true;
        boolean lerps = lerpTo != null;
        int lerpTick = 20;
        // standing still first (the probe seeds the boat from that tick), then paddling and turning on ice
        int[] keys = { 0, 1, 1, 1 | 4, 1 | 4, 1, 1 | 8, 8, 2, 2 | 4, 0, 0, 1, 1, 1 };
        int compared = 0;
        double reach = 0.0;
        // a lerp runs past its end (the interpolation has completed after its client tick budget): the border is known again
        for (int tick = 0; tick < (lerps ? 110 : 60); tick++) {
            riderMoving[0] = false;
            if (replaceVelocity && (tick == 3 || tick == 7 || tick == 13)) {
                Vec3d velocity = tick == 7 ? new Vec3d(0.125, 0.0, -0.25) : Vec3d.ZERO;
                state.body.deltaMovement = velocity;
                worldModel.sent(++last, Wire.of(new ru.minecomplex.mac.fixture.outbound.EntityVelocity(BOAT, velocity.x(), velocity.y(), velocity.z())));
                window = new SyncWindow(last, last + 1);
            }
            if (lerps && tick == lerpTick) {
                var lerp = new ru.minecomplex.mac.fixture.outbound.WorldBorder(ru.minecomplex.mac.fixture.outbound.WorldBorder.Operation.LERP, 8.5, 8.5, border, lerpTo, 2000L, 0);
                worldModel.sent(++last, Wire.of(lerp));
                level.border(Wire.of(lerp));
                window = new SyncWindow(last, last + 1);
            }
            int mask = tick == 0 ? 0 : keys[tick % keys.length];
            client.player().yRot = tick * 2.5F - 20.0F;
            client.player().xRot = 10.0F;
            // LocalPlayer.turn → AbstractBoat.onPassengerTurned clamps the rider's yaw against the boat's
            float delta = ru.minecomplex.mac.engine.movementtick.BoatPassengerPhysics.wrap(client.player().yRot - state.body.yRot);
            client.player().yRot = client.player().yRot + Math.clamp(delta, -105.0F, 105.0F) - delta;
            var result = simulation.tick(PlayerInput.fromMask(mask), Tri.TRUE, true, true);
            assertEquals(Set.of(), result.unmodeled(), "client tick " + tick);
            if (lerps)
                level.tickBorder();
            Movement movement = null;
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0)
                packets.add(PlayerLoaded.INSTANCE);
            for (InboundEvent packet : Wire.in(result.packets())) {
                packets.add(packet);
                if (packet instanceof Movement move)
                    movement = move;
            }
            var frame = new ClientTickFrame(tick, tick, tick, window, Wire.ofAll(packets), (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
            MovementCheckRunner.TickReport report = probe.frame(frame);
            String context = "" + type + " tick " + tick + " " + report;
            assertEquals(type.substring("minecraft:".length()), report.vehicle(), context);
            if (tick == 0) {
                assertEquals(MovementCheckRunner.Status.SKIPPED, report.status(), context);
                continue;
            }
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
            assertNotNull(report.reported(), context);
            if (lerps) {
                // an unknown phase is reported; what is not reported must match
                if (report.gaps().isEmpty())
                    assertEquals(0.0, report.offset(), context);
                else
                    assertTrue(report.gaps().equals(Set.of(ru.minecomplex.mac.engine.Unmodeled.WORLD_BORDER_CLOCK)), context);
                compared++;
                continue;
            }
            assertEquals(0.0, report.offset(), context);
            assertEquals("", report.note(), context);
            assertTrue(report.gaps().isEmpty(), context);
            compared++;
            reach = Math.max(reach, Math.max(Math.abs(state.body.position.x() - 8.5), Math.abs(state.body.position.z() - 8.5)) + dimensions.width() / 2.0);
        }
        assertEquals(lerps ? 109 : 59, compared);
        // the rounded interior of a border of size 4 around x/z 8.5 is 6..11
        if (border != null && !lerps)
            assertEquals(2.5, reach, 1.0E-6, "" + type + ": the boat did not reach the border");
        // the boat really moved on the ice
        if (border == null)
            assertTrue(Math.abs(state.body.position.x() - 8.5) + Math.abs(state.body.position.z() - 8.5) > 1.0, state.body.position.toString());
    }
}
