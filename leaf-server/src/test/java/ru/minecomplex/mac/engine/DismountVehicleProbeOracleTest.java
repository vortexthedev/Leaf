package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EntityMove;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import ru.minecomplex.mac.fixture.outbound.EntityPositionSync;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;

import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * MADV v3 through the movement probe: the wire events and the client's tick packets (with those it sends while handling
 * the server's, such as {@code accept_teleportation}) of a real client that rides a boat or raft, is dismounted by the
 * server and at once walks into the boat, along it or jumps while the server moves the boat on; or rides a happy ghast,
 * is dismounted while it moves, stands on it while the server syncs it where it stopped, is seated again and drives at
 * once (the ghast starts from the movement its client kept); or, dismounted onto the vehicle's top, is seated and
 * dismounted again and again, then stands and walks on it. Every tick the probe compares must match the client's
 * position exactly (the first tick of each ride is seeded from the client), with the boat's collision box and its pushes
 * in the remote boat's tick.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class DismountVehicleProbeOracleTest {

    static final int PLAYER = 1;

    /**
     * Real marker timeline and frame assembler; no hand-built SyncWindow or injected tick input.
     */
    static final class SessionWire {

        final ru.minecomplex.mac.player.MacPlayer session;

        final List<Integer> markers = new ArrayList<>();

        List<Integer> delayed = List.of();

        final List<MovementCheckRunner.TickReport> reports = new ArrayList<>();

        long nanos, lastSeq = Long.MIN_VALUE;

        boolean first = true;

        SessionWire(boolean enabled) {
            session = enabled ? new ru.minecomplex.mac.player.MacPlayer(new ru.minecomplex.mac.frame.FrameListener() {

                public void betweenTicks(ru.minecomplex.mac.frame.BetweenTicks event) {
                }

                public void frame(ClientTickFrame frame) {
                }
            }) : null;
            if (session != null)
                session.probeListener(reports::add);
        }

        void world(WorldModel world, long seq, ru.minecomplex.mac.fixture.outbound.OutboundEvent event) {
            if (session == null) {
                var change = Wire.of(event, Wire.FACTS);
                if (WorldModel.isWorldChange((Object) change))
                    world.sent(seq, change);
            } else
                send(seq, event);
        }

        void player(PlayerModel player, long seq, ru.minecomplex.mac.fixture.outbound.OutboundEvent event) {
            if (session == null)
                player.sent(seq, Wire.of(event, Wire.FACTS));
            else
                send(seq, event);
        }

        void send(long seq, ru.minecomplex.mac.fixture.outbound.OutboundEvent event) {
            // the initial login belongs to both models but occurs once on the wire
            if (seq == lastSeq)
                return;
            lastSeq = seq;
            // would quantize them on the wire, so that packet enters the session decoded, not as bytes.
            boolean markerAfter = event instanceof ru.minecomplex.mac.fixture.outbound.EntityVelocity ? session.outbound(Wire.of(event, Wire.FACTS), ++nanos) : Wire.send(session, event, ++nanos);
            if (markerAfter) {
                int marker = session.allocateMarker(++nanos);
                Wire.send(session, new ru.minecomplex.mac.fixture.outbound.Ping(marker), ++nanos);
                markers.add(marker);
            }
        }

        void ack(List<Integer> ids) {
            for (int marker : ids) Wire.receive(session, new ru.minecomplex.mac.fixture.inbound.Pong(marker), ++nanos);
        }

        MovementCheckRunner.TickReport tick(List<InboundEvent> packets, boolean late) {
            ack(delayed);
            delayed = List.of();
            int i = 0;
            // handleMovePlayer's responses precede the tick and the pong of the marker after the teleport.
            while (i < packets.size()) {
                if (packets.get(i) instanceof PlayerLoaded) {
                    i++;
                    continue;
                }
                if (!(packets.get(i) instanceof ru.minecomplex.mac.fixture.inbound.TeleportAccept))
                    break;
                Wire.receive(session, packets.get(i++), ++nanos);
            }
            if (late && !first && !markers.isEmpty()) {
                // Reaching a later change also reaches every marker preceding it; only the final marker may lag.
                ack(markers.subList(0, markers.size() - 1));
                delayed = List.of(markers.getLast());
            } else
                ack(markers);
            markers.clear();
            first = false;
            for (; i < packets.size(); i++) Wire.receive(session, packets.get(i), ++nanos);
            int count = reports.size();
            Wire.receive(session, ru.minecomplex.mac.fixture.inbound.TickEnd.INSTANCE, ++nanos);
            assertEquals(null, session.probeFailure());
            assertEquals(count + 1, reports.size());
            return reports.getLast();
        }
    }

    @Test
    void probeFollowsTheClient() throws Exception {
        replay(Late.NONE);
    }

    @Test
    void livingMountsMoveImmediatelyAfterDismount() throws Exception {
        replay(Late.NONE, true);
    }

    @Test
    void livingMountsMoveImmediatelyWithLateMarkers() throws Exception {
        replay(Late.CHANGES, true);
    }

    @Test
    void unsaddledHorsesMoveImmediatelyAfterDismount() throws Exception {
        replay(Late.NONE, true, true);
    }

    @Test
    void unsaddledHorsesMoveImmediatelyWithLateMarkers() throws Exception {
        replay(Late.CHANGES, true, true);
    }

    @Test
    void unsaddledDismountThroughTheSession() throws Exception {
        replay(Late.NONE, true, true, true);
    }

    @Test
    void unsaddledDismountThroughTheSessionWithLateMarkers() throws Exception {
        replay(Late.CHANGES, true, true, true);
    }

    /**
     * The same sessions with the marker after every seating, dismount and teleport answered one frame late (the client
     * handled them in the frame before their marker reached it): a tick may be uncertain then, but no tick may differ
     * from the client without saying so.
     */
    @Test
    void probeNeverDiffersSilentlyWhenMarkersAreLate() throws Exception {
        replay(Late.CHANGES);
    }

    /**
     * Which markers come a frame after the client handled what they follow.
     */
    enum Late {

        NONE, MOUNT, CHANGES
    }

    /**
     * The same sessions with the marker after the mount's {@code set_passengers} answered only after the first ride tick
     * (the client handled the mount in the frame before its marker reached it): the client's {@code move_vehicle} of that
     * tick proves the mount, and the probe must follow exactly as when the marker came at once.
     */
    @Test
    void probeFollowsTheClientWhenTheMountMarkerIsLate() throws Exception {
        replay(Late.MOUNT);
    }

    private void replay(Late late) throws Exception {
        replay(late, false);
    }

    private void replay(Late late, boolean living) throws Exception {
        replay(late, living, false);
    }

    private void replay(Late late, boolean living, boolean unsaddled) throws Exception {
        replay(late, living, unsaddled, false);
    }

    private void replay(Late late, boolean living, boolean unsaddled, boolean session) throws Exception {
        boolean lateMount = late == Late.MOUNT;

        var raw = getClass().getResourceAsStream("/vanilla/777" + (unsaddled ? "/dismount-unsaddled" : living ? "/dismount-living" : "/dismount-vehicles") + ".bin.gz");
        assertNotNull(raw, "26.3");
        var registry = BlockRegistry.get();
        var failures = new ArrayList<String>();
        long compared = 0, uncertain = 0;
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            assertEquals(0x4d414456, in.readInt());
            assertEquals(3, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int scenarios = in.readInt();
            for (int scenario = 0; scenario < scenarios; scenario++) {
                int steps = in.readInt();
                int vehicleKind = in.readUnsignedByte();
                String vehicleName = living ? " living mount " + vehicleKind : new String[] { " boat", " raft", " happy ghast" }[vehicleKind];
                int floor = in.readInt();
                WorldModel worldModel = new WorldModel(registry);
                PlayerModel playerModel = new PlayerModel();
                var wire = new SessionWire(session);
                MovementCheckRunner probe = session ? wire.session.probe() : new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
                wire.world(worldModel, 0, new JoinGame(PLAYER, "minecraft:overworld", 0, 64, 8));
                wire.player(playerModel, 0, new JoinGame(PLAYER, "minecraft:overworld", 0, 64, 8));
                long seq = 1;
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        wire.world(worldModel, seq++, new ChunkData(x, z, new int[] { 0, 0, 0, 0 }, new int[] { 1, 1, 1, 1 }, new int[] { floor, 0, 0, 0 }, new long[0], ChunkBlockEntities.encode(List.of())));
                    }
                }
                long frame = 0;
                boolean seeded = false;
                // the mount's set_passengers (the first one) and whether its marker is still unanswered
                long mountSeq = -1;
                boolean reseat = false;
                // Late.CHANGES: the first passengers or position change since the last tick, still unanswered at the next
                long pendingSince = -1;
                // the last teleport since the last tick: the client accepts it in this frame (handleMovePlayer), which
                // acknowledges it and everything before it like a pong
                long acceptedThrough = -1;
                String failure = null;
                for (int step = 0; step < steps; step++) {
                    int kind = in.readUnsignedByte();
                    switch(kind) {
                        case 1 ->
                            {
                                String type = in.readUTF();
                                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                                int packed = in.readUnsignedByte();
                                long most = in.readLong(), least = in.readLong();
                                // native angle: the packed byte over 256/360
                                float yaw = (byte) packed / (256.0F / 360.0F);
                                wire.world(worldModel, seq++, new EntitySpawn(2, type, x, y, z, yaw, 0.0F, yaw, 0, false, 0.0, 0.0, 0.0, most, least));
                            }
                        case 2 ->
                            {
                                int vehicle = in.readInt();
                                int[] passengers = new int[in.readInt()];
                                for (int i = 0; i < passengers.length; i++) passengers[i] = in.readInt();
                                if (mountSeq < 0 && passengers.length > 0)
                                    mountSeq = seq;
                                // a later seating: its first ride tick is seeded again
                                if (passengers.length > 0 && seeded)
                                    reseat = true;
                                if (pendingSince < 0)
                                    pendingSince = seq;
                                wire.world(worldModel, seq++, new EntityPassengers(vehicle, passengers));
                            }
                        case 3 ->
                            {
                                int id = in.readInt();
                                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                                float yRot = in.readFloat(), xRot = in.readFloat();
                                int relatives = in.readInt();
                                if (pendingSince < 0 && frame > 0)
                                    pendingSince = seq;
                                acceptedThrough = seq;
                                wire.player(playerModel, seq++, new PlayerPosition(id, x, y, z, 0.0, 0.0, 0.0, yRot, xRot, relatives));
                            }
                        case 4 ->
                            wire.world(worldModel, seq++, EntityMove.linear(in.readInt(), true, false, in.readShort(), in.readShort(), in.readShort(), (byte) 0, (byte) 0, in.readBoolean()));
                        case 5 ->
                            wire.world(worldModel, seq++, EntityPositionSync.linear(in.readInt(), in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), in.readBoolean()));
                        case 6 ->
                            wire.world(worldModel, seq++, ru.minecomplex.mac.fixture.outbound.EntityEquipment.of(in.readInt(), null, ru.minecomplex.mac.fixture.WireItem.of(in.readUTF(), 1)));
                        case 7 ->
                            {
                                var items = new java.util.TreeMap<Integer, Object>();
                                for (int n = in.readInt(); n > 0; n--) {
                                    int id = in.readInt();
                                    Object value = switch(in.readUnsignedByte()) {
                                        case 0 ->
                                            in.readByte();
                                        case 1 ->
                                            in.readBoolean();
                                        case 2 ->
                                            in.readInt();
                                        case 3 ->
                                            in.readLong();
                                        case 4 ->
                                            in.readFloat();
                                        default ->
                                            throw new IOException("mob item type");
                                    };
                                    items.put(id, value);
                                }
                                wire.world(worldModel, seq++, new ru.minecomplex.mac.fixture.outbound.EntitySyncedData(2, 0, (byte) 0, false, "", (byte) 0, 0F, ru.minecomplex.mac.fixture.outbound.EntitySyncedData.encodeMobItems(items)));
                            }
                        case 8 ->
                            {
                                var attributes = new ArrayList<ru.minecomplex.mac.entity.attribute.AttributeSnapshot>();
                                for (int n = in.readInt(); n > 0; n--) {
                                    String name = in.readUTF();
                                    if (in.readBoolean())
                                        attributes.add(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot(name, in.readDouble(), List.of()));
                                }
                                wire.world(worldModel, seq++, ru.minecomplex.mac.fixture.outbound.EntityAttributes.of(2, attributes));
                            }
                        case 9 ->
                            wire.world(worldModel, seq++, ru.minecomplex.mac.fixture.outbound.EntityEquipment.of(2, ru.minecomplex.mac.fixture.WireItem.of(in.readUTF(), 1), null));
                        case 11 ->
                            wire.player(playerModel, seq++, ru.minecomplex.mac.fixture.outbound.PlayerInventorySlot.of(0, ru.minecomplex.mac.fixture.WireItem.of(in.readUTF(), 1)));
                        case 12 ->
                            wire.world(worldModel, seq++, new ru.minecomplex.mac.fixture.outbound.GameTime(in.readLong()));
                        case 10 ->
                            {
                                int mask = in.readUnsignedByte();
                                float tickYaw = in.readFloat();
                                float tickPitch = in.readFloat();
                                var packets = new ArrayList<InboundEvent>();
                                if (frame == 0)
                                    packets.add(PlayerLoaded.INSTANCE);
                                Movement movement = null;
                                int count = in.readInt();
                                for (int p = 0; p < count; p++) {
                                    InboundEvent packet = LocalPlayerOracleCodec.packet(in);
                                    packets.add(packet);
                                    if (packet instanceof Movement move)
                                        movement = move;
                                }
                                Vec3d vanilla = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                                Vec3d vanillaVelocity = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                                Vec3d vanillaBoat = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                                // late: the first ride tick still sees the mount in flight
                                var window = lateMount && !seeded && mountSeq >= 0 ? new SyncWindow(mountSeq - 1, seq) : late == Late.CHANGES && pendingSince >= 0 ? new SyncWindow(Math.max(pendingSince - 1, acceptedThrough), seq) : new SyncWindow(seq - 1, seq);
                                pendingSince = -1;
                                acceptedThrough = -1;
                                var tickFrame = new ClientTickFrame(frame, frame, frame, window, Wire.ofAll(packets), (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
                                if (System.getProperty("mac.debug.madv", "").equals(scenario + ""))
                                    System.out.println("TESTFRAME " + frame + " window " + window);
                                MovementCheckRunner.TickReport report = session ? wire.tick(packets, late == Late.CHANGES) : probe.frame(tickFrame);
                                if (System.getProperty("mac.debug.madv", "").equals(scenario + "")) {
                                    var motions = probe.clientWorld().entities().motions();
                                    System.out.println("DEBUG frame " + frame + " mask " + mask + " yaw " + tickYaw + " pitch " + tickPitch + " packets " + packets + " boat model " + motions.describe(2) + " vanilla boat " + vanillaBoat + " player " + vanilla + " report " + report);
                                }
                                String context = "26.3" + " scenario " + scenario + vehicleName + " frame " + frame + ": " + report + " vanilla " + vanilla + " v " + vanillaVelocity + " boat " + vanillaBoat;
                                frame++;
                                if (failure != null)
                                    continue;
                                if (unsaddled && frame <= 12) {
                                    if (frame == 1 && late == Late.CHANGES && report.status() != MovementCheckRunner.Status.SKIPPED) {
                                        assertTrue(report.inFlight().contains("ClientboundSetPassengersPacket"), context);
                                    } else
                                        assertEquals(MovementCheckRunner.Status.SKIPPED, report.status(), context);
                                    continue;
                                }
                                if (late == Late.CHANGES && report.status() == MovementCheckRunner.Status.COMPARED) {
                                    compared++;
                                    if (!report.gaps().isEmpty() || !report.inFlight().isEmpty())
                                        uncertain++;
                                }
                                if (late == Late.CHANGES) {
                                    // the client's own packets prove what a late marker does not: the vehicle stays followed
                                    var motions = probe.clientWorld().entities().motions();
                                    if (motions.unknown(2))
                                        failure = "vehicle unknown: " + context;
                                    // once its interpolation ended, so did the client's: its phase is known again (a ride's
                                    // estimate only after the server's next move)
                                    boolean[] phase = motions.phase(2);
                                    if (phase[0] && phase[1] && !phase[2])
                                        failure = "phase uncertain at rest: " + context;
                                    // a tick that matched the client bit for bit proves the velocity (no sneaking at an edge,
                                    // no bouncy floor): the uncertainty an earlier tick left must not outlive it
                                    String floorName = registry.block(floor).name();
                                    boolean bouncy = floorName.equals("minecraft:slime_block") || floorName.endsWith("_bed");
                                    if (report.status() == MovementCheckRunner.Status.COMPARED && report.offset() == 0.0 && report.reported() != null && (mask & 32) == 0 && !bouncy && report.note().contains("скорость после неточного")) {
                                        failure = "uncertainty kept on an exact tick: " + context;
                                    }
                                    // uncertain ticks are allowed (a gap, or a change still in flight); a difference must say so
                                    if (report.status() == MovementCheckRunner.Status.COMPARED && report.offset() != 0.0 && report.gaps().isEmpty() && report.inFlight().isEmpty()) {
                                        failure = context;
                                    }
                                    continue;
                                }
                                if (!seeded || reseat) {
                                    // the first ride tick starts from the boat position the client reports
                                    seeded = true;
                                    reseat = false;
                                    continue;
                                }
                                // the boat's box and pushes are modelled: nothing about them is left uncertain
                                if (report.status() != MovementCheckRunner.Status.COMPARED || report.offset() != 0.0 || !report.gaps().isEmpty())
                                    failure = context;
                                // Record equality compares double bits (including signed zero); also check a tick whose
                                // displacement was too small for the client to send a position.
                                if (living && report.vehicle() == null && report.gaps().isEmpty())
                                    assertEquals(vanilla, report.predicted(), context);
                            }
                        default ->
                            throw new IOException("Unknown MADV step " + kind);
                    }
                }
                if (failure != null)
                    failures.add(failure);
            }
            assertEquals(-1, in.read());
        }
        if (Boolean.getBoolean("mac.madv.stats") && late == Late.CHANGES) {
            System.out.println("MADV 26.3 late changes: " + uncertain + " of " + compared + " compared ticks uncertain");
        }
        assertEquals(List.of(), failures, "26.3");
    }
}
