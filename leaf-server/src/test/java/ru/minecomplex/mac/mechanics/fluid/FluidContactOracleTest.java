package ru.minecomplex.mac.mechanics.fluid;

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
import ru.minecomplex.mac.engine.movementtick.FluidTravelOracleTest;

import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/** Direct fluid refresh and current vectors against vanilla, without resetting a chained prediction. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
public class FluidContactOracleTest {

    @Test
    void matchesVanillaContact() throws Exception {
        matchesContact(false);
    }
    @Test
    void matchesVanillaVehicleContact()throws Exception {
        matchesContact(true);
    }
    @Test
    void matchesVanillaBoatPassengerContact() throws Exception {
        matchesContact(false,true);
    }
    private void matchesContact(boolean vehicle)throws Exception {
        matchesContact(vehicle,false);
    }
    private void matchesContact(boolean vehicle,boolean passenger)throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/" + (passenger ? "boat-passenger-fluid" : vehicle ? "vehicle-fluid" : "fluid-contact") + ".bin.gz";
        var raw = getClass().getResourceAsStream(path); assertNotNull(raw,path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414643,in.readInt()); assertEquals(2,in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt()); assertEquals(2048,in.readInt());
            float width = vehicle ? in.readFloat() : 0.0F, height = vehicle ? in.readFloat() : 0.0F, eye = vehicle ? in.readFloat() : 0.0F;
            for(int i=0;i<2048;i++) {
                var blocks = new HashMap<Long,Integer>();
                for(int n=in.readInt(); n>0; n--) blocks.put(key(in.readInt(),in.readInt(),in.readInt()),in.readInt());
                var p = new PlayerState(); p.firstTick=false;
                p.fixedBodyDimensions = vehicle; p.bodyWidth = width; p.bodyHeight = height;
                p.position = new Vec3d(0.5,in.readDouble(),0.5); p.abilities.flying=in.readBoolean();
                p.deltaMovement = new Vec3d(in.readDouble(),-0.0,0.0);
                p.passenger = passenger;
                FluidWorld.BoatPassenger boat = passenger ? new FluidWorld.BoatPassenger(new Aabb(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble()),in.readBoolean()) : null;
                var gaps = new ArrayList<Unmodeled>(); boolean[] fast={false};
                Vec3d originalMovement=p.deltaMovement;
                MovementEnvironment env = new MovementEnvironment() {
                    public boolean fluidsResolved(){return true;} public Tri fastLava(){return fast[0]?Tri.TRUE:Tri.FALSE;}
                    public BlockSource blocks(){return (x,y,z)->blocks.getOrDefault(key(x,y,z),0);}
                    public BlockRegistry registry(){return registry;} public BlockTags tags(){return (tag,block)->tag==BlockTag.BLOCKS_FLUID_FLOW && block.name().equals("minecraft:stone");}
                    public boolean hasChunkAt(int x,int z){return true;} public int minY(){return -64;}
                    public List<VoxelShape> entityCollisions(Aabb box){return List.of();}
                    public VoxelShape borderShape(Aabb box){return null;}
                };
                var fluids = new FluidWorld(env,gaps::add,vehicle ? new FluidWorld.EntityFacts(false,eye,true) : null);
                String context="26.3 "+i;
                FluidTravelOracleTest.compare(in,fluids.flow(0,64,0),context+" flow");
                fluids.refresh(p,true,boat);
                bits(in.readDouble(),p.waterHeight,context+" water height");
                bits(in.readDouble(),p.lavaHeight,context+" lava height");
                assertEquals(in.readBoolean(),p.wasTouchingWater,context+" water");
                assertEquals(in.readBoolean(),p.isInLava,context+" lava");
                assertEquals(in.readBoolean(),p.eyeInWater,context+" eye water");
                assertEquals(in.readBoolean(),p.eyeInLava,context+" eye lava");
                FluidTravelOracleTest.compare(in,p.deltaMovement,context+" current");
                p.deltaMovement=originalMovement; fast[0]=true;
                fluids.refresh(p,true,boat);
                FluidTravelOracleTest.compare(in,p.deltaMovement,context+" fast current");
                if (passenger) {
                    boat = new FluidWorld.BoatPassenger(boat.box(),in.readBoolean());
                    fast[0]=false; p.deltaMovement=originalMovement;
                    fluids.refresh(p,false,boat);
                    bits(in.readDouble(),p.waterHeight,context+" next water height");
                    bits(in.readDouble(),p.lavaHeight,context+" next lava height");
                    assertEquals(in.readBoolean(),p.wasTouchingWater,context+" next water");
                    assertEquals(in.readBoolean(),p.isInLava,context+" next lava");
                    assertEquals(in.readBoolean(),p.eyeInWater,context+" next eye water");
                    assertEquals(in.readBoolean(),p.eyeInLava,context+" next eye lava");
                    FluidTravelOracleTest.compare(in,p.deltaMovement,context+" next current");
                }
                assertEquals(List.of(),gaps,context);
            }
            assertEquals(-1,in.read());
        }
    }
    public static void bits(double expected,double actual,String context) {
        assertEquals(Double.doubleToLongBits(expected),Double.doubleToLongBits(actual),context);
    }
    public static long key(int x,int y,int z) {
        return ((long)x & 0x3FFFFFFL)<<38 | ((long)z & 0x3FFFFFFL)<<12 | (y & 0xFFFL);
    }
}
