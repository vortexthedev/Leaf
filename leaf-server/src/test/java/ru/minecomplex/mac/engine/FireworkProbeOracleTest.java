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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EntityRemove;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;

import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * MAFF v1 through the movement probe: the wire events and the client's tick packets of a real client gliding while the
 * server makes it glide, attaches firework rockets to it (one or two at a time, before the glide too), removes them,
 * stops the glide under a rocket and fires rockets attached to nobody; the rockets tick after the player in the real
 * tick list. Every tick the probe compares must match the client's position exactly: when the markers come right after
 * the rockets' changes, and when the markers after a rocket's attachment and removal are answered a frame late (the
 * client handled them before the tick whose window still has them in flight): the states that handled them are kept
 * apart from those that did not, and the next tick tells them apart.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class FireworkProbeOracleTest {
    static final int PLAYER = 1;

    @Test
    void probeFollowsTheGlidingClient() throws Exception {
        replay(false);
    }

    @Test
    void probeFollowsTheGlidingClientWhenRocketMarkersAreLate() throws Exception {
        replay(true);
    }

    private void replay(boolean late) throws Exception {
        replayResource(late,"firework-flights");
    }

    void replayResource(boolean late,String stem)throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/"+stem + ".bin.gz");
        assertNotNull(raw, "26.3");
        var registry = BlockRegistry.get();
        int attachItem = EntityTypes.get().dataId("FireworkRocketEntity.DATA_ATTACHED_TO_TARGET");
        var failures = new ArrayList<String>();
        long boosted = 0;
        var usedHands=new java.util.HashSet<Boolean>();
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            assertEquals(0x4d414646, in.readInt());
            assertEquals(stem.equals("item-rocket-flights")?2:1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            assertEquals(in.readInt(), attachItem, "26.3" + ": DATA_ATTACHED_TO_TARGET id");
            int scenarios = in.readInt();
            for (int scenario = 0; scenario < scenarios; scenario++) {
                int steps = in.readInt();
                WorldModel worldModel = new WorldModel(registry);
                PlayerModel playerModel = new PlayerModel();
                MovementCheckRunner probe = new MovementCheckRunner(worldModel, playerModel, new SyncWindow(-1, 0));
                JoinGame join = new JoinGame(PLAYER, "minecraft:overworld", 0, 256, 8);
                worldModel.sent(0, Wire.of(join));
                playerModel.sent(0, Wire.of(join));
                long seq = 1;
                for (int x = -5; x <= 5; x++) {
                    for (int z = -5; z <= 5; z++) {
                        int[] ones = new int[16];
                        Arrays.fill(ones, 1);
                        worldModel.sent(seq++, Wire.of(new ChunkData(x, z, new int[16], ones, new int[16], new long[0],
                                ChunkBlockEntities.encode(List.of()))));
                    }
                }
                long frame = 0;
                // late: the first rocket attachment or removal since the last tick, still unanswered at the next
                long pendingSince = -1;
                Vec3d lastVelocity = Vec3d.ZERO;
                String failure = null;
                for (int step = 0; step < steps; step++) {
                    int kind = in.readUnsignedByte();
                    switch (kind) {
                        case 1 -> {
                            int id = in.readInt();
                            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                            long most = in.readLong(), least = in.readLong();
                            worldModel.sent(seq++, Wire.of(new EntitySpawn(id, "minecraft:firework_rocket", x, y, z, 0.0F, 0.0F, 0.0F, 0,
                                    false, 0.0, 0.0, 0.0, most, least)));
                        }
                        case 2 -> {
                            int id = in.readInt();
                            int target = in.readInt();
                            if (late && pendingSince < 0) pendingSince = seq;
                            worldModel.sent(seq++, Wire.of(new EntitySyncedData(id, 0, (byte) 0, false, "", (byte) 0, 0.0F,
                                    EntitySyncedData.encodeMobItems(Map.of(attachItem, OptionalInt.of(target))))));
                        }
                        case 3 -> {
                            int id = in.readInt();
                            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                            float yRot = in.readFloat(), xRot = in.readFloat();
                            int relatives = in.readInt();
                            playerModel.sent(seq++, Wire.of(new PlayerPosition(id, x, y, z, 0.0, 0.0, 0.0, yRot, xRot, relatives)));
                        }
                        case 4 -> {
                            int[] ids = new int[in.readInt()];
                            for (int i = 0; i < ids.length; i++) ids[i] = in.readInt();
                            if (late && pendingSince < 0) pendingSince = seq;
                            worldModel.sent(seq++, Wire.of(new EntityRemove(ids)));
                        }
                        case 5 -> {
                            byte flags = in.readByte();
                            // the own player's data: both models see it, the player model applies it
                            var data = new EntitySyncedData(PLAYER, EntitySyncedData.SHARED_FLAGS, flags, false, "", (byte) 0, 0.0F);
                            worldModel.sent(seq, Wire.of(data));
                            playerModel.sent(seq++, Wire.of(data));
                        }
                        case 6 -> playerModel.sent(seq++,Wire.of(ru.minecomplex.mac.fixture.outbound.PlayerInventorySlot.of(in.readInt(),
                                ru.minecomplex.mac.fixture.WireItem.of("minecraft:firework_rocket",in.readInt()))));
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
                                if(packet instanceof ru.minecomplex.mac.fixture.inbound.UseItem use)usedHands.add(use.offHand());
                                packets.add(packet);
                                if (packet instanceof Movement move) movement = move;
                            }
                            Vec3d vanilla = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                            Vec3d velocity = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                            var window = pendingSince >= 0 ? new SyncWindow(pendingSince - 1, seq) : new SyncWindow(seq - 1, seq);
                            pendingSince = -1;
                            var tickFrame = new ClientTickFrame(frame, frame, frame, window, Wire.ofAll(packets),
                                    (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
                            MovementCheckRunner.TickReport report = probe.frame(tickFrame);
                            String context = "26.3" + " scenario " + scenario + " frame " + frame + " window " + window
                                    + ": " + report + " vanilla " + vanilla + " v " + velocity;
                            if (System.getProperty("mac.debug.maff", "").equals(scenario + "")) {
                                System.out.println("DEBUG " + context + " mask " + mask + " yaw " + tickYaw + " pitch " + tickPitch
                                        + " packets " + packets);
                                System.out.println("DEBUG model " + probe.diagnosticState().lines()
                                        .filter(line -> line.startsWith("player position=")).findFirst().orElse("unknown"));
                            }
                            frame++;
                            // a rocket's push: more than the glide alone changes in one tick
                            if (velocity.subtract(lastVelocity).length() > 0.2) boosted++;
                            lastVelocity = velocity;
                            if (failure != null) continue;
                            if (report.status() != MovementCheckRunner.Status.COMPARED || report.offset() != 0.0
                                    || !report.gaps().isEmpty() || !report.inFlight().isEmpty()) {
                                failure = context;
                            }
                        }
                        default -> throw new IOException("Unknown MAFF step " + kind);
                    }
                }
                if (failure != null) failures.add(failure);
            }
            assertEquals(-1, in.read());
        }
        assertTrue(boosted > 0, "26.3: no rocket pushed the player");
        if(stem.equals("item-rocket-flights"))assertEquals(Set.of(false,true),usedHands,"Actual firework use in both hands");
        assertEquals(List.of(), failures, "26.3");
    }
}
