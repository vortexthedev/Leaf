package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.*;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/** Border state and exact outside shape, movement and noCollision against real vanilla under fixed time. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class WorldBorderOracleTest {

    static final class Port extends MovementTicker {
        Port(MovementEnvironment env,PlayerState p){super(env,p);}
        boolean free(Aabb box){return noCollision(box);}
    }
    @Test
    void matchesVanillaBorder()throws Exception {
        var registry=BlockRegistry.get();
        var raw=getClass().getResourceAsStream("/vanilla/777/world-border.bin.gz");assertNotNull(raw);
        var collide=MovementTicker.class.getDeclaredMethod("collide",Vec3d.class);collide.setAccessible(true);
        var escape=MovementTicker.class.getDeclaredMethod("isFree",double.class,double.class,double.class);escape.setAccessible(true);
        try(var in=new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414257,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());assertEquals(512,in.readInt());
            for(int i=0;i<512;i++) {
                var border=new ClientWorldBorder();border.setCenter(in.readDouble(),in.readDouble());border.setAbsoluteMaxSize(in.readInt());
                border.lerp(in.readDouble(),in.readDouble(),in.readLong());
                for(int tick=0;tick<5;tick++) {
                    long now=in.readLong();String context="26.3 border "+i+" tick "+tick;
                    FluidContactOracleTest.bits(in.readDouble(),border.size(),context+" size");
                    var snapshot=border.snapshot();
                    for(double b:new double[]{snapshot.minX(),snapshot.minZ(),snapshot.maxX(),snapshot.maxZ()})FluidContactOracleTest.bits(in.readDouble(),b,context+" bound");
                    var boxes=snapshot.shape().toAabbs();assertEquals(in.readInt(),boxes.size(),context+" boxes");
                    for(Aabb box:boxes)for(double v:new double[]{box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()})FluidContactOracleTest.bits(in.readDouble(),v,context+" shape");
                    var p=new PlayerState();p.position=new Vec3d(in.readDouble(),64.0,in.readDouble());
                    var delta=new Vec3d(in.readDouble(),in.readDouble(),in.readDouble());
                    assertEquals(in.readBoolean(),snapshot.close(p.x(),p.z(),p.boundingBox().expandTowards(delta.x(),delta.y(),delta.z())),context+" close");
                    MovementEnvironment env=new MovementEnvironment() {
                        public BlockSource blocks(){return (x,y,z)->0;}public BlockRegistry registry(){return registry;}
                        public BlockTags tags(){return BlockTags.NONE;}public boolean hasChunkAt(int x,int z){return true;}public int minY(){return -64;}
                        public List<VoxelShape> entityCollisions(Aabb box){return List.of();}
                        public VoxelShape borderShape(Aabb box){return snapshot.collision(p.x(),p.z(),box);}
                    };
                    var physics=new Port(env,p);
                    FluidTravelOracleTest.compare(in,(Vec3d)collide.invoke(physics,delta),context+" move");
                    boolean free=in.readBoolean();
                    assertEquals(free,physics.free(p.boundingBox().move(delta.x(),delta.y(),delta.z())),context+" free");
                    // There are no fluids in this fixture; Entity.isFree is exactly noCollision here.
                    assertEquals(free,(boolean)escape.invoke(physics,delta.x(),delta.y(),delta.z()),context+" escape");
                    assertTrue(physics.unmodeled().isEmpty(),context+" uncertainty");
                    var fork=border.copy();FluidContactOracleTest.bits(border.size(),fork.size(),context+" fork");
                    border.tick();
                    assertEquals(snapshot,fork.snapshot(),context+" independent fork");
                }
            }
            assertEquals(-1,in.read());
        }
    }
}
