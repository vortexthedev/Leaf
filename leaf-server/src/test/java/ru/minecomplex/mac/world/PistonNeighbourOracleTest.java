package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.replica.CompensatedWorld;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PistonNeighbourOracleTest {

    @Test
    void matchesVanilla()throws Exception{
        var registry=BlockRegistry.get();
        var raw=getClass().getResourceAsStream("/vanilla/777/piston-neighbours.bin.gz");assertNotNull(raw);
        var update=CompensatedWorld.class.getDeclaredMethod("updateShape",int.class,BlockPosition.class,BlockDirection.class);update.setAccessible(true);
        try(var in=new DataInputStream(new GZIPInputStream(raw))){
            assertEquals(0x4d415048,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());int count=in.readInt();
            for(int s=0;s<count;s++){
                String message="26.3 neighbour "+s;int head=in.readInt(),base=in.readInt();var facing=BlockDirection.values()[in.readInt()];int expected=in.readInt();boolean destroyed=in.readBoolean();
                var world=new CompensatedWorld("minecraft:overworld",0,128,2,registry);var sections=new PalettedSection[8];for(int i=0;i<8;i++)sections[i]=PalettedSection.single(0,16);world.load(new ChunkColumn(0,0,sections));
                var at=new BlockPosition(8,64,8);var behind=at.relative(facing.opposite());world.setBlock(behind.x(),behind.y(),behind.z(),base);world.setBlock(at.x(),at.y(),at.z(),head);
                int state=head;for(var side:new BlockDirection[]{BlockDirection.WEST,BlockDirection.EAST,BlockDirection.NORTH,BlockDirection.SOUTH,BlockDirection.DOWN,BlockDirection.UP})state=(int)update.invoke(world,state,at,side);
                assertEquals(expected,state,message+" survival");world.setBlock(8,64,8,0);assertEquals(destroyed?0:base,world.getBlock(behind.x(),behind.y(),behind.z()),message+" removal");
            }
            assertEquals(-1,in.read());
        }
    }
}
