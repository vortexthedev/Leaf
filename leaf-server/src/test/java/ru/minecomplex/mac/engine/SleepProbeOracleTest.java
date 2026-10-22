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
import ru.minecomplex.mac.fixture.outbound.BlockUpdate;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EntityAnimation;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;

import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * MASL v1 through the movement probe: a real client walks to a bed, the server puts it to sleep (the bed's
 * {@code occupied} with the chunk broadcast, then pose and sleeping position from the entity tracker), it sleeps still
 * sliding, the server wakes it (the wake-up animation, the stand-up teleport, the bed and the standing data) and it walks
 * off. Every tick the probe compares must match the client exactly: with the markers right after the changes, and with
 * the markers after the sleep's and the wake-up's changes answered a frame late (the client lay down before the tick whose
 * window still has the bed and the sleeping position in flight: the state that handled them must be kept, and a bed's
 * occupancy in flight must not stop it).
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class SleepProbeOracleTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeNativePackets() { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }

    static final int PLAYER = 1;

    @Test
    void probeFollowsTheSleepingClient() throws Exception {
        replay(false, false, true);
    }

    /** The server's form: both halves of the bed in one section, one {@code section_blocks_update}, then their entities. */
    @Test
    void probeFollowsTheSleepingClientWhenTheMarkersAreLate() throws Exception {
        replay(true, false, true);
    }

    /** The halves as two {@code block_update}s (a bed across sections). */
    @Test
    void probeFollowsTheSleepingClientWhenTheMarkersAreLateWithSingleBlocks() throws Exception {
        replay(true, false, false);
    }

    /**
     * The late sessions with a block change that matters (a stone block far off becoming air) in flight before the bed's:
     * no state can be branched past it to the sleep, so the tick that applies the sleep a frame late says so
     * (SLEEP_TIMING, carried with the velocity it leaves) instead of differing silently.
     */
    @Test
    void probeNeverDiffersSilentlyWhenTheSleepCannotBeBranched() throws Exception {
        replay(true, true, true);
    }

    /** A change routed as {@code MacPlayer.outbound} does. */
    private static void send(WorldModel world, PlayerModel player, long seq, OutboundEvent event) {
        var facts = Wire.FACTS;
        var packet = Wire.of(event, facts);
        if (WorldModel.isWorldChange(packet)) world.sent(seq, packet);
        if (PlayerModel.isPlayerChange(packet, PLAYER, facts.localUuid())) player.sent(seq, packet);
    }

    /**
     * @param late    the markers after the sleep's and the wake-up's changes are answered a frame late
     * @param blocked a stone block far off becomes air in flight before the bed's changes
     * @param section the bed's halves in one section come as one {@code section_blocks_update} (else two
     *                {@code block_update}s), each followed by its {@code block_entity_data} (a bed's colour)
     */
    private void replay(boolean late, boolean blocked, boolean section) throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/sleeps.bin.gz");
        assertNotNull(raw, "26.3");
        var registry = BlockRegistry.get();
        int stone = registry.block("minecraft:stone").firstStateId();
        var failures = new ArrayList<String>();
        long sleeps = 0;
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            assertEquals(0x4d41534c, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int scenarios = in.readInt();
            for (int scenario = 0; scenario < scenarios; scenario++) {
                int steps = in.readInt();
                WorldModel worldModel = new WorldModel(registry);
                PlayerModel playerModel = new PlayerModel();
                MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
                JoinGame join = new JoinGame(PLAYER, "minecraft:overworld", 0, 64, 2);
                worldModel.sent(0, Wire.of(join));
                playerModel.sent(0, Wire.of(join));
                long seq = 1;
                long frame = 0;
                // late: the first change since the last tick whose marker is answered a frame late; a teleport's accept
                // acknowledges everything before it
                long pendingSince = -1;
                long acceptedThrough = -1;
                String failure = null;
                List<int[]> blocks = new ArrayList<>();
                for (int step = 0; step < steps; step++) {
                    int kind = in.readUnsignedByte();
                    switch (kind) {
                        case 1 -> {
                            int[] foot = {in.readInt(), in.readInt(), in.readInt(), in.readInt()};
                            int[] head = {in.readInt(), in.readInt(), in.readInt(), in.readInt()};
                            for (int x = -1; x <= 1; x++) {
                                for (int z = -1; z <= 1; z++) worldModel.sent(seq++, Wire.of(chunk(x, z, stone, foot, head)));
                            }
                        }
                        case 2 -> {
                            int x = in.readInt(), y = in.readInt(), z = in.readInt(), state = in.readInt();
                            blocks.add(new int[]{x, y, z, state});
                            // the halves of one broadcast: sent once both are read
                            if (blocks.size() < 2) continue;
                            if (late && pendingSince < 0) pendingSince = seq;
                            if (blocked) send(worldModel, playerModel, seq++, new BlockUpdate(-12, 5, -12, 0));
                            int[] a = blocks.get(0), b = blocks.get(1);
                            if (section && a[0] >> 4 == b[0] >> 4 && a[1] >> 4 == b[1] >> 4 && a[2] >> 4 == b[2] >> 4) {
                                long[] entries = new long[2];
                                for (int i = 0; i < 2; i++) {
                                    int[] c = blocks.get(i);
                                    entries[i] = (long) c[3] << 12 | (long) (c[0] & 15) << 8 | (long) (c[2] & 15) << 4 | (c[1] & 15);
                                }
                                send(worldModel, playerModel, seq++, new ru.minecomplex.mac.fixture.outbound.SectionBlocksUpdate(
                                        a[0] >> 4, a[1] >> 4, a[2] >> 4, entries));
                            } else {
                                for (int[] c : blocks) send(worldModel, playerModel, seq++, new BlockUpdate(c[0], c[1], c[2], c[3]));
                            }
                            // Native 26.3 beds have no block entity.
                            blocks.clear();
                        }
                        case 3 -> {
                            boolean sleeping = in.readBoolean();
                            int x = in.readInt(), y = in.readInt(), z = in.readInt();
                            if (late && pendingSince < 0) pendingSince = seq;
                            send(worldModel, playerModel, seq++, new EntitySyncedData(PLAYER,
                                    EntitySyncedData.POSE | EntitySyncedData.SLEEPING_POS, (byte) 0, false,
                                    sleeping ? "SLEEPING" : "STANDING", (byte) 0, 0.0F, new byte[0], sleeping, x, y, z));
                            if (sleeping) sleeps++;
                        }
                        case 4 -> {
                            int id = in.readInt();
                            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                            float yRot = in.readFloat(), xRot = in.readFloat();
                            int relatives = in.readInt();
                            acceptedThrough = seq;
                            send(worldModel, playerModel, seq++, new PlayerPosition(id, x, y, z, 0.0, 0.0, 0.0, yRot, xRot, relatives));
                        }
                        case 5 -> {
                            int action = in.readInt();
                            if (late && pendingSince < 0) pendingSince = seq;
                            send(worldModel, playerModel, seq++, new EntityAnimation(PLAYER, action));
                        }
                        case 10 -> {
                            int mask = in.readUnsignedByte();
                            float tickYaw = in.readFloat();
                            float tickPitch = in.readFloat();
                            var packets = new ArrayList<InboundEvent>();
                            if (frame == 0) packets.add(PlayerLoaded.INSTANCE);
                            Movement movement = null;
                            int count = in.readInt();
                            for (int p = 0; p < count; p++) {
                                InboundEvent packet = LocalPlayerOracleCodec.packet(in);
                                packets.add(packet);
                                if (packet instanceof Movement move) movement = move;
                            }
                            Vec3d vanilla = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                            Vec3d velocity = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                            var window = pendingSince >= 0 ? new SyncWindow(Math.max(pendingSince - 1, acceptedThrough), seq)
                                    : new SyncWindow(seq - 1, seq);
                            pendingSince = -1;
                            acceptedThrough = -1;
                            var tickFrame = new ClientTickFrame(frame, frame, frame, window, Wire.ofAll(packets),
                                    (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
                            MovementCheckRunner.TickReport report = probe.frame(tickFrame);
                            String context = "26.3" + " scenario " + scenario + " frame " + frame + " window " + window + ": "
                                    + report + " vanilla " + vanilla + " v " + velocity + " packets " + packets;
                            if (System.getProperty("mac.debug.masl", "").equals(scenario + "")) System.out.println("DEBUG " + context);
                            frame++;
                            if (failure != null) continue;
                            if (blocked) {
                                // uncertain ticks are allowed; a difference must say so
                                if (report.status() == MovementCheckRunner.Status.COMPARED && report.offset() != 0.0 && report.gaps().isEmpty()
                                        && report.inFlight().isEmpty()) failure = context;
                                continue;
                            }
                            boolean inFlightAllowed = late;
                            if (report.status() != MovementCheckRunner.Status.COMPARED || report.offset() != 0.0 || !report.gaps().isEmpty()
                                    || !inFlightAllowed && !report.inFlight().isEmpty()) {
                                failure = context;
                            }
                        }
                        default -> throw new IOException("Unknown MASL step " + kind);
                    }
                }
                if (failure != null) failures.add(failure);
            }
            assertEquals(-1, in.read());
        }
        assertTrue(sleeps > 0, "26.3");
        assertEquals(List.of(), failures, "26.3");
    }

    /**
     * A chunk of the fixture: stone below y 16, air above but for the bed ({x, y, z, state} of each half), in a section of
     * 4-bit palette entries {@code [air, foot, head]} indexed {@code (y << 8 | z << 4 | x)}.
     */
    static ChunkData chunk(int cx, int cz, int stone, int[] foot, int[] head) {
        long[] data = new long[256];
        boolean any = false;
        int[][] halves = {foot, head};
        for (int h = 0; h < 2; h++) {
            int[] half = halves[h];
            if (half[0] >> 4 != cx || half[2] >> 4 != cz) continue;
            int index = (half[1] - 16) << 8 | (half[2] & 15) << 4 | (half[0] & 15);
            data[index / 16] |= (long) (h + 1) << (index % 16) * 4;
            any = true;
        }
        if (!any) {
            return new ChunkData(cx, cz, new int[]{0, 0, 0, 0}, new int[]{1, 1, 1, 1}, new int[]{stone, 0, 0, 0}, new long[0],
                    ChunkBlockEntities.encode(List.of()));
        }
        return new ChunkData(cx, cz, new int[]{0, 4, 0, 0}, new int[]{1, 3, 1, 1}, new int[]{stone, 0, foot[3], head[3], 0, 0}, data,
                ChunkBlockEntities.encode(List.of()));
    }
}
