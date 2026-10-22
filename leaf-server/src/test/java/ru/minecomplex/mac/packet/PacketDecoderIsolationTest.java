package ru.minecomplex.mac.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import ru.minecomplex.mac.replay.Recording;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PacketDecoderIsolationTest {
    @Test void repeatedDecodesCreateIndependentNativePackets() {
        var codec=new NativePacketCodec(NativeRuntime.registries());
        var nativePacket=new ServerboundSetCarriedItemPacket(3);
        var wire=codec.encode(0,Recording.Stage.SERVERBOUND,nativePacket);

        var first=assertInstanceOf(ServerboundSetCarriedItemPacket.class,codec.decode(wire));
        assertEquals(3,first.getSlot());

        assertEquals(3,assertInstanceOf(ServerboundSetCarriedItemPacket.class,codec.decode(wire)).getSlot());
        assertNotSame(nativePacket,first);
        assertNotSame(first,codec.decode(wire));
    }
    @Test void malformedAndTrailingPayloadsCannotContaminateTheNextDecode() {
        var codec=new NativePacketCodec(NativeRuntime.registries());
        var wire=codec.encode(0,Recording.Stage.SERVERBOUND,new ServerboundSetCarriedItemPacket(3));
        assertThrows(RuntimeException.class,()->codec.decode(new Recording.Packet(0,wire.stage(),wire.packetId(),new byte[]{0})));
        assertThrows(RuntimeException.class,()->codec.decode(new Recording.Packet(0,wire.stage(),wire.packetId(),new byte[]{0,3,1})));
        assertEquals(3,assertInstanceOf(ServerboundSetCarriedItemPacket.class,codec.decode(wire)).getSlot());
    }
}
