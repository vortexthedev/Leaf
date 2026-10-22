package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/** Direct bubble callbacks with independent above-block, flying, precision and clamp factors. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BubbleColumnOracleTest {

    @Test
    void matchesVanillaBubble()throws Exception {
        var registry=BlockRegistry.get();
        boolean precise = true;
        String path="/vanilla/777/bubble-column.bin.gz";
        var raw=getClass().getResourceAsStream(path);assertNotNull(raw,path);
        var inside=MovementTicker.class.getDeclaredMethod("entityInside",int.class,int.class,int.class,int.class,boolean.class,boolean[].class);
        inside.setAccessible(true);
        try(var in=new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414243,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());assertEquals(1024,in.readInt());
            for(int i=0;i<1024;i++) {
                int flags=in.readInt(),state=in.readInt(),above=in.readInt();
                var p=new PlayerState();p.position=new Vec3d(0.5,64.0,0.5);p.deltaMovement=new Vec3d(-0.0,in.readDouble(),0.0);
                p.abilities.flying=(flags&2)!=0;p.fallDistance=7.5;
                MovementEnvironment env=new MovementEnvironment() {
                    public boolean fluidsResolved(){return true;}public Tri fastLava(){return Tri.FALSE;}
                    public BlockSource blocks(){return (x,y,z)->x==0&&z==0?(y==64?state:y==65?above:0):0;}
                    public BlockRegistry registry(){return registry;}public BlockTags tags(){return BlockTags.NONE;}
                    public boolean hasChunkAt(int x,int z){return true;}public int minY(){return -64;}
                    public List<VoxelShape> entityCollisions(Aabb box){return List.of();}public VoxelShape borderShape(Aabb box){return null;}
                };
                var physics=new MovementTicker(env,p);inside.invoke(physics,state,0,64,0,(flags&4)!=0,new boolean[1]);
                String context="26.3 bubble "+i;
                FluidTravelOracleTest.compare(in,p.deltaMovement,context);
                FluidContactOracleTest.bits(in.readDouble(),p.fallDistance,context+" fall");
                assertTrue(physics.unmodeled().isEmpty(),context);
            }
            assertEquals(-1,in.read());
        }
    }
}
