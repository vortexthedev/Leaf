package ru.minecomplex.mac.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;
import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.fixture.FixturePackets;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.replay.Recording;
import ru.minecomplex.mac.replay.ReplayConnection;
import ru.minecomplex.mac.replay.ReplayFormat;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;

import ru.minecomplex.mac.packet.NativeRuntime;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class MultiBlockChangeBitRepackHandlerTest {

    private static Recording.Packet packet(int x,int y,int z,long[] entries) {
        return FixturePackets.packet(123,Recording.Stage.CLIENTBOUND,new MultiBlockChangeBitRepackHandler.Update(x,y,z,entries));
    }
    private static MultiBlockChangeBitRepackHandler.Update decode(Recording.Packet packet) {
        return assertInstanceOf(MultiBlockChangeBitRepackHandler.Update.class,new ReplayConnection(Wire.FACTS).send(packet).value());
    }
    private static void matchesNativePackets(Recording.Packet packet,boolean compact) {
        var actual=decode(packet);
        var nativePacket=assertInstanceOf(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket.class,new ru.minecomplex.mac.packet.NativePacketCodec(NativeRuntime.registries()).decode(packet));
        assertEquals(nativePacket.sectionPos().x(),actual.sectionX());
        assertEquals(nativePacket.sectionPos().y(),actual.sectionY());
        assertEquals(nativePacket.sectionPos().z(),actual.sectionZ());
        for(int i=0;i<actual.count();i++) {
            assertEquals(net.minecraft.world.level.block.Block.getId(nativePacket.states()[i]),actual.stateId(i));
            assertEquals(Short.toUnsignedLong(nativePacket.positions()[i]),actual.entry(i)&4095);
        }
        assertArrayEquals(packet.payload(),FixturePackets.packet(123,Recording.Stage.CLIENTBOUND,actual).payload());
    }

    @Test
    void everyRegistryStateMatchesNativePacketsWithoutPerBlockObjects() throws Exception {
        long[] entries = new long[BlockRegistry.get().stateCount()];
        var random = new Random(71);
        for (int state = 0; state < entries.length; state++) entries[state] = ((long) state << 12) | random.nextInt(4096);
        matchesNativePackets(packet(-17, -4, 29, entries), true);
    }

    @Test
    void allLocalPositionsAndSignedSectionBoundariesMatchNativePackets() throws Exception {
        long[] entries = new long[4096];
        int[] states = {0, 1, 32767, 32768, 35722};
        for (int i = 0; i < entries.length; i++) entries[i] = ((long) states[i % states.length] << 12) | i;
        matchesNativePackets(packet(-2097152, -524288, 2097151, entries), true);
        matchesNativePackets(packet(2097151, 524287, -2097152, entries), true);
        matchesNativePackets(packet(0, 0, 0, new long[0]), true);
    }

    @Test
    void widerStateIdsFallBackWithoutLosingEarlierOrLaterRecords() throws Exception {
        long[] entries = {0xffffffffL, ((long) 1048576 << 12) | 0xabc, ((long) Integer.MAX_VALUE << 12) | 0xfff, 1};

        var update = new MultiBlockChangeBitRepackHandler.Update(-1, -1, -2, entries);
        entries[0] = 0;
        assertEquals(0xffffffffL, update.entry(0));
        assertEquals(1, update.entry(3));
    }

    @Test
    void recordingKeepsOriginalBytesAndReplaysIntoCompactStorage() throws Exception {
        long[] entries = {(32768L << 12) | 0xfff, 0x123, (35722L << 12) | 0xfff};
        var original = packet(-2, -1, 3, entries);
        var bytes = new ByteArrayOutputStream();
        ReplayFormat.write(new Recording("repack", List.of(original)), bytes);
        var replay = ReplayFormat.read(new ByteArrayInputStream(bytes.toByteArray()));
        var recorded = assertInstanceOf(Recording.Packet.class, replay.entries().getFirst());
        assertEquals(original, recorded);
        var decoded = decode(recorded);
        for (int i = 0; i < entries.length; i++) assertEquals(entries[i], decoded.entry(i));
    }

    @Test
    void worldPrefixesAndForksPreserveDuplicateOrderAndHighStateIds() {
        var registry = BlockRegistry.get();
        var world = new WorldModel(registry);
        world.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", -64, 384, 10)));
        int[] paletteSizes = new int[24]; java.util.Arrays.fill(paletteSizes, 1);
        world.sent(1, Wire.of(new ChunkData(-1, -2, new int[24], paletteSizes, new int[24], new long[0])));
        int lastState = registry.stateCount() - 1;
        long[] entries = {(1L << 12) | 0xfff, ((long) lastState << 12) | 0xfff, (2L << 12) | 0x123};
        world.sent(2, decode(packet(-1, -1, -2, entries)));
        var window = new SyncWindow(1, 4);
        assertEquals(0, world.acknowledged(window).getBlock(-1, -1, -17));
        try (var hypothesis = world.hypothesis(window, 1); var fork = hypothesis.fork()) {
            hypothesis.apply(window, 2);
            assertEquals(lastState, hypothesis.world().getBlock(-1, -1, -17));
            assertEquals(2, hypothesis.world().getBlock(-15, -13, -30));
            assertEquals(0, fork.world().getBlock(-1, -1, -17));
            assertEquals(0, world.acknowledged(window).getBlock(-1, -1, -17));
            fork.apply(window, 2);
            assertEquals(lastState, fork.world().getBlock(-1, -1, -17));
        }
        assertEquals(lastState, world.acknowledged(new SyncWindow(2, 4)).getBlock(-1, -1, -17));
    }

    @Test
    void corruptCountsFailBeforeAllocatingTheirArrays() {
        NativeRuntime.ensure();
        for (byte[] count : new byte[][]{{-1, -1, -1, -1, 7}, {-1, -1, -1, -1, 15}, {2}}) {
            byte[] payload = new byte[8 + count.length];
            System.arraycopy(count, 0, payload, 8, count.length);
            var packet = new Recording.Packet(0, Recording.Stage.CLIENTBOUND,
                    packet(0,0,0,new long[0]).packetId(), payload);
            assertThrows(RuntimeException.class, () -> decode(packet));
        }
    }
}
