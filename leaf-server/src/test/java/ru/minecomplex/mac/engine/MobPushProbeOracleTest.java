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
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.outbound.BlockUpdate;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EntityAttributes;
import ru.minecomplex.mac.fixture.outbound.EntityMove;
import ru.minecomplex.mac.fixture.outbound.EntityPositionSync;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.fixture.outbound.EntityTeleport;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.PlayerInfoGameMode;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.fixture.outbound.TeamUpdate;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;

import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * MAMP v1 through the movement probe: the wire events and the client's tick packets of a real client among real living
 * entities of every type (spawned, aged, posed, sized, scaled, teamed and moved by the server) that push it. Every tick
 * must be compared and match the client's position exactly with nothing left uncertain, and every mob's client box after
 * every tick must match the model's bit for bit.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class MobPushProbeOracleTest {
    static final int PLAYER = 1;

    @Test
    void probeFollowsTheClient() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/mob-pushes.bin.gz");
        assertNotNull(raw, "26.3");
        var registry = BlockRegistry.get();
        var types = EntityTypes.get();
        var failures = new ArrayList<String>();
        int compared = 0, pushed = 0;
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            assertEquals(0x4d414d50, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int scenarios = in.readInt();
            for (int scenario = 0; scenario < scenarios; scenario++) {
                int steps = in.readInt();
                int floor = in.readInt();
                // the connection logged in as "oracle" (the local player's scoreboard name)
                WorldModel worldModel = new WorldModel(registry, new ru.minecomplex.mac.player.connection.PlayerConnectionContext("oracle", null));
                PlayerModel playerModel = new PlayerModel();
                MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
                var join = new JoinGame(PLAYER, "minecraft:overworld", 0, 64, 8, 0, "oracle");
                worldModel.sent(0, Wire.of(join));
                playerModel.sent(0, Wire.of(join));
                long seq = 1;
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        worldModel.sent(seq++, Wire.of(new ChunkData(x, z, new int[]{0, 0, 0, 0}, new int[]{1, 1, 1, 1}, new int[]{floor, 0, 0, 0},
                                new long[0], ChunkBlockEntities.encode(List.of()))));
                    }
                }
                long frame = 0;
                String failure = null;
                var spawned = new TreeMap<Integer, String>();
                Vec3d lastVelocity = Vec3d.ZERO;
                for (int step = 0; step < steps; step++) {
                    int kind = in.readUnsignedByte();
                    switch (kind) {
                        case 1 -> {
                            int id = in.readInt();
                            String type = in.readUTF();
                            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                            int packed = in.readUnsignedByte();
                            long most = in.readLong(), least = in.readLong();
                            // native angle: the packed byte over 256/360
                            float yaw = (byte) packed / (256.0F / 360.0F);
                            worldModel.sent(seq++, Wire.of(new EntitySpawn(id, type, x, y, z, yaw, 0.0F, yaw, 0, false, 0.0, 0.0, 0.0, most, least)));
                            spawned.put(id, type);
                        }
                        case 2 -> {
                            var data = data(in, types);
                            if (System.getProperty("mac.debug.mamp", "").equals("26.3" + "/" + scenario))
                                System.out.println("DEBUG data " + data + " items " + data.mobItemValues());
                            worldModel.sent(seq++, Wire.of(data));
                        }
                        case 3 -> {
                            int id = in.readInt();
                            String attribute = in.readUTF();
                            double base = in.readDouble();
                            worldModel.sent(seq++, Wire.of(EntityAttributes.of(id, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot(attribute, base, List.of())))));
                        }
                        case 4 -> {
                            String name = in.readUTF();
                            int method = in.readUnsignedByte(), rule = in.readInt();
                            var players = new ArrayList<String>();
                            for (int n = in.readInt(); n > 0; n--) players.add(in.readUTF());
                            worldModel.sent(seq++, Wire.of(TeamUpdate.of(name, method, rule, players)));
                        }
                        case 5 -> {
                            int id = in.readInt();
                            boolean hasPos = in.readBoolean(), hasRot = in.readBoolean();
                            short xa = in.readShort(), ya = in.readShort(), za = in.readShort();
                            byte yRot = in.readByte(), xRot = in.readByte();
                            worldModel.sent(seq++, Wire.of(EntityMove.linear(id, hasPos, hasRot, xa, ya, za, yRot, xRot, in.readBoolean())));
                        }
                        case 6 -> worldModel.sent(seq++, Wire.of(EntityPositionSync.linear(in.readInt(), in.readDouble(), in.readDouble(),
                                in.readDouble(), in.readFloat(), in.readFloat(), in.readBoolean())));
                        case 7 -> worldModel.sent(seq++, Wire.of(new EntityTeleport(in.readInt(), in.readDouble(), in.readDouble(), in.readDouble(),
                                0.0, 0.0, 0.0, in.readFloat(), in.readFloat(), 0, in.readBoolean())));
                        case 8 -> {
                            int id = in.readInt();
                            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                            float yRot = in.readFloat(), xRot = in.readFloat();
                            int relatives = in.readInt();
                            playerModel.sent(seq++, Wire.of(new PlayerPosition(id, x, y, z, 0.0, 0.0, 0.0, yRot, xRot, relatives)));
                        }
                        case 9 -> worldModel.sent(seq++, Wire.of(new BlockUpdate(in.readInt(), in.readInt(), in.readInt(), in.readInt())));
                        case 11 -> {
                            var uuid = new java.util.UUID(in.readLong(), in.readLong());
                            String name = in.readUTF();
                            int mode = in.readInt();
                            worldModel.sent(seq++, Wire.of(new PlayerInfoGameMode(false, false, 0,
                                    PlayerInfoGameMode.encode(List.of(new PlayerInfoGameMode.Profile(uuid, name))),
                                    PlayerInfoGameMode.encodeGameModes(List.of(new PlayerInfoGameMode.EntryGameMode(uuid, mode))))));
                        }
                        case 12 -> {
                            var uuid = new java.util.UUID(in.readLong(), in.readLong());
                            worldModel.sent(seq++, Wire.of(new PlayerInfoGameMode(false, false, 0, null,
                                    PlayerInfoGameMode.encodeGameModes(List.of(new PlayerInfoGameMode.EntryGameMode(uuid, in.readInt()))))));
                        }
                        case 10 -> {
                            int mask = in.readUnsignedByte();
                            in.readFloat();
                            in.readFloat();
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
                            Vec3d vanillaVelocity = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                            var window = new SyncWindow(seq - 1, seq);
                            var tickFrame = new ClientTickFrame(frame, frame, frame, window, Wire.ofAll(packets),
                                    (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
                            MovementCheckRunner.TickReport report = probe.frame(tickFrame);
                            var motions = probe.clientWorld().entities().motions();
                            var boxes = new StringBuilder();
                            int mobs = in.readInt();
                            for (int m = 0; m < mobs; m++) {
                                int id = in.readInt();
                                Vec3d position = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                                Aabb box = new Aabb(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
                                // the ender dragon ticks its own way and pushes nothing: followed by its packet positions only
                                if ("minecraft:ender_dragon".equals(spawned.get(id))) {
                                    if (motions.kindOf(id) != ru.minecomplex.mac.entity.PacketEntityFollowed.Kind.PUSHABLE && failure == null) {
                                        failure = "26.3" + " scenario " + scenario + ": the ender dragon is " + motions.kindOf(id);
                                    }
                                    continue;
                                }
                                Aabb model = motions.boxOf(id);
                                boolean same = model != null && bits(model.minX()) == bits(box.minX()) && bits(model.minY()) == bits(box.minY())
                                        && bits(model.minZ()) == bits(box.minZ()) && bits(model.maxX()) == bits(box.maxX())
                                        && bits(model.maxY()) == bits(box.maxY()) && bits(model.maxZ()) == bits(box.maxZ());
                                if (!same && failure == null) {
                                    failure = "26.3" + " scenario " + scenario + " frame " + frame + ": " + spawned.get(id) + " " + id
                                            + " box " + model + " vanilla " + box + " at " + position + " (" + motions.describe(id) + ")";
                                }
                                boxes.append(' ').append(spawned.get(id)).append('@').append(position);
                            }
                            if (System.getProperty("mac.debug.mamp", "").equals("26.3" + "/" + scenario)) {
                                System.out.println("DEBUG frame " + frame + " player " + vanilla + " v " + vanillaVelocity + " report " + report + boxes);
                                System.out.println("DEBUG model " + probe.diagnosticState().lines()
                                        .filter(line -> line.startsWith("player position=")).findFirst().orElse("unknown"));
                            }
                            String context = "26.3" + " scenario " + scenario + " frame " + frame + ": " + report
                                    + " vanilla " + vanilla + " v " + vanillaVelocity + " previous v " + lastVelocity + boxes;
                            lastVelocity = vanillaVelocity;
                            frame++;
                            if (failure != null) continue;
                            if (report.status() == MovementCheckRunner.Status.COMPARED) compared++;
                            if (report.status() != MovementCheckRunner.Status.COMPARED || report.offset() != 0.0 || !report.gaps().isEmpty()) failure = context;
                        }
                        default -> throw new IOException("Unknown MAMP step " + kind);
                    }
                    if (kind != 10 && System.getProperty("mac.debug.mamp", "").equals("26.3" + "/" + scenario)) {
                        var debugMotions = probe.clientWorld() == null || probe.clientWorld().entities() == null ? null : probe.clientWorld().entities().motions();
                        var described = new StringBuilder();
                        if (debugMotions != null) for (int id : spawned.keySet()) described.append(" [").append(id).append(": ").append(debugMotions.describe(id)).append(']');
                        System.out.println("DEBUG step " + kind + " (the model applies it with the next tick)" + described);
                    }
                }
                if (failure != null) failures.add(failure);
            }
            assertEquals(-1, in.read());
        }
        assertEquals(List.of(), failures.subList(0, Math.min(failures.size(), 5)), "26.3: " + failures.size() + " failed scenarios");
        assertTrue(compared > 0, "26.3");
    }

    private static long bits(double value) {
        return Double.doubleToLongBits(value);
    }

    /**
     * One {@code set_entity_data} as the adapter normalizes it: base items 0 (shared flags), 6 (pose), 8 (living flags),
     * 9 (health) and 14 (sleeping position), mob items from 15.
     */
    static EntitySyncedData data(DataInputStream in, EntityTypes types) throws IOException {
        int id = in.readInt();
        int present = 0;
        byte sharedFlags = 0, livingFlags = 0;
        String pose = "";
        float health = 0.0F;
        boolean sleeping = false;
        int[] bed = new int[3];
        var mobItems = new TreeMap<Integer, Object>();
        for (int n = in.readInt(); n > 0; n--) {
            int dataId = in.readInt();
            int kind = in.readUnsignedByte();
            Object value = switch (kind) {
                case 1 -> in.readByte();
                case 2 -> in.readBoolean();
                case 3 -> in.readInt();
                case 5 -> in.readFloat();
                case 6 -> types.vanillaPose(in.readInt()).name();
                // an enum serializer (the sniffer's state): its wire id
                case 7 -> in.readInt();
                case 8 -> {
                    boolean presentPosition = in.readBoolean();
                    yield presentPosition ? new int[]{in.readInt(), in.readInt(), in.readInt()} : null;
                }
                default -> throw new IOException("Unknown MAMP data kind " + kind);
            };
            if (dataId == 0 && kind == 1) {
                sharedFlags = (byte) value;
                present |= EntitySyncedData.SHARED_FLAGS;
            } else if (dataId == 6 && kind == 6) {
                pose = (String) value;
                present |= EntitySyncedData.POSE;
            } else if (dataId == 8 && kind == 1) {
                livingFlags = (byte) value;
                present |= EntitySyncedData.LIVING_FLAGS;
            } else if (dataId == 9 && kind == 5) {
                health = (float) value;
                present |= EntitySyncedData.HEALTH;
            } else if (dataId == 14 && kind == 8) {
                present |= EntitySyncedData.SLEEPING_POS;
                sleeping = value != null;
                if (sleeping) bed = (int[]) value;
            } else if (dataId >= 15) {
                mobItems.put(dataId, value);
            } else {
                throw new IOException("Unexpected MAMP base item " + dataId);
            }
        }
        return new EntitySyncedData(id, present, sharedFlags, false, pose, livingFlags, health, EntitySyncedData.encodeMobItems(mobItems),
                sleeping, bed[0], bed[1], bed[2]);
    }
}
