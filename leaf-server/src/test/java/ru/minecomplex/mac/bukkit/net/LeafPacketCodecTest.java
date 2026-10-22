package ru.minecomplex.mac.bukkit.net;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.ByteBuf;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.junit.jupiter.api.Test;
import ru.minecomplex.mac.replay.Recording;

class LeafPacketCodecTest {
    private static ProtocolInfo<ClientGamePacketListener> protocol(
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec) {
        return new ProtocolInfo<>() {
            @Override public ConnectionProtocol id() { return ConnectionProtocol.PLAY; }
            @Override public PacketFlow flow() { return PacketFlow.CLIENTBOUND; }
            @Override public StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec() { return codec; }
            @Override public BundlerInfo bundlerInfo() { return null; }
        };
    }

    @Test
    void markerPayloadAndMultibytePacketIdAreSeparatedAndDetached() {
        var buffer = new AtomicReference<ByteBuf>();
        var info = protocol(StreamCodec.of((bytes, packet) -> {
            buffer.set(bytes);
            new FriendlyByteBuf(bytes).writeVarInt(384);
            ClientboundPingPacket.STREAM_CODEC.encode(new FriendlyByteBuf(bytes), (ClientboundPingPacket) packet);
        }, bytes -> { throw new UnsupportedOperationException(); }));
        var recorded = LeafPacketCodec.encode(new ClientboundPingPacket(Integer.MIN_VALUE), info,
                Recording.Stage.CLIENTBOUND, 123L);
        assertEquals(384, recorded.packetId());
        assertEquals(123L, recorded.nanos());
        assertArrayEquals(new byte[]{(byte) 128, 0, 0, 0}, recorded.payload());
        assertEquals(0, buffer.get().refCnt());
    }

    @Test
    void codecFailureReleasesItsBufferWithoutProducingARecord() {
        var buffer = new AtomicReference<ByteBuf>();
        var failure = new IllegalArgumentException("invalid packet");
        var info = protocol(StreamCodec.of((bytes, packet) -> {
            buffer.set(bytes);
            throw failure;
        }, bytes -> { throw new UnsupportedOperationException(); }));
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> LeafPacketCodec.encode(
                new ClientboundPingPacket(1), info, Recording.Stage.CLIENTBOUND, 0)));
        assertEquals(0, buffer.get().refCnt());
    }
}
