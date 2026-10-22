package ru.minecomplex.mac.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.fixture.FixtureCodec;
import ru.minecomplex.mac.fixture.outbound.EntityAnimation;
import ru.minecomplex.mac.replay.Recording;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class EntityAnimationDecodingTest {
    @Test void allNativeAnimationIdsRoundTripExactly() {
        var codec=new NativePacketCodec(NativeRuntime.registries());
        for(int action=0;action<3;action++) {
            var fixture=new EntityAnimation(7,action);
            var nativePacket=assertInstanceOf(ClientboundAnimatePacket.class,FixtureCodec.decode(fixture,Wire.FACTS));
            var recorded=codec.encode(1,Recording.Stage.CLIENTBOUND,nativePacket);
            assertArrayEquals(new byte[]{7,(byte)action},recorded.payload());
            var decoded=assertInstanceOf(ClientboundAnimatePacket.class,codec.decode(recorded));
            assertEquals(7,decoded.getId());
            assertEquals(action,decoded.getAction());
            assertEquals(fixture,FixtureCodec.encodeOutbound(decoded,Wire.FACTS));
        }
    }
}
