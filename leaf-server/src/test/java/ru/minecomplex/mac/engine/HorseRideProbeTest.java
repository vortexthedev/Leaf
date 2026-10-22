package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.engine.movementtick.HorseOracle;
import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.entity.EntityAttachment;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.entity.attribute.AttributeMap;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.fixture.WireItem;

import ru.minecomplex.mac.engine.movementtick.HorsePassengerPhysics;
import ru.minecomplex.mac.engine.movementtick.HorsePlayerRide;
import ru.minecomplex.mac.engine.movementtick.HorsePlayerTick;

import ru.minecomplex.mac.entity.HorseState;

import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.engine.movementtick.VehiclePacketState;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EntityAttributes;
import ru.minecomplex.mac.fixture.outbound.EntityEquipment;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.fixture.outbound.GameTime;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;

/**
 * Wiring of the horse ride in {@link MovementCheckRunner}: saddle equipment, the tame flag,
 * attributes and level time arrive as packets; a "client" made of the same engines with those values written out
 * drives the horse, and the probe must follow its {@code move_vehicle} with zero offset after the seeded tick. Not a
 * vanilla test: the engines are verified by their oracles.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class HorseRideProbeTest {

    private static final int PLAYER = 7;

    private static final int HORSE = 21;

    @Test
    void followsAHorseDrivenByTheSameEngine() {
        drive("minecraft:horse", HorseState.Kind.HORSE, true);
        drive("minecraft:camel", HorseState.Kind.CAMEL, true);
    }

    /**
     * The rider moves on its very first ride tick while the marker after the mount's {@code set_passengers} is still
     * unanswered: its {@code move_vehicle} proves the mount, the probe seeds the mount from that tick and follows it.
     */
    @Test
    void followsAHorseWhenTheMountMarkerIsLateAndTheRiderMovesAtOnce() {
        drive("minecraft:horse", HorseState.Kind.HORSE, true, true);
        drive("minecraft:camel", HorseState.Kind.CAMEL, true, true);
    }

    @Test
    void anUnsaddledHorseIsLeftToTheServer() {
        drive("minecraft:horse", HorseState.Kind.HORSE, false);
    }

    /**
     * A horse still moving when the player takes it: the client drives it on from the velocity it kept.
     */
    @Test
    void startsFromTheMovementTheClientKeptForTheHorse() {
        drive("minecraft:horse", HorseState.Kind.HORSE, true, true, new Vec3d(0.23, 0.0, 0.16));
        drive("minecraft:camel", HorseState.Kind.CAMEL, true, true, new Vec3d(-0.14, 0.0, 0.2));
    }

    private static void drive(String type, HorseState.Kind kind, boolean saddled) {
        drive(type, kind, saddled, false);
    }

    /**
     * @param lateMount the first ride tick moves at once and still sees the mount in flight
     */
    private static void drive(String type, HorseState.Kind kind, boolean saddled, boolean lateMount) {
        drive(type, kind, saddled, lateMount, null);
    }

    /**
     * @param horseVelocity a {@code set_entity_motion} of the horse before the ride ({@code null}: none)
     */
    private static void drive(String type, HorseState.Kind kind, boolean saddled, boolean lateMount, Vec3d horseVelocity) {
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
        boolean camel = kind == HorseState.Kind.CAMEL;
        int stone = registry.block("minecraft:stone").firstStateId();
        JoinGame join = new JoinGame(PLAYER, "minecraft:overworld", 0, 32, 8);
        worldModel.sent(0, Wire.of(join));
        playerModel.sent(0, Wire.of(join));
        long seq = 1;
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                worldModel.sent(seq++, Wire.of(new ChunkData(x, z, new int[] { 0, 0 }, new int[] { 1, 1 }, new int[] { stone, 0 }, new long[0], ChunkBlockEntities.encode(List.of()))));
            }
        }
        playerModel.sent(seq++, Wire.of(new PlayerPosition(1, 8.5, 16.0, 8.5, 0, 0, 0, 0.0F, 10.0F, 0)));
        List<OutboundEvent> horse = new ArrayList<>();
        horse.add(new EntitySpawn(HORSE, type, 8.5, 16.0, 8.5, 0.0F, 0.0F, 0.0F, 0, false, 0, 0, 0));
        if (horseVelocity != null) {
            horse.add(new ru.minecomplex.mac.fixture.outbound.EntityVelocity(HORSE, horseVelocity.x(), horseVelocity.y(), horseVelocity.z()));
        }
        int shift = 1;
        boolean equipment = true;
        byte flags = (byte) (2 | (saddled && !equipment ? 4 : 0));
        Map<Integer, Object> items = new HashMap<>();
        items.put(17 + shift, flags);
        if (camel)
            items.put(19 + shift, 100L);
        horse.add(new EntitySyncedData(HORSE, 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(items)));
        if (equipment && saddled)
            horse.add(EntityEquipment.of(HORSE, WireItem.of("minecraft:saddle", 1), null));
        double speed = camel ? 0.09 : 0.2875, jump = camel ? 0.42 : 0.81;
        var snapshots = List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:movement_speed", speed, List.of()), new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:jump_strength", jump, List.of()));
        horse.add(EntityAttributes.of(HORSE, snapshots));
        horse.add(new GameTime(5000L));
        horse.add(new EntityPassengers(HORSE, new int[] { PLAYER }));
        long last = seq;
        for (OutboundEvent event : horse) {
            last = seq++;
            worldModel.sent(last, Wire.of(event));
        }
        SyncWindow window = new SyncWindow(last, last + 1);
        PlayerModel.Hypothesis client = playerModel.start(new PlayerState(), new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(window, last);
        CompensatedWorld level = worldModel.viewAt(window, last);
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
        var types = EntityTypes.get();
        var rideable = types.rideable(type);
        var standing = types.livingDimensions(rideable, false, Pose.STANDING, 1.0F);
        var graph = new PassengerGraph();
        graph.spawn(PLAYER, true);
        graph.spawn(HORSE);
        graph.startRiding(PLAYER, HORSE, true);
        var body = new PlayerState();
        body.fixedBodyDimensions = true;
        body.bodyWidth = standing.width();
        body.bodyHeight = standing.height();
        body.position = new Vec3d(8.5, 16.0, 8.5);
        // the client's horse as its packets left it: spawned (not on the ground until a move packet says so) with the spawn
        // velocity, or the one set since
        body.onGround = false;
        body.deltaMovement = horseVelocity != null ? horseVelocity : Vec3d.ZERO;
        body.firstTick = false;
        body.attributes = AttributeMap.forEntity(types.type(type).defaultAttributes());
        body.attributes.get(Attribute.MOVEMENT_SPEED).setBaseValue(speed);
        body.attributes.get(Attribute.JUMP_STRENGTH).setBaseValue(jump);
        var state = new HorseState(graph.get(HORSE).generation(), kind, body);
        state.lastPoseChangeTick = camel ? 100L : 0L;
        var bodyPhysics = HorseOracle.body(env, state, standing.eyeHeight(), rideable.pushedByFluid(), true, true, false, new HorseOracle.Dimensions(standing.width(), standing.height(), standing.eyeHeight()));
        long[] time = { 5000L };
        boolean effectiveAi = true;
        java.util.function.Supplier<HorseOracle.Control> control = () -> new HorseOracle.Control(kind, true, true, effectiveAi, body.onGround, !camel, time[0], speed, jump, HorseOracle.blockJumpFactor(bodyPhysics), HorseOracle.blockSpeedFactor(bodyPhysics), 0.0F);
        java.util.function.Supplier<HorsePassengerPhysics.Facts> seat = () -> new HorsePassengerPhysics.Facts(kind, body.position, body.yRot, state.bodyYaw, body.height(), 1.0F, false, false, 0, 1, false, time[0], state.lastPoseChangeTick, state.oldStandAnimation, standing.attachments().getClamped(EntityAttachment.PASSENGER, 0, body.yRot));
        var ride = new HorsePlayerRide(graph, PLAYER, HORSE, bodyPhysics, seat, () -> new VehiclePacketState(state.generation, Tri.TRUE, body.position, body.yRot, body.xRot, null, false, body.onGround), rider -> types.playerDimensions(rider.pose, rider.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, rider.yRot));
        var simulation = new HorsePlayerTick(env, bodyPhysics, ride, client.player(), client.local(), FixturePlayers.loaded());
        // standing first (the probe seeds the horse from that tick), then walking, turning, a charged jump
        int[] keys = { 0, 1, 1, 1 | 4, 1, 1 | 16, 1 | 16, 1 | 16, 1, 1, 1 | 8, 0, 0, 1 | 64, 1 | 64, 1 };
        int compared = 0;
        for (int tick = 0; tick < 60; tick++) {
            int mask = tick == 0 && !lateMount ? 0 : keys[(tick == 0 ? 1 : tick) % keys.length];
            if (horseVelocity != null && tick == 30) {
                // the server sets the driven horse's velocity (handleSetEntityMotion: lerpMotion): the client drives on from it
                Vec3d kick = new Vec3d(0.15, 0.2, -0.1);
                long set = seq++;
                worldModel.sent(set, Wire.of(new ru.minecomplex.mac.fixture.outbound.EntityVelocity(HORSE, kick.x(), kick.y(), kick.z())));
                window = new SyncWindow(set, set + 1);
                body.deltaMovement = kick;
            }
            client.player().yRot = tick * 1.5F - 10.0F;
            client.player().xRot = 5.0F;
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0)
                packets.add(PlayerLoaded.INSTANCE);
            if (saddled) {
                assertEquals(Set.of(), HorseOracle.simulate(simulation,state,control.get(),rideable.maxHeadYRot(),true,false,rideable.canFloat()), "client tick " + tick);
                var result = simulation.simulatePassengerTick(PlayerInput.fromMask(mask));
                packets.addAll(Wire.in(result.packets()));
                assertEquals(Set.of(), result.unmodeled(), "client tick " + tick);
                {
                    var late = simulation.sendAfterEntityTicks();
                    packets.addAll(Wire.in(late.packets()));
                    assertEquals(Set.of(), late.unmodeled(), "client tick " + tick);
                }
            } else {
                packets.add(Movement.rot(client.player().yRot, client.player().xRot, false, false));
            }
            time[0]++;
            Movement movement = null;
            for (InboundEvent packet : packets) {
                if (packet instanceof Movement move)
                    movement = move;
            }
            var frame = new ClientTickFrame(tick, tick, tick, lateMount && tick == 0 ? new SyncWindow(last - 1, last + 1) : window, Wire.ofAll(packets), (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
            MovementCheckRunner.TickReport report = probe.frame(frame);
            String context = "" + type + " tick " + tick + " " + report;
            assertEquals(type.substring("minecraft:".length()), report.vehicle(), context);
            if (tick == 0 || !saddled) {
                assertEquals(MovementCheckRunner.Status.SKIPPED, report.status(), context);
                continue;
            }
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
            assertNotNull(report.reported(), context);
            assertEquals(0.0, report.offset(), context);
            assertEquals("", report.note(), context);
            assertTrue(report.gaps().isEmpty(), context);
            compared++;
        }
        if (!saddled)
            return;
        assertEquals(59, compared);
        assertTrue(Math.abs(body.position.x() - 8.5) + Math.abs(body.position.z() - 8.5) > 1.0, body.position.toString());
    }
}
