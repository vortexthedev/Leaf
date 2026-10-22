package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.entity.HorseState;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.HorseState.CamelPose;
import ru.minecomplex.mac.vanilla.OracleBits;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class HorseTickOracleTest {

    static HorseState state(DataInputStream in,boolean camel)throws IOException{
        var s=HorseControlOracleTest.state(in,camel);s.standAnimation=in.readFloat();s.oldStandAnimation=in.readFloat();s.pose=CamelPose.valueOf(in.readUTF());return s;
    }
    @Test
    void matchesRealSuperclassTails()throws Exception{
        String path="/vanilla/777/horse-tick.bin.gz";var raw=getClass().getResourceAsStream(path);assertNotNull(raw,path);
        try(var in=new DataInputStream(new GZIPInputStream(raw))){
            assertEquals(0x4d414854,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());assertEquals(512,in.readInt());

            for(var kind:HorseState.Kind.values())for(int i=0;i<512;i++){
                boolean camel=kind==HorseState.Kind.CAMEL;boolean effective=in.readBoolean(),ground=in.readBoolean(),liquid=in.readBoolean(),passenger=in.readBoolean(),water=in.readBoolean(),first=in.readBoolean();long time=in.readLong();
                var actual=state(in,camel).copy();boolean sitting=actual.lastPoseChangeTick<0;boolean refresh=MovementTickerHorse.tickTail(actual,effective,ground,liquid,passenger,water,first,time);String context="26.3 "+kind+" "+i;
                assertEquals(camel && sitting && water,refresh,context+" dimension callback");
                HorseControlOracleTest.compare(in,actual,camel,context);OracleBits.bits(in.readFloat(),actual.standAnimation,context+" animation");OracleBits.bits(in.readFloat(),actual.oldStandAnimation,context+" old animation");assertEquals(CamelPose.valueOf(in.readUTF()),actual.pose,context+" pose");
            }
            assertEquals(-1,in.read());
        }
    }
}
