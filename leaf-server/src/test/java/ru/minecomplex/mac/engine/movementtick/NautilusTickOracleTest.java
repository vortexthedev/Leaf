package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class NautilusTickOracleTest {

    @Test
    void matchesActualTickTail()throws Exception{
        String path="/vanilla/777/nautilus-tick.bin.gz";var raw=getClass().getResourceAsStream(path);assertNotNull(raw,path);
        try(var in=new DataInputStream(new GZIPInputStream(raw))){
            assertEquals(0x4d414e54,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());boolean exists=in.readBoolean();assertEquals(true,exists);
            if(exists){assertEquals(512,in.readInt());for(var kind:new OtherVehicleRiding.Kind[]{OtherVehicleRiding.Kind.NAUTILUS,OtherVehicleRiding.Kind.ZOMBIE_NAUTILUS}){
                var control=new OtherVehicleRiding(kind);for(int i=0;i<512;i++){
                    var s=new OtherVehicleRiding.State();s.dashing=in.readBoolean();boolean first=in.readBoolean();s.dashCooldown=in.readInt();
                    control.advanceNautilusDash(s,first);String context="26.3 "+kind+" "+i;
                    assertEquals(in.readBoolean(),s.dashing,context+" dash");assertEquals(in.readInt(),s.dashCooldown,context+" cooldown");
                }
            }}
            assertEquals(-1,in.read());
        }
    }
}
