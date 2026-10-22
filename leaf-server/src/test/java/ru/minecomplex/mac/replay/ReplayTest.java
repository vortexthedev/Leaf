package ru.minecomplex.mac.replay;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.fixture.FixtureCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.fixture.inbound.ChunkBatchReceived;
import ru.minecomplex.mac.fixture.inbound.HeldSlot;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.KeepAlive;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.OtherInbound;
import ru.minecomplex.mac.fixture.inbound.PlayerCommand;
import ru.minecomplex.mac.fixture.inbound.PlayerAbilities;
import ru.minecomplex.mac.fixture.inbound.PlayerInput;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.inbound.Pong;
import ru.minecomplex.mac.fixture.inbound.TeleportAccept;
import ru.minecomplex.mac.fixture.inbound.TickEnd;
import ru.minecomplex.mac.fixture.outbound.Abilities;
import ru.minecomplex.mac.fixture.outbound.BlockEvent;
import ru.minecomplex.mac.fixture.outbound.BlockEntityData;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.BlockChangedAck;
import ru.minecomplex.mac.fixture.outbound.BlockUpdate;
import ru.minecomplex.mac.fixture.outbound.BundleDelimiter;
import ru.minecomplex.mac.fixture.outbound.ChunkBatch;
import ru.minecomplex.mac.fixture.outbound.ChunkCacheCenter;
import ru.minecomplex.mac.fixture.outbound.ChunkCacheRadius;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.ForgetChunk;
import ru.minecomplex.mac.fixture.outbound.SectionBlocksUpdate;
import ru.minecomplex.mac.fixture.outbound.EntityVelocity;
import ru.minecomplex.mac.fixture.outbound.Explosion;
import ru.minecomplex.mac.fixture.outbound.GameEvent;
import ru.minecomplex.mac.fixture.outbound.JoinGame;

import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.Ping;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.fixture.outbound.PlayerRotation;
import ru.minecomplex.mac.fixture.outbound.Respawn;
import ru.minecomplex.mac.player.MacPlayer;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ReplayTest {

    /** Collects everything a session emits, for comparing two runs. */
    private static final class Capture implements FrameListener {
        final List<Object> events = new ArrayList<>();

        @Override
        public void betweenTicks(BetweenTicks event) {
            events.add(event);
        }

        @Override
        public void frame(ClientTickFrame frame) {
            events.add(frame);
        }
    }

    private static InboundEvent randomInbound(Random random, int markerBase) {
        return switch (random.nextInt(13)) {
            case 0, 1, 2 -> TickEnd.INSTANCE;
            case 3 -> new Pong(markerBase + random.nextInt(8));
            case 4 -> Movement.posRot(random.nextGaussian(), 64 + random.nextDouble(), random.nextGaussian(),
                    random.nextFloat() * 360, random.nextFloat() * 90, random.nextBoolean(), random.nextBoolean());
            case 5 -> Movement.rot(random.nextFloat(), random.nextFloat(), false, false);
            case 6 -> random.nextBoolean() ? TeleportAccept.idOnly(random.nextInt(4))
                    : TeleportAccept.withPosition(random.nextInt(4), 1.5, 2.5, 3.5, 4, 5);
            case 7 -> new PlayerInput(random.nextInt(128));
            case 8 -> new PlayerCommand(PlayerCommand.Action.values()[random.nextInt(PlayerCommand.Action.values().length)], random.nextInt(100));
            case 9 -> random.nextBoolean() ? new HeldSlot(random.nextInt(9)) : PlayerLoaded.INSTANCE;
            case 10 -> random.nextBoolean() ? new KeepAlive(random.nextLong()) : new ChunkBatchReceived(random.nextFloat());
            case 11 -> new PlayerAbilities(random.nextBoolean());
            default -> new OtherInbound("interact_" + random.nextInt(3));
        };
    }

    private static OutboundEvent randomOutbound(Random random, MacPlayer session) {
        return switch (random.nextInt(12)) {
            case 0, 1 -> new Ping(session.allocateMarker(0));
            case 2 -> BundleDelimiter.INSTANCE;
            case 3 -> random.nextBoolean() ? new JoinGame(random.nextInt(), "minecraft:overworld", -64, 384, 10)
                    : new Respawn(random.nextBoolean() ? "minecraft:the_nether" : "minecraft:overworld", 3, 0, 256);
            case 4 -> new PlayerPosition(random.nextInt(4), 1.5, 2.5, 3.5, 0.1, 0.2, 0.3, 4, 5, random.nextInt(512));
            case 5 -> new PlayerRotation(random.nextFloat() * 360, random.nextBoolean(), random.nextFloat() * 90, random.nextBoolean());
            case 6 -> new EntityVelocity(random.nextInt(50), random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
            case 7 -> new Explosion(1, 2, 3, random.nextBoolean(), 0.5, 0.25, -0.5);
            case 8 -> new Abilities(false, random.nextBoolean(), true, false, 0.05f, 0.1f);
            case 9 -> new GameEvent(random.nextInt(14), random.nextFloat());
            case 10 -> switch (random.nextInt(5)) {
                case 0 -> new ChunkData(random.nextInt(9) - 4, random.nextInt(9) - 4, new int[]{0, 4}, new int[]{1, 2},
                        new int[]{0, 0, 1}, new long[256]);
                case 1 -> new BlockUpdate(random.nextInt(64) - 32, random.nextInt(100), random.nextInt(64) - 32, random.nextInt(20));
                case 2 -> new SectionBlocksUpdate(0, 1, 0, new long[]{(5L << 12) | 0x123, (1L << 12) | 0x456});
                case 3 -> new ChunkCacheCenter(random.nextInt(5) - 2, random.nextInt(5) - 2);
                default -> random.nextBoolean() ? new ForgetChunk(1, -1) : new ChunkCacheRadius(2 + random.nextInt(10));
            };
            default -> random.nextBoolean() ? new BlockChangedAck(random.nextInt(100)) : new ChunkBatch(random.nextBoolean(), 5);
        };
    }

    @Test
    void recordingReplaysIdentically() throws IOException {
        for (long seed = 0; seed < 50; seed++) {
            Random random = new Random(seed);
            Capture original = new Capture();
            MacPlayer session = new MacPlayer(original);
            RecordingBuffer buffer = new RecordingBuffer(0);
            session.record(buffer);
            long nanos = 1_000;
            for (int i = 0; i < 2_000; i++) {
                nanos += random.nextInt(50_000_000);
                if (random.nextBoolean()) {
                    Wire.receive(session, randomInbound(random, 0x4D00_0000), nanos);
                } else {
                    Wire.send(session, randomOutbound(random, session), nanos);
                }
            }
            Recording recording = buffer.snapshot("seed " + seed);
            assertFalse(buffer.truncated());

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ReplayFormat.write(recording, bytes);
            Recording read = ReplayFormat.read(new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(recording, read, "round trip through the binary format");

            Capture replayed = new Capture();
            MacPlayer replay = Replayer.replay(read, replayed);
            assertEquals(ru.minecomplex.mac.Wire.view(original.events), ru.minecomplex.mac.Wire.view(replayed.events), "replay produces the same frames and events");
            assertEquals(session.currentWindow(), replay.currentWindow());
            assertEquals(session.timeline().worldEpoch(), replay.timeline().worldEpoch());
        }
    }

    /** Typed NBT of a compound holding {@code data} as a byte array. */
    private static byte[] nbt(byte[] data) throws IOException {
        var compound = new net.minecraft.nbt.CompoundTag();
        compound.putByteArray("data", data);
        var bytes = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeAnyTag(compound,new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    @Test
    void dynamicPacketsPreserveTypedNbtAndLargePayloads() throws IOException {
        byte[] large = new byte[100_000];
        new Random(590).nextBytes(large);
        byte[] tag = nbt(large);
        var entity = new BlockEntityData(-17, -64, 31, "minecraft:piston", tag);
        byte original = tag[0]; tag[0]++;
        assertEquals(original, entity.nbt()[0]);
        byte[] accessor = entity.nbt(); accessor[0]++;
        assertEquals(original, entity.nbt()[0]);
        byte[] batch = ChunkBlockEntities.encode(List.of(entity, new BlockEntityData(-17, 0, 31, "minecraft:shulker_box", new byte[]{0})));
        assertEquals(2, ChunkBlockEntities.decode(batch).size());
        assertEquals(entity, ChunkBlockEntities.decode(batch).getFirst());
        int[] ones = new int[24];
        java.util.Arrays.fill(ones, 1);
        // a whole column of the overworld's 24 sections (a server always sends them all)
        var chunk = new ChunkData(-2, 1, new int[24], ones, new int[24], new long[0], batch);
        var mismatches = new ArrayList<String>();
          for (Record record : List.of(new JoinGame(5, "minecraft:overworld", -64, 384, 10, 1, "", ru.minecomplex.mac.util.Tri.TRUE), entity, chunk, new BlockEvent(-17, -64, 31, 255, 255, 42),
                  new ru.minecomplex.mac.fixture.inbound.VehicleMovement(-0.0, 64, 31, 37, -12, true),
                  new ru.minecomplex.mac.fixture.inbound.PaddleBoat(true, false),
                  new ru.minecomplex.mac.fixture.outbound.EntitySpawn(17, "minecraft:oak_boat", -0.0, 64, 0.5, 36.5625F, -12.65625F, 36.5625F, 0, true, 0, 0, 0.0),
                  new ru.minecomplex.mac.fixture.outbound.EntityRemove(new int[]{1, 17, 99}),
                  new ru.minecomplex.mac.fixture.outbound.EntityPassengers(17, new int[]{1, 99}),
                  new ru.minecomplex.mac.fixture.outbound.VehicleCorrection(0, 64, 1, 90, 10),
                new ru.minecomplex.mac.fixture.outbound.WorldBorder(ru.minecomplex.mac.fixture.outbound.WorldBorder.Operation.LERP,
                        0.0, 0.0, 2.0, 10.0, Long.MAX_VALUE, 0),
                new ru.minecomplex.mac.fixture.outbound.EffectUpdate(1, "minecraft:jump_boost", 300, -1, true, false, true, false),
                new ru.minecomplex.mac.fixture.outbound.EffectRemove(1, "minecraft:blindness"),
                ru.minecomplex.mac.fixture.outbound.EntityAttributes.of(1, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot(
                        "minecraft:movement_speed", -0.0, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot.Modifier("minecraft:sprinting", 0.30000001192092896, 2))))),
                new ru.minecomplex.mac.fixture.outbound.PlayerHealth(Float.NaN, 6, 5.0f),
                new ru.minecomplex.mac.fixture.outbound.EntitySyncedData(1, 31, (byte) -120, true, "SWIMMING", (byte) 5, -0.0f),
                ru.minecomplex.mac.fixture.outbound.ContainerContent.of(0, 7, List.of(ru.minecomplex.mac.fixture.WireItem.of("minecraft:elytra", 1)),
                        ru.minecomplex.mac.fixture.WireItem.EMPTY),
                ru.minecomplex.mac.fixture.outbound.ContainerSlot.of(0, 8, 6, ru.minecomplex.mac.fixture.WireItem.of("minecraft:elytra", 1,
                        ru.minecomplex.mac.fixture.WireItem.DAMAGE, ru.minecomplex.mac.fixture.WireItem.GLIDER, null, 0, 431, null, 0.0F)),
                ru.minecomplex.mac.fixture.outbound.PlayerInventorySlot.of(40, ru.minecomplex.mac.fixture.WireItem.of("minecraft:shield", 1)),
                new ru.minecomplex.mac.fixture.outbound.SetHeldSlot(4),
                ru.minecomplex.mac.fixture.outbound.CursorItem.of(ru.minecomplex.mac.fixture.WireItem.EMPTY),
                new ru.minecomplex.mac.fixture.outbound.OpenContainer(3, 2),
                new ru.minecomplex.mac.fixture.outbound.MountScreenOpen(4, 5, 99),
                new ru.minecomplex.mac.fixture.outbound.CloseContainer(3),
                new ru.minecomplex.mac.fixture.outbound.Respawn("minecraft:the_end", 3, 0, 256, 3, ru.minecomplex.mac.util.Tri.TRUE),
                new ru.minecomplex.mac.fixture.outbound.JoinGame(5, "minecraft:overworld", -64, 384, 10, 1, "", ru.minecomplex.mac.util.Tri.TRUE))) {
            // written by native 26.3 as the server sends it, read back through the transport's decoding
            var facts = Wire.FACTS;
            boolean serverbound = record instanceof InboundEvent;
            var wrapper = serverbound ? FixtureCodec.decode((InboundEvent) record, facts) : FixtureCodec.decode((OutboundEvent) record, facts);
            var fixture = new ru.minecomplex.mac.fixture.FixturePackets.Connection(facts);
            var packet = serverbound ? ru.minecomplex.mac.fixture.FixturePackets.packet(1, Recording.Stage.SERVERBOUND, wrapper)
                    : fixture.clientbound(1, wrapper).getLast();
            var connection = fixture.replay();

            Object read = serverbound ? FixtureCodec.encodeInbound(connection.receive(packet).value(), facts)
                    : FixtureCodec.encodeOutbound(connection.send(packet).value(), facts);
            Object expected=record instanceof ChunkData ? FixtureCodec.encodeOutbound(wrapper,facts) : record;
            if (!expected.equals(read)) mismatches.add(record + " -> " + read);
        }
        assertEquals(List.of(), mismatches);
    }

    @Test
    void cappedBufferKeepsTheTail() {
        RecordingBuffer buffer = new RecordingBuffer(3);
        for (int i = 0; i < 5; i++) {
            buffer.packet(new Recording.Packet(i, Recording.Stage.SERVERBOUND, 0x34, new byte[]{(byte) i}));
        }
        Recording recording = buffer.snapshot("");
        assertTrue(buffer.truncated());
        assertEquals(3, recording.entries().size());
        assertEquals(new Recording.Packet(2, Recording.Stage.SERVERBOUND, 0x34, new byte[]{2}), recording.entries().getFirst());
    }
    /**
     * A recorded session's block tags survive their file ({@link TagsFile}) and resolve against the native registry; a replay with tags
     * and a report listener runs the probe as the plugin does.
     */
    @Test
    void recordedTagsAndProbeReportsReplay() throws IOException {
        var keys = new java.util.EnumMap<ru.minecomplex.mac.world.BlockTag, java.util.List<String>>(ru.minecomplex.mac.world.BlockTag.class);
        keys.put(ru.minecomplex.mac.world.BlockTag.CLIMBABLE, java.util.List.of("minecraft:ladder", "minecraft:vine", "minecraft:no_such_block"));
        keys.put(ru.minecomplex.mac.world.BlockTag.WALLS, java.util.List.of());
        var text = new java.io.StringWriter();
        TagsFile.write(keys, text);
        var read = TagsFile.read(new java.io.StringReader(text.toString()));
        assertEquals(keys.get(ru.minecomplex.mac.world.BlockTag.CLIMBABLE), read.get(ru.minecomplex.mac.world.BlockTag.CLIMBABLE));
        assertEquals(java.util.List.of(), read.get(ru.minecomplex.mac.world.BlockTag.FENCES));
        var registry = ru.minecomplex.mac.world.BlockRegistry.get();
        var tags = ru.minecomplex.mac.world.ResolvedBlockTags.ofKeys(read);
        assertEquals(true, tags.contains(ru.minecomplex.mac.world.BlockTag.CLIMBABLE, registry.block("minecraft:ladder")));
        assertEquals(false, tags.contains(ru.minecomplex.mac.world.BlockTag.CLIMBABLE, registry.block("minecraft:stone")));

        Random random = new Random(7);
        MacPlayer session = new MacPlayer(new Capture());
        RecordingBuffer buffer = new RecordingBuffer(0);
        session.record(buffer);
        var live = new ArrayList<ru.minecomplex.mac.engine.MovementCheckRunner.TickReport>();
        session.probe().tags(tags);
        session.probeListener(live::add);
        long nanos = 1_000;
        for (int i = 0; i < 2_000; i++) {
            nanos += random.nextInt(50_000_000);
            if (random.nextBoolean()) Wire.receive(session, randomInbound(random, 0x4D00_0000), nanos);
            else Wire.send(session, randomOutbound(random, session), nanos);
        }
        var replayed = new ArrayList<ru.minecomplex.mac.engine.MovementCheckRunner.TickReport>();
        Replayer.replay(buffer.snapshot("tags"), new Capture(), tags, replayed::add);
        assertEquals(live, replayed, "the replayed probe reports the same ticks");
    }
}
