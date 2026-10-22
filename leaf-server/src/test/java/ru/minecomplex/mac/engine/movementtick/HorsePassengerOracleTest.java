package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.entity.HorseState;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.vanilla.OracleBits;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class HorsePassengerOracleTest {

    static void vector(Vec3d expected,Vec3d actual,String context){
        FluidContactOracleTest.bits(expected.x(),actual.x(),context+" X");FluidContactOracleTest.bits(expected.y(),actual.y(),context+" Y");FluidContactOracleTest.bits(expected.z(),actual.z(),context+" Z");
    }
    @Test
    void matchesRealAttachmentsAndPosition()throws Exception{
        String path="/vanilla/777/horse-passenger.bin.gz";var raw=getClass().getResourceAsStream(path);assertNotNull(raw,path);
        try(var in=new DataInputStream(new GZIPInputStream(raw))){
            assertEquals(0x4d414850,in.readInt());assertEquals(2,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());assertEquals(1024,in.readInt());var physics=new HorsePassengerPhysics();
            for(var kind:HorseState.Kind.values())for(int i=0;i<1024;i++){
                var f=new HorsePassengerPhysics.Facts(kind,OracleBits.vector(in),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readBoolean(),in.readBoolean(),in.readInt(),in.readInt(),in.readBoolean(),in.readLong(),in.readLong(),in.readFloat(),OracleBits.vector(in));
                float partial=in.readFloat(),scale=in.readFloat();String context="26.3 "+kind+" "+i;
                if(kind==HorseState.Kind.CAMEL){FluidContactOracleTest.bits(in.readDouble(),physics.anchor(f,true,partial,scale),context+" front");FluidContactOracleTest.bits(in.readDouble(),physics.anchor(f,false,partial,scale),context+" rear");}
                vector(OracleBits.vector(in),physics.attachment(f,scale),context+" attachment");
                var attachment=OracleBits.vector(in);boolean passenger=in.readBoolean();var actual=BoatPassengerOracleTest.state(in);physics.position(actual,f,attachment,passenger);BoatPassengerOracleTest.compare(BoatPassengerOracleTest.state(in),actual,context+" position");
            }
            assertEquals(-1,in.read());
        }
    }
}
