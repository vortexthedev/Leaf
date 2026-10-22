package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.*;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;

import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * MAVT uses real vanilla controllers and full ticks, including ticks without the steering item.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class VehicleTransitionOracleTest {

    @Test
    void transitions() throws Exception {
        replay(false);
    }

    @Test
    void lateMount() throws Exception {
        replay(true);
    }

    @Test
    void hotbarRegainDirectAndSession() throws Exception {
        replay(false, "vehicle-hotbar", false);
        replay(false, "vehicle-hotbar", true);
    }

    @Test
    void hotbarRegainAfterLateMount() throws Exception {
        replay(true, "vehicle-hotbar", true);
    }

    @Test
    void hotbarRegainRetainsAnUncertainVelocityEstimate() throws Exception {
        replay(false, "vehicle-hotbar", false, true);
    }

    @Test
    void hotbarRegainRetainsAnUncertainTransformEstimate() throws Exception {
        replay(false, "vehicle-hotbar", false, true, true);
        replay(false, "vehicle-hotbar", true, true, true);
    }

    @Test
    void firstMountRetainsUncertainStateWithSteeringItemAlreadyHeld() throws Exception {
        replay(false, "vehicle-hotbar", false, true, true, true);
        replay(false, "vehicle-hotbar", true, true, true, true);
    }

    @Test
    void steeringSelectionBeforeOrAfterPositionSync() throws Exception {
        replay(false, "vehicle-handler-orders", false);
        replay(false, "vehicle-handler-orders", true);
    }

    @Test
    void serverVelocityReplacementOnEveryDrivenVehicle() throws Exception {
        replay(false, "vehicle-velocity", false);
        replay(false, "vehicle-velocity", true);
    }

    private void replay(boolean late) throws Exception {
        replay(late, System.getProperty("mac.vehicle.fixture", "vehicle-transitions"), Boolean.getBoolean("mac.vehicle.session"));
    }

    private void replay(boolean late, String fixture, boolean sessionMode) throws Exception {
        replay(late, fixture, sessionMode, false);
    }

    private void replay(boolean late, String fixture, boolean sessionMode, boolean uncertainVelocity) throws Exception {
        replay(late, fixture, sessionMode, uncertainVelocity, false);
    }

    private void replay(boolean late, String fixture, boolean sessionMode, boolean uncertainVelocity, boolean uncertainTransform) throws Exception {
        replay(late, fixture, sessionMode, uncertainVelocity, uncertainTransform, false);
    }

    private void replay(boolean late, String fixture, boolean sessionMode, boolean uncertainVelocity, boolean uncertainTransform, boolean uncertainFirstMount) throws Exception {
        replay(late, fixture, sessionMode, uncertainVelocity, uncertainTransform, uncertainFirstMount, null);
    }

    void recordHotbarSessions(java.util.function.BiConsumer<ru.minecomplex.mac.replay.Recording, List<MovementCheckRunner.TickReport>> sink) throws Exception {
        replay(false, "vehicle-hotbar", true, false, false, false, sink);
    }

    private void replay(boolean late, String fixture, boolean sessionMode, boolean uncertainVelocity, boolean uncertainTransform, boolean uncertainFirstMount, java.util.function.BiConsumer<ru.minecomplex.mac.replay.Recording, List<MovementCheckRunner.TickReport>> sink) throws Exception {
        boolean hotbar = fixture.equals("vehicle-hotbar") || fixture.equals("vehicle-handler-orders") || fixture.equals("vehicle-handler-ground");
        var raw = getClass().getResourceAsStream("/vanilla/777/" + fixture + ".bin.gz");
        assertNotNull(raw, "26.3");
        var registry = BlockRegistry.get();
        var types = EntityTypes.get();
        var failures = new ArrayList<String>();
        int controlled = 0, foot = 0;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d415654, in.readInt());
            assertEquals(6, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int count = in.readInt();
            for (int s = 0; s < count; s++) {
                String type = in.readUTF();
                int scenario = in.readInt(), floor = in.readInt();
                String equipment = in.readUTF(), held = in.readUTF();
                var world = new WorldModel(registry);
                var player = new PlayerModel();
                var wire = new DismountVehicleProbeOracleTest.SessionWire(sessionMode);
                var recording = sink == null ? null : new ru.minecomplex.mac.replay.RecordingBuffer(0);
                if (recording != null)
                    wire.session.record(recording);
                var probe = wire.session == null ? new MovementCheckRunner(world, player, new SyncWindow(-1, 0)) : wire.session.probe();
                long seq = 0;
                var join = new JoinGame(1, "minecraft:overworld", 0, 64, 8);
                wire.world(world, seq, join);
                wire.player(player, seq++, join);
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) wire.world(world, seq++, new ChunkData(x, z, new int[] { 0, 0, 0, 0 }, new int[] { 1, 1, 1, 1 }, new int[] { floor, 0, 0, 0 }, new long[0], ChunkBlockEntities.encode(List.of())));
                wire.world(world, seq++, new EntitySpawn(2, type, 8.5, 16.0, 8.5, 0F, 0F, 0F, 0, false, 0, 0, 0));
                wire.world(world, seq++, new GameTime(0));
                var data = new HashMap<Integer, Object>();
                if (type.contains("horse") || type.equals("minecraft:donkey") || type.equals("minecraft:mule") || type.equals("minecraft:camel") || type.equals("minecraft:camel_husk")) {
                    data.put(types.dataId("DATA_ID_FLAGS"), (byte) (2));
                }
                if (type.contains("nautilus"))
                    data.put(types.dataId("TamableAnimal.DATA_FLAGS_ID"), (byte) 4);
                if (!data.isEmpty())
                    wire.world(world, seq++, new EntitySyncedData(2, 0, (byte) 0, false, "", (byte) 0, 0F, EntitySyncedData.encodeMobItems(data)));
                if (!equipment.isEmpty())
                    wire.world(world, seq++, EntityEquipment.of(2, type.equals("minecraft:happy_ghast") ? null : WireItem.of(equipment, 1), type.equals("minecraft:happy_ghast") ? WireItem.of(equipment, 1) : null));
                if (!held.isEmpty())
                    wire.player(player, seq++, PlayerInventorySlot.of(0, WireItem.of(held, 1)));
                wire.player(player, seq++, new PlayerPosition(1, 12.5, 16, 8.5, 0, 0, 0, 0F, 0F, 0));
                Vec3d lastMount = new Vec3d(8.5, 16, 8.5);
                int ticks = in.readInt();
                boolean broken = false;
                for (int t = 0; t < ticks; t++) {
                    if (in.readBoolean())
                        wire.world(world, seq++, new EntitySyncedData(2, 0, (byte) 0, false, "", (byte) 0, 0F, EntitySyncedData.encodeMobItems(java.util.Map.of(types.dataId("DATA_MOB_FLAGS_ID"), (byte) 0))));
                    if (in.readBoolean())
                        wire.world(world, seq++, new EntitySyncedData(2, 0, (byte) 0, false, "", (byte) 0, 0F, EntitySyncedData.encodeMobItems(java.util.Map.of(types.dataId(type.equals("minecraft:pig") ? "Pig.DATA_BOOST_TIME" : "Strider.DATA_BOOST_TIME"), 120))));
                    boolean remoteMove = in.readBoolean();
                    int change = in.readUnsignedByte(), announced = in.readUnsignedByte(), slot = in.readUnsignedByte(), mask = in.readUnsignedByte();
                    long mountSeq = -1;
                    if (change == 1) {
                        mountSeq = seq;
                        wire.world(world, seq++, new EntityPassengers(2, new int[] { 1 }));
                    }
                    if (change == 2) {
                        wire.world(world, seq++, new EntityPassengers(2, new int[0]));
                        int keep = PlayerPosition.RELATIVE_DELTA_X | PlayerPosition.RELATIVE_DELTA_Y | PlayerPosition.RELATIVE_DELTA_Z | PlayerPosition.RELATIVE_Y_ROT | PlayerPosition.RELATIVE_X_ROT;
                        wire.player(player, seq++, new PlayerPosition(2, lastMount.x() + 4, 19, lastMount.z(), 0, 0, 0, 0F, 0F, keep));
                    }
                    if (remoteMove && fixture.equals("vehicle-velocity")) {
                        wire.world(world, seq++, new EntityVelocity(2, t == 16 ? .125 : 0, 0, t == 16 ? -.25 : 0));
                        // A transport marker can follow any packet. Certify receipt of this handler
                        // even when the normal marker policy waits for the next server update.
                        if (wire.session != null) {
                            int marker = wire.session.allocateMarker(++wire.nanos);
                            Wire.send(wire.session, new Ping(marker), ++wire.nanos);
                            wire.markers.add(marker);
                        }
                    } else if (remoteMove && scenario >= 13)
                        wire.world(world, seq++, EntityPositionSync.linear(2, 8.85, 16.0, 8.375, 90F, -40F, scenario < 15));
                    else if (remoteMove && scenario >= 9)
                        wire.world(world, seq++, new EntityTeleport(2, 8.75 + t * 0.01, 16.0, 8.375, 0, 0, 0, 0F, 0F, 0, true));
                    else if (remoteMove)
                        wire.world(world, seq++, EntityMove.linear(2, true, false, (short) 128, (short) 0, (short) -64, (byte) 0, (byte) 0, true));
                    var packets = new ArrayList<InboundEvent>();
                    if (t == 0)
                        packets.add(PlayerLoaded.INSTANCE);
                    Movement move = null;
                    int n = in.readInt();
                    for (int p = 0; p < n; p++) {
                        var packet = LocalPlayerOracleCodec.packet(in);
                        packets.add(packet);
                        if (packet instanceof Movement m)
                            move = m;
                    }
                    Vec3d position = vec(in), velocity = vec(in);
                    lastMount = vec(in);
                    Vec3d mountVelocity = vec(in);
                    // Lose the certificate, not the vanilla-matching estimate: either before the first mount
                    // (steering item already held), or before losing/regaining control for three ticks.
                    if (uncertainVelocity && (scenario == 5 || scenario == 6) && t == (uncertainFirstMount ? 5 : 9))
                        probe.clientWorld().entities().motions().velocityLate(2);
                    if (uncertainTransform && (scenario == 5 || scenario == 6) && t == (uncertainFirstMount ? 5 : 12))
                        probe.clientWorld().entities().motions().uncertainController(2);
                    var window = new SyncWindow(late && mountSeq >= 0 ? mountSeq - 1 : seq - 1, seq);
                    var report = wire.session == null ? probe.frame(new ClientTickFrame(t, t, t, window, Wire.ofAll(packets), (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(move), Set.of())) : wire.tick(packets, late && change == 1);
                    String context = "26.3" + " " + type + " scenario " + scenario + " tick " + t + " announced " + announced + " slot " + slot + " " + report;
                    if (System.getProperty("mac.debug.mavt", "").equals(scenario + "") && type.equals("minecraft:pig")) {
                        var state = probe.clientWorld().entities().livingState(2);
                        System.out.println(context + " root velocity " + mountVelocity + " simulated " + (state == null ? "none" : state.body.deltaMovement + " boost " + state.control.boostTime));
                    }
                    if (!broken && report.status() == MovementCheckRunner.Status.COMPARED && report.gaps().isEmpty() && report.inFlight().isEmpty()) {
                        VehicleMovement sentVehicle = null;
                        for (var packet : packets) if (packet instanceof VehicleMovement v)
                            sentVehicle = v;
                        // An interpolating vehicle sends its target, not its current physical position.
                        Vec3d expected = report.vehicle() == null ? position : sentVehicle == null ? lastMount : new Vec3d(sentVehicle.x(), sentVehicle.y(), sentVehicle.z());
                        if (!sameBits(expected, report.predicted())) {
                            failures.add(context + " expected " + expected);
                            broken = true;
                        }
                        if (!broken && report.vehicle() != null) {
                            Vec3d tracked = probe.clientWorld().entities().motions().position(2);
                            if (!sameBits(lastMount, tracked)) {
                                failures.add(context + " physical root " + lastMount + " actual " + tracked);
                                broken = true;
                            }
                        }
                    }
                    if (!broken && t >= 42 && (!hotbar || t < 56) && report.gaps().isEmpty()) {
                        foot++;
                    }
                    if (!uncertainVelocity && !broken && type.equals("minecraft:pig") && scenario != 4 && t >= 42 && (!hotbar || t < 56)) {
                        assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
                        assertTrue(report.gaps().isEmpty(), context);
                        assertTrue(report.inFlight().isEmpty(), context);
                        assertEquals(Double.doubleToLongBits(position.x()), Double.doubleToLongBits(report.predicted().x()), context);
                        assertEquals(Double.doubleToLongBits(position.y()), Double.doubleToLongBits(report.predicted().y()), context);
                        assertEquals(Double.doubleToLongBits(position.z()), Double.doubleToLongBits(report.predicted().z()), context);
                    }
                    if (fixture.equals("vehicle-velocity") && t > 6 && t < 32) {
                        var body = probe.clientWorld().entities().boatState(2);
                        var horse = probe.clientWorld().entities().horseState(2);
                        var living = probe.clientWorld().entities().livingState(2);
                        Vec3d actual = body != null ? body.body.deltaMovement : horse != null ? horse.body.deltaMovement : living.body.deltaMovement;
                        assertTrue(sameBits(mountVelocity, actual), context + " velocity expected " + mountVelocity + " actual " + actual);
                    }
                    if (scenario == 0 && t > 6 && t < 32) {
                        assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
                        assertTrue(report.gaps().isEmpty(), context);
                        assertTrue(report.inFlight().isEmpty(), context);
                    }
                    if (type.equals("minecraft:pig") && (scenario == 1 || scenario == 3) && t >= 8 && t < 32 && slot == 0) {
                        assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
                        assertTrue(report.gaps().isEmpty(), context);
                        assertTrue(sameBits(lastMount, report.predicted()), context + " expected " + lastMount);
                    }
                    if (!broken && t > 7 && t < 32 && report.status() == MovementCheckRunner.Status.COMPARED && report.gaps().isEmpty())
                        controlled++;
                    if (hotbar && !uncertainVelocity && (t >= 8 && t < 32 && slot == 0 || t >= 57)) {
                        assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
                        assertTrue(report.gaps().isEmpty(), context);
                        assertTrue(report.inFlight().isEmpty(), context);
                    }
                    if (uncertainVelocity && (scenario == 5 || scenario == 6) && t >= (uncertainFirstMount ? 7 : 13) && t < 32 && slot == 0) {
                        assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
                        assertTrue(report.gaps().contains(Unmodeled.RIDING), context);
                        assertTrue(sameBits(lastMount, report.predicted()), context + " expected " + lastMount);
                    }
                }
                if (recording != null)
                    sink.accept(recording.snapshot("26.3" + " " + type + " scenario " + scenario), List.copyOf(wire.reports));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(controlled > (hotbar ? 20 : 100), "controlled ticks exercised: " + controlled);
        assertTrue(foot > (hotbar ? 40 : 100), "dismount ticks exercised: " + foot);
    }

    private static Vec3d vec(DataInputStream in) throws Exception {
        return new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
    }

    private static boolean sameBits(Vec3d expected, Vec3d actual) {
        return actual != null && Double.doubleToLongBits(expected.x()) == Double.doubleToLongBits(actual.x()) && Double.doubleToLongBits(expected.y()) == Double.doubleToLongBits(actual.y()) && Double.doubleToLongBits(expected.z()) == Double.doubleToLongBits(actual.z());
    }
}
