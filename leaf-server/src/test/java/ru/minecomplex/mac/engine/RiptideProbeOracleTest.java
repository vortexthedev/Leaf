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
import ru.minecomplex.mac.fixture.ItemComponentSample;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.PlayerInventorySlot;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;

import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * MART v1–v3 through the movement probe: the wire events and the client's tick packets of a real client that uses and
 * releases a riptide trident (levels 1 … 5 of the vanilla definition, whose strength the server's registry gives) in a
 * shallow pool (the launch lifts it off the floor), in a deep one, or on dry land without rain (the use fails), held long
 * enough or not; the server flags and clears the spin attack. Every tick the probe compares must match the client's
 * position exactly, with no gap but the fluid model's shape cache (a fall into the pool).
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class RiptideProbeOracleTest {
    @org.junit.jupiter.api.BeforeAll static void initializeNativePackets() { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }
    static final int PLAYER = 1;

    @Test
    void canopyTransitions() throws Exception {
        replay(false, "riptides-canopy");
    }

    @Test
    void probeFollowsTheThrownClient() throws Exception {
        replay(false);
    }

    @Test
    void roofRejectionSurvivesLightPacketsInFlight() throws Exception {
        replay(true);
    }

    @Test
    void nearbyViewChangesDoNotHideTheRiptideRain() throws Exception {
        replay(false, "riptides", true);
    }

    private void replay(boolean pendingRoofLight) throws Exception {
        replay(pendingRoofLight, System.getProperty("mac.riptide.fixture", "riptides"));
    }

    void replay(boolean pendingRoofLight, String fixture) throws Exception {
        replay(pendingRoofLight, fixture, false);
    }

    private void replay(boolean pendingRoofLight, String fixture, boolean pendingView) throws Exception {
        var equippedSlots=new java.util.HashSet<Integer>();

        var raw = getClass().getResourceAsStream("/vanilla/777/" + fixture + ".bin.gz");
        assertNotNull(raw, "26.3");
        var registry = BlockRegistry.get();
        int stone = registry.block("minecraft:stone").firstStateId();
        int water = registry.block("minecraft:water").firstStateId();
        var failures = new ArrayList<String>();
        long launches = 0;
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            assertEquals(0x4d415254, in.readInt());
            int format = in.readUnsignedShort();
            assertTrue(format >= 1 && format <= 4);
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int scenarios = in.readInt();
            for (int scenario = 0; scenario < scenarios; scenario++) {
                int steps = in.readInt();
                WorldModel worldModel = new WorldModel(registry);
                PlayerModel playerModel = new PlayerModel();
                MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
                probe.tags((tag, block) -> tag == ru.minecomplex.mac.world.BlockTag.BLOCKS_MOTION_IN_HEIGHTMAP
                        && block.name().equals("minecraft:stone"));
                probe.biomes(new ru.minecomplex.mac.world.Precipitation.Source() {
                    public ru.minecomplex.mac.world.Precipitation.Facts noiseBiome(String dimension, int x, int y, int z) {
                        return new ru.minecomplex.mac.world.Precipitation.Facts(true, 0.8F, false);
                    }
                    public Integer seaLevel(String dimension) { return 63; }
                });
                JoinGame join = new JoinGame(PLAYER, "minecraft:overworld", 0, 64, 8, 0, "", ru.minecomplex.mac.util.Tri.TRUE);
                worldModel.sent(0, Wire.of(join));
                playerModel.sent(0, Wire.of(join));
                long seq = 1;
                long frame = 0;
                boolean chunksSent = false;
                Vec3d lastVelocity = Vec3d.ZERO;
                String failure = null;
                int[] pool = null;
                boolean roof = false;
                Boolean canopyTrunk = null;
                for (int step = 0; step < steps; step++) {
                    int kind = in.readUnsignedByte();
                    if (kind != 4 && kind != 5 && kind != 6 && !chunksSent) {
                        chunksSent = true;
                        // Load the four starting columns first: their light is settled before the first use.
                        var columns = new ArrayList<int[]>();
                        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) columns.add(new int[]{x, z});
                        columns.sort(java.util.Comparator.comparingDouble(column ->
                                (column[0] + 0.5) * (column[0] + 0.5) + (column[1] + 0.5) * (column[1] + 0.5)));
                        for (int[] column : columns) {
                            int x = column[0], z = column[1];
                            worldModel.sent(seq++, Wire.of(chunk(x, z, stone, water, pool)));
                            if (roof) for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++)
                                worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.BlockUpdate(x * 16 + bx, 40, z * 16 + bz, stone)));
                        }
                        if (canopyTrunk != null) {
                            int leaves = registry.block("minecraft:oak_leaves").firstStateId();
                            for (int bx = 4; bx <= 31; bx++) for (int bz = 0; bz <= 3; bz++)
                                worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.BlockUpdate(bx, 20, bz, leaves)));
                            if (canopyTrunk) for (int by = 16; by < 20; by++)
                                worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.BlockUpdate(12, by, 1, stone)));
                        }
                    }
                    switch (kind) {
                        case 6 -> {
                            assertEquals(4, format);
                            canopyTrunk = in.readBoolean();
                            probe.tags((tag, block) -> tag == ru.minecomplex.mac.world.BlockTag.BLOCKS_MOTION_IN_HEIGHTMAP
                                    && (block.name().equals("minecraft:stone") || block.name().equals("minecraft:oak_leaves")));
                        }
                        case 5 -> {
                            assertTrue(format >= 2);
                            boolean allowed = in.readBoolean();
                            roof = !in.readBoolean();
                            String levelKey = format >= 3 ? in.readUTF() : "minecraft:overworld";
                            float rainLevel = in.readFloat();
                            boolean vanillaRain = in.readBoolean();
                            join = new JoinGame(PLAYER, levelKey, 0, 64, 8, 0, "", ru.minecomplex.mac.util.Tri.of(allowed));
                            worldModel.sent(seq, Wire.of(join));
                            playerModel.sent(seq++, Wire.of(join));
                            worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.GameEvent(7, rainLevel)));
                            try (var hypothesis = worldModel.hypothesis(new SyncWindow(seq - 1, seq), seq - 1)) {
                                assertEquals(ru.minecomplex.mac.util.Tri.of(vanillaRain), hypothesis.world().raining(), "scenario " + scenario + " allowed " + allowed + " level " + rainLevel);
                                assertEquals(hypothesis.world().raining(), hypothesis.world().detachedCopy().raining());
                            }
                        }
                        case 1 -> {
                            int id = in.readInt();
                            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                            float yRot = in.readFloat(), xRot = in.readFloat();
                            int relatives = in.readInt();
                            playerModel.sent(seq++, Wire.of(new PlayerPosition(id, x, y, z, 0.0, 0.0, 0.0, yRot, xRot, relatives)));
                        }
                        case 2 -> {
                            int slot = in.readInt();
                            equippedSlots.add(slot);
                            int level = in.readInt();
                            // the vanilla riptide: add(linear(1.5, 0.75)), from 0 (EnchantmentHelper.getTridentSpinAttackStrength)
                            float strength = 0.0F + (1.5F + 0.75F * (float) (level - 1));
                            var components = new ItemComponentSample(false, false, null, false, false, null, null, null, null, null,
                                    false, false, false, null, null, new ItemComponentSample.Enchantments(true, false, 0, strength), null);
                            var trident = new WireItem("minecraft:trident", 1, WireItem.ENCHANTMENTS, 0, components);
                            playerModel.sent(seq++, Wire.of(new PlayerInventorySlot(slot, WireItem.encode(trident)),Wire.FACTS));
                        }
                        case 3 -> {
                            byte flags = in.readByte();
                            var data = new EntitySyncedData(PLAYER, EntitySyncedData.LIVING_FLAGS, (byte) 0, false, "", flags, 0.0F);
                            worldModel.sent(seq, Wire.of(data));
                            playerModel.sent(seq++, Wire.of(data));
                        }
                        case 4 -> pool = new int[]{in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt()};
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
                            var window = new SyncWindow(seq - 1, seq);
                            if (pendingRoofLight && roof) {
                                // The fixture's roof never changes. Light may arrive before its marker, but cannot
                                // alter the heightmap rejection recorded by the vanilla use/release oracle.
                                worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.LightUpdate(
                                        VanillaMath.floor(vanilla.x()) >> 4, VanillaMath.floor(vanilla.z()) >> 4)));
                                window = new SyncWindow(window.certainThrough(), seq);
                            }
                            if (pendingView) {
                                // Both possible views retain every fixture column read by this tick. MCVW independently
                                // checks real ClientChunkCache retention; the movement/use trace is unchanged vanilla.
                                worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.ChunkCacheCenter(
                                        VanillaMath.floor(vanilla.x()) >> 4, VanillaMath.floor(vanilla.z()) >> 4)));
                                worldModel.sent(seq++, Wire.of(new ru.minecomplex.mac.fixture.outbound.ChunkCacheRadius(8)));
                                window = new SyncWindow(window.certainThrough(), seq);
                            }
                            var tickFrame = new ClientTickFrame(frame, frame, frame, window, Wire.ofAll(packets),
                                    (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
                            MovementCheckRunner.TickReport report = probe.frame(tickFrame);
                            String context = "26.3" + " scenario " + scenario + " frame " + frame + ": " + report
                                    + " vanilla " + vanilla + " v " + velocity + " packets " + packets;
                            if (System.getProperty("mac.debug.mart", "").equals(scenario + "")) {
                                System.out.println("DEBUG " + context + " mask " + mask + " yaw " + tickYaw + " pitch " + tickPitch);
                            }
                            frame++;
                            if (velocity.subtract(lastVelocity).length() > 1.0) launches++;
                            lastVelocity = velocity;
                            if (failure != null) continue;
                            // the fluid model's own cache gap may come with a fall into the pool; nothing else may stay open
                            var gaps = java.util.EnumSet.noneOf(Unmodeled.class);
                            gaps.addAll(report.gaps());
                            gaps.remove(Unmodeled.FLUID_SHAPE_CACHE);
                            // The fixture performs the leaf click without a server block-prediction ack. Its block
                            // map stays unchanged; coordinates must still match exactly even while that gap is open.
                            if (fixture.equals("riptides-canopy")) gaps.remove(Unmodeled.BLOCK_PREDICTION);
                            boolean exactPosition = report.predicted() != null
                                    && Double.doubleToLongBits(vanilla.x()) == Double.doubleToLongBits(report.predicted().x())
                                    && Double.doubleToLongBits(vanilla.y()) == Double.doubleToLongBits(report.predicted().y())
                                    && Double.doubleToLongBits(vanilla.z()) == Double.doubleToLongBits(report.predicted().z());
                            var expectedInFlight = pendingView ? List.of("ClientboundSetChunkCacheCenterPacket",
                                    "ClientboundSetChunkCacheRadiusPacket") : List.<String>of();
                            if (report.status() != MovementCheckRunner.Status.COMPARED || !exactPosition || report.offset() != 0.0
                                    || !gaps.isEmpty() || !report.inFlight().equals(expectedInFlight)) {
                                failure = context;
                            }
                        }
                        default -> throw new IOException("Unknown MART step " + kind);
                    }
                }
                if (failure != null) failures.add(failure);
            }
            assertEquals(-1, in.read());
        }
        assertTrue(launches > 0, "26.3: no riptide launched the player");
        if(fixture.equals("item-riptides"))assertEquals(Set.of(0,40),equippedSlots,"Real riptide tridents in both hands");
        assertEquals(List.of(), failures, "26.3");
    }

    /**
     * A chunk of the fixture's level: stone below y 16, then air but for the pool's water ({@code pool}: x0 y0 z0 x1 y1 z1,
     * {@code null}: none), in a section of 4-bit palette entries {@code [air, water]} indexed {@code (y << 8 | z << 4 | x)}.
     */
    static ChunkData chunk(int cx, int cz, int stone, int water, int[] pool) {
        long[] data = new long[256];
        boolean any = false;
        if (pool != null) {
            for (int y = 16; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int wx = cx * 16 + x, wz = cz * 16 + z;
                if (wx < pool[0] || wx > pool[3] || y < pool[1] || y > pool[4] || wz < pool[2] || wz > pool[5]) continue;
                int index = (y - 16) << 8 | z << 4 | x;
                data[index / 16] |= 1L << (index % 16) * 4;
                any = true;
            }
        }
        if (!any) {
            return new ChunkData(cx, cz, new int[]{0, 0, 0, 0}, new int[]{1, 1, 1, 1}, new int[]{stone, 0, 0, 0}, new long[0],
                    ChunkBlockEntities.encode(List.of()));
        }
        return new ChunkData(cx, cz, new int[]{0, 4, 0, 0}, new int[]{1, 2, 1, 1}, new int[]{stone, 0, water, 0, 0}, data,
                ChunkBlockEntities.encode(List.of()));
    }
}
