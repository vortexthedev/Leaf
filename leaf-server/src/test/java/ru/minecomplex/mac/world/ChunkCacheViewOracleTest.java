package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.Wire;

import ru.minecomplex.mac.fixture.outbound.ChunkCacheCenter;
import ru.minecomplex.mac.fixture.outbound.ChunkCacheRadius;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.util.Tri;

/** Real ClientChunkCache handlers/storage versus the view dependencies of rain queries. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ChunkCacheViewOracleTest {

    private static final Precipitation.Source PLAINS=new Precipitation.Source() {
        public Precipitation.Facts noiseBiome(String dimension,int x,int y,int z) {return new Precipitation.Facts(true,0.8F,false);}
        public Integer seaLevel(String dimension) {return 63;}
    };
    @Test
    void rainOnlyDependsOnViewsThatCanHideItsChunks() throws Exception {
        var registry=BlockRegistry.get();
        int bits=32-Integer.numberOfLeadingZeros(registry.stateCount()-1);
        var raw=getClass().getResourceAsStream("/vanilla/777/chunk-cache-view.bin.gz");
        assertNotNull(raw,"26.3");
        int positive=0,uncertain=0,queriesChecked=0;
        try(var in=new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d435657,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");assertEquals(108,in.readInt());
            for(int scenario=0;scenario<108;scenario++) {
                int radius=in.readInt(),centerX=in.readInt(),centerZ=in.readInt(),blockX=in.readInt(),blockZ=in.readInt();
                var world=new CompensatedWorld("minecraft:overworld",-64,384,radius,registry);
                world.setCenter(centerX,centerZ);
                int count=in.readInt();assertEquals(4,count);
                int[][] queries=new int[count][2];
                for(int[] q:queries) {
                    q[0]=in.readInt();q[1]=in.readInt();
                    var sections=new PalettedSection[24];
                    for(int section=0;section<sections.length;section++) sections[section]=PalettedSection.single(0,bits);
                    world.load(new ChunkColumn(q[0],q[1],sections));
                }
                var initial=world.detachedCopy();
                var pending=new ArrayList<OutboundEvent>();
                boolean alwaysLoaded=true;
                assertEquals(9,in.readInt());
                for(int step=0;step<9;step++) {
                    int kind=in.readUnsignedByte(),a=in.readInt(),b=in.readInt();
                    if(kind==0) {world.setCenter(a,b);centerX=a;centerZ=b;pending.add(new ChunkCacheCenter(a,b));}
                    else if(kind==1) {world.setRadius(a);radius=a;pending.add(new ChunkCacheRadius(a));}
                    else assertEquals(2,kind);
                    String context="26.3"+" scenario="+scenario+" prefix="+step;
                    for(int[] q:queries) {
                        boolean inRange=in.readBoolean(),loaded=in.readBoolean();
                        assertEquals(inRange,CompensatedWorld.viewContains(centerX,centerZ,radius,q[0],q[1]),context+" range");
                        assertEquals(loaded,world.isLoaded(q[0],q[1]),context+" retained chunk");
                        alwaysLoaded &= loaded;queriesChecked++;
                    }
                    var expected=alwaysLoaded?Tri.TRUE:Tri.EITHER;
                    assertEquals(expected,Precipitation.inRain(initial,Wire.ofOut(pending),BlockTags.NONE,PLAINS,
                            blockX+0.5,70.0,blockZ+0.5,71.8),context+" rain");
                    if(alwaysLoaded)positive++;else uncertain++;
                }
            }
            assertEquals(-1,in.read());
        }
        assertEquals(3888,queriesChecked);assertTrue(positive>100);assertTrue(uncertain>100);
    }
}
