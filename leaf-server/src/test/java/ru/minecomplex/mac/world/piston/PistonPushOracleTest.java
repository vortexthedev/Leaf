package ru.minecomplex.mac.world.piston;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.mechanics.movement.PistonMovement;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockRegistry;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PistonPushOracleTest {

    static Vec3d vector(DataInputStream in)throws IOException{return new Vec3d(in.readDouble(),in.readDouble(),in.readDouble());}
    static Aabb box(DataInputStream in)throws IOException{return new Aabb(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());}
    static void bits(Vec3d expected,Vec3d actual,String message){for(var axis:ru.minecomplex.mac.collision.Axis.values())assertEquals(Double.doubleToLongBits(expected.get(axis)),Double.doubleToLongBits(actual.get(axis)),message+axis);}
    static final class Entity implements PistonPush.Entity {
        Vec3d pos,velocity=new Vec3d(-0.0,0.125,-0.25);boolean ground,support,authority;int reaction;final List<Vec3d> moves=new ArrayList<>();
        public Aabb box(){float half=0.6F/2.0F,height=1.8F;return new Aabb(pos.x()-half,pos.y(),pos.z()-half,pos.x()+half,pos.y()+height,pos.z()+half);}
        public Vec3d position(){return pos;}public Vec3d velocity(){return velocity;}public void velocity(Vec3d v){velocity=v;}
        public boolean ignored(){return reaction==1;}public boolean normalReaction(){return reaction==0;}public boolean onGround(){return ground;}public boolean supportedBy(BlockPosition p){return support;}public boolean serverPlayer(){return false;}public boolean canSimulateMovement(){return authority;}
        public void move(BlockDirection noClip,Vec3d delta){moves.add(delta);pos=pos.add(delta);}
    }
    @Test
    void matchesVanilla()throws Exception{
        var registry=BlockRegistry.get();
        var raw=getClass().getResourceAsStream("/vanilla/777/piston-push.bin.gz");assertNotNull(raw);
        try(var in=new DataInputStream(new GZIPInputStream(raw))){
            assertEquals(0x4d415050,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());int count=in.readInt();
            var push=new PistonPush(registry,BlockCollisions.emptyContext(registry));
            for(int s=0;s<count;s++){
                String context="26.3 push "+s;var piston=new MovingPiston(in.readInt(),BlockDirection.values()[in.readInt()],in.readBoolean(),in.readBoolean(),in.readFloat(),0,0,0);float next=in.readFloat();
                var entity=new Entity();entity.ground=in.readBoolean();entity.support=in.readBoolean();entity.authority=in.readBoolean();entity.reaction=in.readInt();entity.pos=vector(in);assertEquals(box(in),entity.box(),context+" initial box");
                push.push(new BlockPosition(0,64,0),piston,next,query->entity.box().intersects(query.minX(),query.minY(),query.minZ(),query.maxX(),query.maxY(),query.maxZ())?List.of(entity):List.of());
                int n=in.readInt();assertEquals(n,entity.moves.size(),context+" move count");for(int i=0;i<n;i++)bits(vector(in),entity.moves.get(i),context+" request "+i);
                bits(vector(in),entity.pos,context+" position");assertEquals(box(in),entity.box(),context+" box");bits(vector(in),entity.velocity,context+" velocity");
            }
            var limits=new PistonMovement();count=in.readInt();for(int s=0;s<count;s++){long time=in.readLong();Vec3d input=vector(in),expected=vector(in);bits(expected,limits.limit(input,time),"26.3 limit "+s);}
            assertEquals(-1,in.read());
        }
    }
}
