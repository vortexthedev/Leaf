package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

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
import ru.minecomplex.mac.engine.movementtick.LivingVehicleBodyPhysics;
import ru.minecomplex.mac.engine.movementtick.LivingVehiclePlayerRide;
import ru.minecomplex.mac.engine.movementtick.LivingVehiclePlayerTick;
import ru.minecomplex.mac.entity.LivingVehicleState;
import ru.minecomplex.mac.engine.movementtick.LivingVehicleTickPhysics;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.engine.movementtick.OtherVehicleRiding;
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
import ru.minecomplex.mac.fixture.outbound.EntityEquipment;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.PlayerInventorySlot;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;

/**
 * Wiring of the pig/strider/happy-ghast/nautilus ride in {@link MovementCheckRunner}: the controller facts (saddle as data or
 * equipment, the steering item in the hand, the harness, the tame flag) arrive as packets; a "client" made of the same
 * engines with those facts written out drives the mount, and the probe must follow its {@code move_vehicle} with zero
 * offset after the seeded tick. Not a vanilla test: the engines are verified by MALW.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LivingRideProbeTest {

    private static final int PLAYER = 7;

    private static final int MOUNT = 22;

    @Test
    void followsLivingMountsDrivenByTheSameEngine() {
        drive(OtherVehicleRiding.Kind.PIG, true);
        drive(OtherVehicleRiding.Kind.STRIDER, true);
        drive(OtherVehicleRiding.Kind.HAPPY_GHAST, true);
        drive(OtherVehicleRiding.Kind.NAUTILUS, true);
    }

    /**
     * The rider moves on its very first ride tick while the marker after the mount's {@code set_passengers} is still
     * unanswered: its {@code move_vehicle} proves the mount, the probe seeds the mount from that tick and follows it.
     */
    @Test
    void followsLivingMountsWhenTheMountMarkerIsLateAndTheRiderMovesAtOnce() {
        drive(OtherVehicleRiding.Kind.PIG, true, false, false, true);
        drive(OtherVehicleRiding.Kind.STRIDER, true, false, false, true);
        drive(OtherVehicleRiding.Kind.HAPPY_GHAST, true, false, false, true);
        drive(OtherVehicleRiding.Kind.NAUTILUS, true, false, false, true);
    }

    /**
     * The mount still moves when the player takes it (the server set its velocity, or a previous ride left it): the client
     * drives it on from that velocity, so the probe must seed it with the velocity the client kept, not a resting one.
     */
    @Test
    void startsFromTheMovementTheClientKeptForTheMount() {
        drive(OtherVehicleRiding.Kind.PIG, true, false, false, true, new Vec3d(0.21, 0.0, -0.17));
        drive(OtherVehicleRiding.Kind.STRIDER, true, false, false, true, new Vec3d(-0.12, 0.0, 0.19));
        {
            drive(OtherVehicleRiding.Kind.HAPPY_GHAST, true, false, false, true, new Vec3d(0.13, 0.07, -0.09));
        }
        {
            drive(OtherVehicleRiding.Kind.NAUTILUS, true, false, false, true, new Vec3d(-0.08, -0.05, 0.11));
        }
    }

    @Test
    void aPigWithoutTheSteeringItemIsLeftToTheServer() {
        drive(OtherVehicleRiding.Kind.PIG, false);
    }

    private static String key(OtherVehicleRiding.Kind kind) {
        return switch(kind) {
            case PIG ->
                "minecraft:pig";
            case STRIDER ->
                "minecraft:strider";
            case HAPPY_GHAST ->
                "minecraft:happy_ghast";
            case NAUTILUS ->
                "minecraft:nautilus";
            case ZOMBIE_NAUTILUS ->
                "minecraft:zombie_nautilus";
        };
    }

    /**
     * After the ride the server dismounts the player ({@code set_passengers}, then a teleport keeping velocity and
     * rotation) and it falls: the velocity its last ride tick left is the probe's, so the fall is followed exactly.
     */
    @Test
    void fallsAfterLeavingALivingMount() {
        drive(OtherVehicleRiding.Kind.PIG, true, true);
        drive(OtherVehicleRiding.Kind.PIG, true, true);
        drive(OtherVehicleRiding.Kind.HAPPY_GHAST, true, true);
    }

    /**
     * Leaving a happy ghast puts the player on its top centre ({@code HappyGhast.getDismountLocationForPassenger}); it then
     * jumps on the ghast, which collides with a player at or above its top.
     */
    @Test
    void jumpsOnAHappyGhastAfterLeavingIt() {
        drive(OtherVehicleRiding.Kind.HAPPY_GHAST, true, true, true);
    }

    private static void drive(OtherVehicleRiding.Kind kind, boolean steering) {
        drive(kind, steering, false);
    }

    private static void drive(OtherVehicleRiding.Kind kind, boolean steering, boolean leave) {
        drive(kind, steering, leave, false);
    }

    private static void drive(OtherVehicleRiding.Kind kind, boolean steering, boolean leave, boolean onTop) {
        drive(kind, steering, leave, onTop, false);
    }

    /**
     * @param lateMount the first ride tick moves at once and still sees the mount in flight
     */
    private static void drive(OtherVehicleRiding.Kind kind, boolean steering, boolean leave, boolean onTop, boolean lateMount) {
        drive(kind, steering, leave, onTop, lateMount, null);
    }

    /**
     * @param mountVelocity a {@code set_entity_motion} of the mount before the ride ({@code null}: none)
     */
    private static void drive(OtherVehicleRiding.Kind kind, boolean steering, boolean leave, boolean onTop, boolean lateMount, Vec3d mountVelocity) {
        BlockRegistry registry = BlockRegistry.get();
        WorldModel worldModel = new WorldModel(registry);
        PlayerModel playerModel = new PlayerModel();
        MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
        var types = EntityTypes.get();
        String type = key(kind);
        boolean ghast = kind == OtherVehicleRiding.Kind.HAPPY_GHAST;
        boolean nautilus = kind == OtherVehicleRiding.Kind.NAUTILUS;
        int stone = registry.block("minecraft:stone").firstStateId();
        int water = registry.block("minecraft:water").firstStateId();
        JoinGame join = new JoinGame(PLAYER, "minecraft:overworld", 0, 32, 8);
        worldModel.sent(0, Wire.of(join));
        playerModel.sent(0, Wire.of(join));
        long seq = 1;
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                // the nautilus swims: the second section is water
                worldModel.sent(seq++, Wire.of(new ChunkData(x, z, new int[] { 0, 0 }, new int[] { 1, 1 }, new int[] { stone, nautilus ? water : 0 }, new long[0], ChunkBlockEntities.encode(List.of()))));
            }
        }
        double startY = ghast ? 20.0 : nautilus ? 20.0 : 16.0;
        playerModel.sent(seq++, Wire.of(new PlayerPosition(1, 8.5, startY, 8.5, 0, 0, 0, 0.0F, 10.0F, 0)));
        String held = kind == OtherVehicleRiding.Kind.PIG ? "minecraft:carrot_on_a_stick" : "minecraft:warped_fungus_on_a_stick";
        if (steering && !ghast && !nautilus)
            playerModel.sent(seq++, Wire.of(PlayerInventorySlot.of(0, WireItem.of(held, 1))));
        List<OutboundEvent> mount = new ArrayList<>();
        mount.add(new EntitySpawn(MOUNT, type, 8.5, startY, 8.5, 0.0F, 0.0F, 0.0F, 0, false, 0, 0, 0));
        if (mountVelocity != null) {
            mount.add(new ru.minecomplex.mac.fixture.outbound.EntityVelocity(MOUNT, mountVelocity.x(), mountVelocity.y(), mountVelocity.z()));
        }
        var data = types;
        Map<Integer, Object> items = new HashMap<>();
        boolean equipment = true;
        if (!equipment && !ghast && !nautilus) {
            items.put(data.dataId((kind == OtherVehicleRiding.Kind.PIG ? "Pig." : "Strider.") + "DATA_SADDLE_ID"), true);
        }
        if (nautilus)
            items.put(data.dataId("TamableAnimal.DATA_FLAGS_ID"), (byte) 4);
        if (!items.isEmpty()) {
            mount.add(new EntitySyncedData(MOUNT, 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(items)));
        }
        if (equipment && !ghast)
            mount.add(EntityEquipment.of(MOUNT, WireItem.of("minecraft:saddle", 1), null));
        if (ghast)
            mount.add(EntityEquipment.of(MOUNT, null, WireItem.of("minecraft:white_harness", 1)));
        mount.add(new EntityPassengers(MOUNT, new int[] { PLAYER }));
        long last = seq;
        for (OutboundEvent event : mount) {
            last = seq++;
            worldModel.sent(last, Wire.of(event));
        }
        SyncWindow window = new SyncWindow(last, last + 1);
        PlayerModel.Hypothesis client = playerModel.start(new PlayerState(), new ru.minecomplex.mac.player.state.LocalPlayerState(), new ru.minecomplex.mac.player.state.ClientPlayerData(), -1);
        client.apply(window, last);
        CompensatedWorld level = worldModel.viewAt(window, last);
        PlayerState[] mountBody = new PlayerState[1], clientPlayer = new PlayerState[1];
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
                // the client's own happy ghast, once it no longer carries the player and the player is at or above its top
                if (!onTop || mountBody[0] == null || clientPlayer[0] == null || clientPlayer[0].passenger)
                    return List.of();
                Aabb top = mountBody[0].boundingBox(), search = box.inflate(1.0E-7);
                return clientPlayer[0].y() >= top.maxY() && top.intersects(search.minX(), search.minY(), search.minZ(), search.maxX(), search.maxY(), search.maxZ()) ? List.of(ru.minecomplex.mac.collision.Shapes.create(top)) : List.of();
            }

            @Override
            public VoxelShape borderShape(Aabb box) {
                return null;
            }
        };
        var rideable = types.rideable(type);
        var standing = types.livingDimensions(rideable, false, Pose.STANDING, 1.0F);
        var graph = new PassengerGraph();
        graph.spawn(PLAYER, true);
        graph.spawn(MOUNT);
        graph.startRiding(PLAYER, MOUNT, true);
        var body = new PlayerState();
        mountBody[0] = body;
        clientPlayer[0] = client.player();
        body.fixedBodyDimensions = true;
        body.bodyWidth = standing.width();
        body.bodyHeight = standing.height();
        body.position = new Vec3d(8.5, startY, 8.5);
        // the client's mob as its packets left it: spawned (not on the ground until a move packet says so) with the spawn
        // velocity, or the one set since
        body.onGround = false;
        body.deltaMovement = mountVelocity != null ? mountVelocity : Vec3d.ZERO;
        body.firstTick = false;
        body.attributes = AttributeMap.forEntity(types.type(type).defaultAttributes());
        var state = new LivingVehicleState(graph.get(MOUNT).generation(), kind, body);
        var bodyPhysics = new LivingVehicleBodyPhysics(env, body, kind, standing.eyeHeight(), rideable.pushedByFluid(), true, true, false, body.attributes.value(Attribute.FLYING_SPEED));
        var vehicleTick = new LivingVehicleTickPhysics(state, bodyPhysics);
        var facts = new LivingVehicleTickPhysics.Facts(true, nautilus, true, false, false, rideable.maxHeadYRot(), true, false, rideable.canFloat());
        var ride = new LivingVehiclePlayerRide(graph, PLAYER, MOUNT, state, vehicleTick.control(), () -> vehicleTick.controlFacts(facts), index -> standing.attachments().getClamped(EntityAttachment.PASSENGER, index, body.yRot), () -> 1.0F, () -> new VehiclePacketState(state.generation, Tri.TRUE, body.position, body.yRot, body.xRot, null, false, body.onGround), rider -> types.playerDimensions(rider.pose, rider.dimensionScale).attachments().get(EntityAttachment.VEHICLE, 0, rider.yRot));
        var simulation = new LivingVehiclePlayerTick(env, state, vehicleTick, ride, client.player(), client.local(), FixturePlayers.loaded());
        // standing first (the probe seeds the mount from that tick), then steering, turning, a jump (dash/ascend)
        int[] keys = { 0, 1, 1, 1 | 4, 1, 1 | 16, 1 | 16, 1 | 16, 1, 1, 1 | 8, 0, 0, 1, 2, 1 };
        int compared = 0;
        String name = type.substring("minecraft:".length());
        for (int tick = 0; tick < 60; tick++) {
            int mask = tick == 0 && !lateMount ? 0 : keys[(tick == 0 ? 1 : tick) % keys.length];
            if (mountVelocity != null && tick == 30) {
                // the server sets the driven mount's velocity (handleSetEntityMotion: lerpMotion): the client drives on from it
                Vec3d kick = new Vec3d(0.15, 0.2, -0.1);
                long set = seq++;
                worldModel.sent(set, Wire.of(new ru.minecomplex.mac.fixture.outbound.EntityVelocity(MOUNT, kick.x(), kick.y(), kick.z())));
                window = new SyncWindow(set, set + 1);
                body.deltaMovement = kick;
            }
            client.player().yRot = tick * 1.5F - 10.0F;
            client.player().xRot = ghast ? -12.0F : 5.0F;
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0)
                packets.add(PlayerLoaded.INSTANCE);
            if (steering) {
                assertEquals(Set.of(), simulation.simulateVehicleTick(facts), kind + " client tick " + tick);
                var result = simulation.simulatePassengerTick(PlayerInput.fromMask(mask));
                packets.addAll(Wire.in(result.packets()));
                assertEquals(Set.of(), result.unmodeled(), kind + " client tick " + tick);
                {
                    var late = simulation.sendAfterEntityTicks();
                    packets.addAll(Wire.in(late.packets()));
                    assertEquals(Set.of(), late.unmodeled(), kind + " client tick " + tick);
                }
            } else {
                packets.add(Movement.rot(client.player().yRot, client.player().xRot, false, false));
            }
            Movement movement = null;
            for (InboundEvent packet : packets) {
                if (packet instanceof Movement move)
                    movement = move;
            }
            var frame = new ClientTickFrame(tick, tick, tick, lateMount && tick == 0 ? new SyncWindow(last - 1, last + 1) : window, Wire.ofAll(packets), (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
            MovementCheckRunner.TickReport report = probe.frame(frame);
            String context = "" + kind + " tick " + tick + " " + report;
            assertEquals(name, report.vehicle(), context);
            if (tick == 0 || !steering) {
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
        if (!steering)
            return;
        assertEquals(59, compared);
        assertTrue(Math.abs(body.position.x() - 8.5) + Math.abs(body.position.z() - 8.5) > 0.5, kind + " " + body.position);
        if (!leave)
            return;
        var p = client.player();
        worldModel.sent(seq++, Wire.of(new EntityPassengers(MOUNT, new int[0])));
        graph.stopRiding(PLAYER);
        p.passenger = false;
        client.local().handsBusy = false;
        client.local().rootVehicle = null;
        int keep = PlayerPosition.RELATIVE_DELTA_X | PlayerPosition.RELATIVE_DELTA_Y | PlayerPosition.RELATIVE_DELTA_Z | PlayerPosition.RELATIVE_Y_ROT | PlayerPosition.RELATIVE_X_ROT;
        long teleport = seq++;
        playerModel.sent(teleport, Wire.of(onTop ? new PlayerPosition(2, body.x(), body.boundingBox().maxY(), body.z(), 0, 0, 0, 0.0F, 0.0F, keep) : new PlayerPosition(2, body.x() + 4.0, Math.max(body.y() + 3.0, 22.0), body.z(), 0, 0, 0, 0.0F, 0.0F, keep)));
        client.apply(new SyncWindow(teleport, teleport + 1), teleport);
        window = new SyncWindow(teleport, teleport + 1);
        double fallFrom = p.y();
        for (int tick = 60; tick < 80; tick++) {
            int mask = onTop ? (tick < 72 ? 16 : 16 | 1) : tick < 70 ? 1 : 0;
            p.yRot = 30.0F;
            p.xRot = 5.0F;
            var ticker = new MovementTickerPlayer(env, p, client.local(), FixturePlayers.loaded());
            ticker.tick(PlayerInput.fromMask(mask));
            Movement movement = null;
            for (InboundEvent packet : Wire.in(ticker.packets())) if (packet instanceof Movement move)
                movement = move;
            var report = probe.frame(new ClientTickFrame(tick, tick, tick, window, new ArrayList<>(ticker.packets()), (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of()));
            String context = "" + kind + " after leaving, tick " + tick + " " + report;
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
            assertEquals(0.0, report.offset(), context);
            assertTrue(report.gaps().isEmpty(), context);
        }
        if (!onTop)
            assertTrue(p.y() < fallFrom - 1.0, kind + " fell from " + fallFrom + " to " + p.y());
    }
}
