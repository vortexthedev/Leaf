package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.engine.movementtick.HorseOracle;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.*;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.vanilla.OracleBits;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class HorseBodyOracleTest {

    @Test
    void matchesRealBodyChains() throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/horse-body.bin.gz";
        var raw = HorseBodyOracleTest.class.getResourceAsStream(path);
        assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414842, in.readInt());
            assertEquals(2, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            assertEquals(48, in.readInt());
            for (var kind : HorseState.Kind.values()) for (int s = 0; s < 48; s++) {
                var map = new HashMap<BlockPosition, Integer>();
                for (int n = in.readInt(); n > 0; n--) map.put(new BlockPosition(in.readInt(), in.readInt(), in.readInt()), in.readInt());
                MovementEnvironment env = new MovementEnvironment() {

                    public boolean fluidsResolved() {
                        return true;
                    }

                    public BlockSource blocks() {
                        return (x, y, z) -> map.getOrDefault(new BlockPosition(x, y, z), 0);
                    }

                    public BlockRegistry registry() {
                        return registry;
                    }

                    public BlockTags tags() {
                        return BlockTags.NONE;
                    }

                    public boolean hasChunkAt(int x, int z) {
                        return true;
                    }

                    public int minY() {
                        return -64;
                    }

                    public List<VoxelShape> entityCollisions(Aabb box) {
                        return List.of();
                    }

                    public VoxelShape borderShape(Aabb box) {
                        return null;
                    }
                };
                float width = in.readFloat(), height = in.readFloat();
                var dimensions = new HorseOracle.Dimensions(in.readFloat(), in.readFloat(), in.readFloat());
                float eye = in.readFloat();
                boolean pushed = in.readBoolean(), controlled = in.readBoolean(), driver = in.readBoolean(), noGravity = in.readBoolean(), shift = in.readBoolean();
                float yaw = in.readFloat();
                var attrs = new double[LivingVehicleBodyOracleTest.ATTRIBUTES.length];
                for (int a = 0; a < attrs.length; a++) attrs[a] = in.readDouble();
                var body = PistonPhysicsOracleTest.state(in);
                assertEquals(0, in.readInt());
                body.eyeInWater = in.readBoolean();
                body.eyeInLava = in.readBoolean();
                body.fixedBodyDimensions = true;
                body.bodyWidth = width;
                body.bodyHeight = height;
                body.yRot = yaw;
                body.shiftKeyDown = shift;
                body.firstTick = false;
                body.oldPosition = new Vec3d(.5, 64.6, .5);
                body.fluidShapes = FluidShapeCache.empty();
                for (int a = 0; a < attrs.length; a++) body.attributes.get(LivingVehicleBodyOracleTest.ATTRIBUTES[a]).setBaseValue(attrs[a]);
                var state = new HorseState(new Object(),kind,body);
                var physics = HorseOracle.body(env, state, eye, pushed, controlled, driver, noGravity, dimensions);

                assertEquals(12, in.readInt());
                for (int step = 0; step < 12; step++) {
                    body.wasTouchingWater = in.readBoolean();
                    body.isInLava = in.readBoolean();
                    body.waterHeight = in.readDouble();
                    body.lavaHeight = in.readDouble();
                    var input = OracleBits.vector(in);
                    boolean ridden = in.readBoolean();
                    float speed = in.readFloat();
                    boolean refuses = in.readBoolean();
                    String context = "26.3 " + kind + " " + s + " " + step;
                    if (ridden) {
                        var facts = new HorseOracle.Control(kind, in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readLong(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), in.readFloat());
                        var rider = new HorseOracle.Rider(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readBoolean());
                        HorseOracle.assign(state,HorseControlOracleTest.state(in, kind == HorseState.Kind.CAMEL));
                        facts.apply(state);physics.gameTime(facts.gameTime());physics.travelRidden(rider.player(),true);
                        HorseControlOracleTest.compare(in, state, kind == HorseState.Kind.CAMEL, context + " controls");
                    } else
                        physics.travelBody(input, speed, refuses);
                    if (in.readBoolean())
                        physics.applyEffectsFromBlocks();
                    PistonPhysicsOracleTest.compare(PistonPhysicsOracleTest.state(in), body, context);
                    assertEquals(in.readInt(), body.movementThisTick.size(), context + " records");
                    OracleBits.bits(in.readFloat(), body.bodyWidth, context + " width");
                    OracleBits.bits(in.readFloat(), body.bodyHeight, context + " height");
                    OracleBits.bits(in.readFloat(), physics.maxUpStep(), context + " step height");
                    OracleBits.bits(in.readFloat(), physics.getFlyingSpeed(), context + " air speed");
                    assertEquals(in.readBoolean(), body.eyeInWater, context + " water eyes");
                    assertEquals(in.readBoolean(), body.eyeInLava, context + " lava eyes");
                    assertEquals(Set.of(), physics.unmodeled(), context);
                }
            }
            assertEquals(-1, in.read());
        }
    }
}
