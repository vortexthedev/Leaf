package ru.minecomplex.mac.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.syncher.*;

import ru.minecomplex.mac.replay.Recording;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class NativePacketBoundaryTest {
    @Test void allNineRelativeFlagsSurviveTheNativeRecordingCodec() {
        var codec=new NativePacketCodec(NativeRuntime.registries());
        for(int mask=0;mask<512;mask++) {
            var packet=new ClientboundPlayerPositionPacket(19,new PositionMoveRotation(new Vec3(-0.0,64,7),new Vec3(1,2,3),-90,12),Relative.unpack(mask));
            var recorded=codec.encode(0,Recording.Stage.CLIENTBOUND,packet);
            var decoded=(ClientboundPlayerPositionPacket)codec.decode(recorded);
            assertEquals(mask,Relative.pack(decoded.relatives()));
            for(var flag:Relative.values()) assertEquals(decoded.relatives().contains(flag),Packets.relative(mask,flag),"flag "+flag+" mask "+mask);
            assertEquals(Double.doubleToLongBits(-0.0),Double.doubleToLongBits(decoded.change().position().x()));
            assertArrayEquals(recorded.payload(),codec.encode(0,Recording.Stage.CLIENTBOUND,decoded).payload());
        }
    }
    @Test void rocketAttachmentUsesOptionalIntAndRetainsEmptyAndZero() {
        var codec=new NativePacketCodec(NativeRuntime.registries());
        for(var attachment:List.of(OptionalInt.empty(),OptionalInt.of(0),OptionalInt.of(37))) {
            var packet=new ClientboundSetEntityDataPacket(8,List.of(new SynchedEntityData.DataValue<>(9,EntityDataSerializers.OPTIONAL_UNSIGNED_INT,attachment)));
            var decoded=(ClientboundSetEntityDataPacket)codec.decode(codec.encode(0,Recording.Stage.CLIENTBOUND,packet));
            assertEquals(Map.of(9,attachment),Packets.mobItems(decoded));
        }
    }
    @Test void everyPackedRotationRoundTripsWithoutWrapperRounding() {
        for(int value=Byte.MIN_VALUE;value<=Byte.MAX_VALUE;value++) {
            byte packed=(byte)value;
            assertEquals(packed,Packets.packedAngle(net.minecraft.util.Mth.unpackDegrees(packed)));
        }
    }
}
