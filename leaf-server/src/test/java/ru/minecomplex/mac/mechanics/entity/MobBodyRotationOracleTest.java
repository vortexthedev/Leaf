package ru.minecomplex.mac.mechanics.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;

import ru.minecomplex.mac.engine.movementtick.HorseWholePlayerOracleTest;

import ru.minecomplex.mac.engine.movementtick.PistonPhysicsOracleTest;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class MobBodyRotationOracleTest {

    @Test
    void clientTickAndExactThresholds()throws Exception {
        var raw=getClass().getResourceAsStream("/vanilla/777/mob-body-rotation.bin.gz");assertNotNull(raw);
        try(var in=new DataInputStream(new GZIPInputStream(raw))){
            assertEquals(0x4d414252,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());assertEquals(4096,in.readInt());
            var rotation=new MobBodyRotation();
            for(int n=0;n<4096;n++){
                int kind=in.readUnsignedByte();var old=PistonPhysicsOracleTest.vector(in);var position=PistonPhysicsOracleTest.vector(in);
                float max=in.readFloat();boolean mob=in.readBoolean(),refuses=in.readBoolean();var look=new MobBodyRotation.Look();
                look.yaw=in.readFloat();look.headYaw=in.readFloat();look.bodyYaw=in.readFloat();var state=new MobBodyRotation.State();state.lastStableHeadYaw=in.readFloat();state.headStableTime=in.readInt();
                rotation.tick(state,look,old,position,max,mob,refuses);String context="26.3 "+kind+" "+n;
                HorseWholePlayerOracleTest.bits(in.readFloat(),look.headYaw,context);HorseWholePlayerOracleTest.bits(in.readFloat(),look.bodyYaw,context);
                HorseWholePlayerOracleTest.bits(in.readFloat(),state.lastStableHeadYaw,context);assertEquals(in.readInt(),state.headStableTime,context);
            }
            assertEquals(-1,in.read());
        }
    }
}
