package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.movementtick.MovementTicker;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.world.piston.MovingPiston;
import ru.minecomplex.mac.world.piston.PistonProperties;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PistonShapeOracleTest {

    static double[] doubles(DataInputStream in) throws IOException {
        double[] values = new double[in.readInt()]; for (int i = 0; i < values.length; i++) values[i] = in.readDouble(); return values;
    }
    static VoxelShape shape(DataInputStream in) throws IOException {
        int kind = in.readByte(); double[] xs = doubles(in), ys = doubles(in), zs = doubles(in);
        int nx = in.readInt(), ny = in.readInt(), nz = in.readInt(); long[] bits = new long[in.readInt()];
        for (int i = 0; i < bits.length; i++) bits[i] = in.readLong();
        return kind == 0 ? VoxelShape.EMPTY : kind == 1 ? VoxelShape.cube(nx, ny, nz, bits) : VoxelShape.array(nx, ny, nz, bits, xs, ys, zs);
    }
    static void bits(double expected, double actual, String context) { assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual), context); }
    static final class Physics extends MovementTicker {
        Physics(MovementEnvironment env, PlayerState state) { super(env, state); }
        VoxelShape movingShape(int state, BlockDirection noClip) throws Exception {
            pistonNoClip = noClip; var method = MovementTicker.class.getDeclaredMethod("shapeContext");method.setAccessible(true);
            BlockCollisions.ShapeContext context = (BlockCollisions.ShapeContext) method.invoke(this);return context.shape(state, 0, 64, 0);
        }
    }
    @Test
    void matchesVanilla() throws Exception {
         var registry = BlockRegistry.get();
        var raw = getClass().getResourceAsStream("/vanilla/777/piston-shapes.bin.gz"); assertNotNull(raw);
        var world = new CompensatedWorld("minecraft:overworld", 0, 80, 2, registry);
        int movingState = PistonProperties.get().defaultState("minecraft:moving_piston");
        var sections = new PalettedSection[5]; for (int i=0;i<5;i++) sections[i]=PalettedSection.single(movingState,16);
        world.load(new ChunkColumn(0,0,sections));world.resolveInitialPistonClock();
        var actor = CompensatedWorld.class.getDeclaredMethod("piston",BlockPosition.class,MovingPiston.class);actor.setAccessible(true);
        MovementEnvironment env = new MovementEnvironment() {
            public BlockSource blocks(){return world;}public BlockRegistry registry(){return registry;}public BlockTags tags(){return BlockTags.NONE;}
            public boolean hasChunkAt(int x,int z){return world.isLoaded(x>>4,z>>4);}public int minY(){return 0;}
            public java.util.List<VoxelShape> entityCollisions(Aabb box){return java.util.List.of();}public VoxelShape borderShape(Aabb box){return null;}
        };
        var player=new PlayerState();player.position=new Vec3d(0.5,63.0,0.5);player.fallDistance=3.0;
        var physics=new Physics(env,player);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d415053, in.readInt()); assertEquals(1, in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(4096, in.readInt());
            for (int s = 0; s < 4096; s++) {
                var piston = new MovingPiston(in.readInt(), BlockDirection.values()[in.readInt()], in.readBoolean(), in.readBoolean(), in.readFloat(), in.readFloat(), 0, 0);
                int noClip = in.readInt(); VoxelShape expected = shape(in);
                VoxelShape actual = piston.collisionShape(registry, BlockCollisions.emptyContext(registry), new BlockPosition(0, 64, 0), noClip < 0 ? null : BlockDirection.values()[noClip]);
                String context = "26.3 shape " + s;
                if (!registry.block(piston.movedState()).isInstance("MovingPistonBlock")) {
                    actor.invoke(world,new BlockPosition(0,64,0),piston);
                    assertEquals(expected.toAabbs(),physics.movingShape(movingState,noClip<0?null:BlockDirection.values()[noClip]).toAabbs(),context+" MovementTicker integration");
                }
                var a = actual.toAabbs(); var e = expected.toAabbs(); assertEquals(e.size(), a.size(), context);
                for (int i = 0; i < e.size(); i++) {
                    var eb = e.get(i); var ab = a.get(i);
                    bits(eb.minX(), ab.minX(), context); bits(eb.minY(), ab.minY(), context); bits(eb.minZ(), ab.minZ(), context);
                    bits(eb.maxX(), ab.maxX(), context); bits(eb.maxY(), ab.maxY(), context); bits(eb.maxZ(), ab.maxZ(), context);
                }
                float partial = in.readFloat();
                assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(piston.getProgress(partial)), context);
                float offset = piston.extendedProgress(piston.getProgress(partial));
                for (int component : new int[]{piston.direction().x, piston.direction().y, piston.direction().z})
                    assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(component * offset), context);
            }
            assertEquals(-1, in.read());
        }
    }
}
