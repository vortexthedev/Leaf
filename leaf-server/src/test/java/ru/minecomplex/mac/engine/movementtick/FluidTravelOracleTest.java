package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.*;
import ru.minecomplex.mac.entity.effect.MobEffect;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.*;

import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;
/** Direct travel formula with contact/collision predicates independent of the real move-phase oracle. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
public class FluidTravelOracleTest {

 static final class Port extends MovementTicker {
  final boolean climbing;Vec3d moved;
  Port(MovementEnvironment env,PlayerState p,boolean climbing){super(env,p);this.climbing=climbing;}
  @Override protected boolean onClimbable(){return climbing;}
  @Override protected void move(MoverType type,Vec3d delta){moved=delta;}
  void fluid(Vec3d input){travelInFluid(input);}
 }
 @Test
 void matchesVanillaTravel() throws Exception {
  var registry=BlockRegistry.get();String path="/vanilla/777/fluid-travel.bin.gz";
  var raw=getClass().getResourceAsStream(path);assertNotNull(raw,path);
  try(var in=new DataInputStream(new GZIPInputStream(raw))) {
   assertEquals(0x4d414654,in.readInt());assertEquals(2,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());assertEquals(4096,in.readInt());
   for(int i=0;i<4096;i++) {
    int flags=in.readInt();double height=in.readDouble(),efficiency=in.readDouble(),speed=in.readDouble(),gravity=in.readDouble(),size=in.readDouble();
    var p=new PlayerState();p.position=new Vec3d(0.5,64.0,0.5);p.yRot=in.readFloat();
    p.deltaMovement=new Vec3d(in.readDouble(),in.readDouble(),in.readDouble());Vec3d input=new Vec3d(in.readFloat(),0.0,in.readFloat());
    p.attributes.get(Attribute.SCALE).setBaseValue(size);p.refreshDimensions(); // the generator refreshes after the scale
    p.attributes.get(Attribute.WATER_MOVEMENT_EFFICIENCY).setBaseValue(efficiency);
    p.attributes.get(Attribute.MOVEMENT_SPEED).setBaseValue(speed);p.attributes.get(Attribute.GRAVITY).setBaseValue(gravity);
    p.wasTouchingWater=(flags&1)!=0;p.isInLava=!p.wasTouchingWater;p.waterHeight=height;p.lavaHeight=height;
    p.setSprinting((flags&2)!=0);p.onGround=(flags&4)!=0;p.horizontalCollision=(flags&8)!=0;
    if((flags&64)!=0)p.effects.set(MobEffect.DOLPHINS_GRACE,0);
    MovementEnvironment env=new MovementEnvironment() {
     public boolean fluidsResolved(){return true;} public Tri fastLava(){return Tri.FALSE;}
     public BlockSource blocks(){return (x,y,z)->0;}public BlockRegistry registry(){return registry;}
     public BlockTags tags(){return BlockTags.NONE;}public boolean hasChunkAt(int x,int z){return true;}public int minY(){return -64;}
     public List<VoxelShape> entityCollisions(Aabb box){return (flags&32)!=0?List.of():List.of(Shapes.create(box));}
     public VoxelShape borderShape(Aabb box){return null;}
    };
    var port=new Port(env,p,(flags&16)!=0);port.fluid(input);
    compare(in,port.moved,"26.3 move "+i);compare(in,p.deltaMovement,"26.3 result "+i);
    assertTrue(port.unmodeled().isEmpty());
   }
   assertEquals(-1,in.read());
  }
 }
 public static void compare(DataInputStream in,Vec3d v,String context)throws IOException {
  for(double d:new double[]{v.x(),v.y(),v.z()})assertEquals(Double.doubleToLongBits(in.readDouble()),Double.doubleToLongBits(d),context);
 }
}
