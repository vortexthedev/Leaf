package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;

import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
public class BoatEnvironmentOracleTest {

    @Test
    void matchesActualBoatQueries() throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/boat-environment.bin.gz";
        var raw = getClass().getResourceAsStream(path); assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414245,in.readInt()); assertEquals(1,in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());
            assertEquals(2048,in.readInt());
            for (int i = 0; i < 2048; i++) {
                var blocks = new HashMap<Long,Integer>();
                for (int n = in.readInt(); n > 0; n--) blocks.put(FluidContactOracleTest.key(in.readInt(),in.readInt(),in.readInt()),in.readInt());
                var box = new Aabb(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());
                double lastYd = in.readDouble(); var gaps = new ArrayList<Unmodeled>();
                MovementEnvironment env = new MovementEnvironment() {
                    public boolean fluidsResolved(){return true;}
                    public BlockSource blocks(){return (x,y,z)->blocks.getOrDefault(FluidContactOracleTest.key(x,y,z),0);}
                    public BlockRegistry registry(){return registry;} public BlockTags tags(){return (tag,block)->false;}
                    public boolean hasChunkAt(int x,int z){return true;} public int minY(){return -64;}
                    public List<VoxelShape> entityCollisions(Aabb b){return List.of();} public VoxelShape borderShape(Aabb b){return null;}
                };
                var state = new BoatState(new Object(),1.375F,0.5625F,0.0F); state.landFriction = -0.0F;
                var queries = new MovementTickerBoat(env,state,null);
                String context = "26.3 " + i;
                assertEquals(in.readBoolean(),queries.checkInWater(box),context);
                FluidContactOracleTest.bits(in.readDouble(),state.waterLevel,context + " water");
                String submerged = in.readUTF(); var actual = queries.isUnderwater(box);
                assertEquals(submerged,actual == null ? "NONE" : actual.name(),context);
                bits(in.readFloat(),queries.getWaterLevelAbove(box,lastYd),context + " above");
                bits(in.readFloat(),queries.getGroundFriction(box),context + " friction");
                assertEquals(in.readUTF(),queries.getStatus(box).name(),context + " status");
                FluidContactOracleTest.bits(in.readDouble(),state.waterLevel,context + " status water");
                bits(in.readFloat(),state.landFriction,context + " land"); assertEquals(Set.of(),queries.unmodeled(),context);
            }
            assertEquals(-1,in.read());
        }
    }
    public static void bits(float expected,float actual,String context) {
        assertEquals(Float.floatToIntBits(expected),Float.floatToIntBits(actual),context);
    }
}
