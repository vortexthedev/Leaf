package ru.minecomplex.mac.packet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.replay.Recording;
import ru.minecomplex.mac.world.ChunkPacketReader;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class NativeChunkPacketsTest {
    @Test void chunkLightAndBiomesRetainTheirNativePayloads() {
        var codec = new NativePacketCodec(NativeRuntime.registries());
        Packet<?>[] packets = {
                Wire.of(new ChunkData(-3, 7, new int[]{0}, new int[]{1}, new int[]{1}, new long[0])),
                Wire.of(new LightUpdate(-3, 7)),
                Wire.of(new ChunkBiomes(new long[]{new net.minecraft.world.level.ChunkPos(-3, 7).pack()}))
        };
        for (var packet : packets) {
            assertTrue(ru.minecomplex.mac.replica.WorldModel.isWorldChange(packet));
            var wire = codec.encode(0, Recording.Stage.CLIENTBOUND, packet);
            var decoded = codec.decode(wire);
            assertSame(decoded, PacketDecoder.decode(decoded, NativeRuntime.registries()));
            if (decoded instanceof ClientboundLevelChunkWithLightPacket chunk) {
                var first = ChunkPacketReader.sections(chunk, NativeRuntime.registries());
                assertEquals(1, first.size());
                assertEquals(1, first.getFirst().get(0));
                first.getFirst().set(0, 0, 0, 0);
                assertEquals(1, ChunkPacketReader.sections(chunk, NativeRuntime.registries()).getFirst().get(0));
                assertTrue(ChunkPacketReader.blockEntities(chunk).isEmpty());
            }
            assertArrayEquals(wire.payload(), codec.encode(0, Recording.Stage.CLIENTBOUND, decoded).payload());
        }
    }
}
