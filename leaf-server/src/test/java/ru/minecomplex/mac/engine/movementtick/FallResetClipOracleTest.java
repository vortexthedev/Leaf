package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.*;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/** Real fall-reset water rays with cold/warm cache history, repeated context changes and integrated Entity.move. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class FallResetClipOracleTest {

    static final class Port extends MovementTicker {
        Port(MovementEnvironment env,PlayerState p){super(env,p);}
        void moveOnly(Vec3d delta){move(MoverType.SELF,delta);}
    }
    @Test
    void matchesVanilla()throws Exception {
        var registry=BlockRegistry.get();
        var raw=getClass().getResourceAsStream("/vanilla/777/fall-reset-clip.bin.gz");assertNotNull(raw);
        var clip=MovementTicker.class.getDeclaredMethod("fallResetClipHits",Vec3d.class,Vec3d.class);clip.setAccessible(true);
        int water=0;
        for(int state=0;state<registry.stateCount();state++)if(registry.block(state).name().equals("minecraft:water")&&registry.fluidAmount(state)==8&&!registry.has(state,BlockRegistry.FLAG_FLUID_FALLING)){water=state;break;}
        assertNotEquals(0,water);
        try(var in=new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414652,in.readInt());assertEquals(3,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());assertEquals(4096,in.readInt());
            for(int i=0;i<4096;i++) {
                int state=in.readInt(),above=in.readInt();boolean resetting=in.readBoolean();int warm=in.readInt();
                var from=new Vec3d(in.readDouble(),in.readDouble(),in.readDouble());var to=new Vec3d(in.readDouble(),in.readDouble(),in.readDouble());
                var p=new PlayerState();p.position=from;p.fallDistance=7.5;p.fluidShapes=FluidShapeCache.empty();
                BlockTags tags=resetting?new ResolvedBlockTags(Map.of(BlockTag.FALL_DAMAGE_RESETTING,new int[]{registry.block(state).registryId()})):BlockTags.NONE;
                if(warm!=0) {
                    var prime=new FluidWorld(environment(registry,state,warm==1?0:water,tags),gap->fail(gap.toString()));
                    p.fluidShapes.height(FluidShapeCache.key(registry,state),prime.height(0,64,0));
                }
                String context="26.3 clip "+i;
                var physics=new Port(environment(registry,state,above,tags),p);
                assertEquals(in.readBoolean(),(boolean)clip.invoke(physics,from,to),context+" first");
                cachedHeight(in,p,registry,state,context+" first cache");
                var fork=p.copy();
                int secondAbove=in.readInt();physics=new Port(environment(registry,state,secondAbove,tags),p);
                assertEquals(in.readBoolean(),(boolean)clip.invoke(physics,from,to),context+" cached");
                cachedHeight(in,p,registry,state,context+" second cache");
                p.fallDistance=7.5;physics.moveOnly(to.subtract(from));
                FluidContactOracleTest.bits(in.readDouble(),p.x(),context+" move x");
                FluidContactOracleTest.bits(in.readDouble(),p.y(),context+" move y");
                FluidContactOracleTest.bits(in.readDouble(),p.z(),context+" move z");
                FluidContactOracleTest.bits(in.readDouble(),p.fallDistance,context+" fall distance");
                assertTrue(physics.unmodeled().isEmpty(),context+" "+physics.unmodeled());
                boolean low=in.readBoolean(),full=in.readBoolean();
                for(boolean high:new boolean[]{false,true}) {
                    var history=new PlayerState();history.position=from;history.fluidShapes=FluidShapeCache.empty();
                    float height=high?1.0F:FluidProperties.get().ownHeight(state);
                    history.fluidShapes.resolve(FluidShapeCache.key(registry,state),height);
                    var replay=new Port(environment(registry,state,above,tags),history);
                    assertEquals(high?full:low,(boolean)clip.invoke(replay,from,to),context+" resolved history "+high);
                }
                if(low!=full) {
                    var uncertain=new Port(environment(registry,state,above,tags),new PlayerState());
                    clip.invoke(uncertain,from,to);
                    assertTrue(uncertain.unmodeled().contains(Unmodeled.FLUID_SHAPE_CACHE),context+" unresolved cache must branch");
                }
                // Client-global cache history survives a fork without shared mutable entries.
                var cacheKey=FluidShapeCache.key(registry,state);
                Float old=fork.fluidShapes.height(cacheKey,0.25F);
                p.fluidShapes.resolve(cacheKey,0.75F);
                assertEquals(old,fork.fluidShapes.height(cacheKey,0.5F),context+" independent cache fork");
            }
            assertEquals(-1,in.read());
        }
    }
    static void cachedHeight(DataInputStream in,PlayerState p,BlockRegistry registry,int state,String context)throws IOException {
        Float expected=in.readBoolean()?Float.valueOf(in.readFloat()):null;
        if(registry.fluid(state).equals("minecraft:water")||registry.fluid(state).equals("minecraft:flowing_water"))
            assertEquals(expected,p.fluidShapes.cachedHeight(FluidShapeCache.key(registry,state)),context);
    }
    static MovementEnvironment environment(BlockRegistry registry,int state,int above,BlockTags tags) {
        return new MovementEnvironment() {
            public boolean fluidsResolved(){return true;}public Tri fastLava(){return Tri.FALSE;}
            public BlockSource blocks(){return (x,y,z)->x==0&&z==0?(y==64?state:y==65?above:0):0;}
            public BlockRegistry registry(){return registry;}public BlockTags tags(){return tags;}
            public boolean hasChunkAt(int x,int z){return true;}public int minY(){return -64;}
            public List<VoxelShape> entityCollisions(Aabb box){return List.of();}public VoxelShape borderShape(Aabb box){return null;}
        };
    }
}
