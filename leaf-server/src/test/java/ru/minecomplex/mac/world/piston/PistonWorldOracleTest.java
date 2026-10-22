package ru.minecomplex.mac.world.piston;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockEvent;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockRegistry;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PistonWorldOracleTest {

    static BlockPosition position(DataInputStream in)throws IOException{return new BlockPosition(in.readInt(),in.readInt(),in.readInt());}
    static final class Fixture implements PistonWorld.World {
        final Map<BlockPosition,Integer> blocks=new LinkedHashMap<>();
        final Map<BlockPosition,MovingPiston> entities=new LinkedHashMap<>();
        final List<List<Integer>> writes=new ArrayList<>(); final BlockRegistry registry;
        Fixture(BlockRegistry registry){this.registry=registry;}
        public int block(BlockPosition p){return blocks.getOrDefault(p,0);}
        public int minY(){return -64;}public int maxY(){return 319;}public boolean withinBorder(BlockPosition p){return p.x()>=-29999984&&p.x()<29999984&&p.z()>=-29999984&&p.z()<29999984;}
        public MovingPiston piston(BlockPosition p){return entities.get(p);}
        public void piston(BlockPosition p,MovingPiston value){if(value==null)entities.remove(p);else entities.put(p,value);}
        public void setBlock(BlockPosition p,int state,int flags){writes.add(List.of(p.x(),p.y(),p.z(),state,flags));if(registry.block(block(p))!=registry.block(state))entities.remove(p);blocks.put(p,state);}
        public int updateFromNeighbours(int state,BlockPosition p){return state;}
        public void neighbourShapes(int state,BlockPosition p,int flags,boolean indirect){}
        public void neighbours(BlockPosition p,int state,BlockDirection direction){}
        public void displacement(BlockPosition p,MovingPiston entity,float next){}
    }
    static void snapshot(DataInputStream in,Fixture world,String context)throws IOException{
        var writes=new ArrayList<List<Integer>>();for(int n=in.readInt();n>0;n--)writes.add(List.of(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt()));
        assertEquals(writes,world.writes,context+" writes");world.writes.clear();
        var blocks=new LinkedHashMap<BlockPosition,Integer>();for(int n=in.readInt();n>0;n--)blocks.put(position(in),in.readInt());
        assertEquals(blocks,world.blocks,context+" blocks");
        var entities=new LinkedHashMap<BlockPosition,MovingPiston>();for(int n=in.readInt();n>0;n--){var p=position(in);entities.put(p,new MovingPiston(in.readInt(),BlockDirection.values()[in.readInt()],in.readBoolean(),in.readBoolean(),in.readFloat(),in.readFloat(),in.readInt(),in.readLong()));}
        assertEquals(new ArrayList<>(entities.keySet()),new ArrayList<>(world.entities.keySet()),context+" ticker order");
        assertEquals(entities,world.entities,context+" entities");
    }
    @Test
    void matchesVanilla()throws Exception{
        var registry=BlockRegistry.get();
        var raw=getClass().getResourceAsStream("/vanilla/777/piston-world.bin.gz");assertNotNull(raw);
        try(var in=new DataInputStream(new GZIPInputStream(raw))){
            assertEquals(0x4d415057,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());int count=in.readInt();
            for(int s=0;s<count;s++){
                var world=new Fixture(registry);var engine=new PistonWorld(world,registry);
                assertEquals(0,in.readInt());for(int n=in.readInt();n>0;n--)world.blocks.put(position(in),in.readInt());assertEquals(0,in.readInt());
                int steps=in.readInt();for(int step=0;step<steps;step++){
                    String context="26.3 scenario "+s+" step "+step;int op=in.readInt(),parameter=in.readInt();long time=in.readLong();
                    if(op<=2)assertEquals(in.readBoolean(),engine.event(new BlockEvent(0,64,0,op,parameter,0)),context);
                    else if(op==3)for(var p:new ArrayList<>(world.entities.keySet()))engine.finalTick(p);
                    else for(var p:new ArrayList<>(world.entities.keySet()))engine.tick(p,time);
                    snapshot(in,world,context);
                }
            }
            assertEquals(-1,in.read());
        }
    }
}
