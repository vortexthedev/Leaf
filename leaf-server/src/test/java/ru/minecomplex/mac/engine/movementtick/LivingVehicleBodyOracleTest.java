package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.*;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.vanilla.OracleBits;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LivingVehicleBodyOracleTest {

    static final Attribute[] ATTRIBUTES = { Attribute.MOVEMENT_SPEED, Attribute.STEP_HEIGHT, Attribute.GRAVITY, Attribute.MOVEMENT_EFFICIENCY, Attribute.WATER_MOVEMENT_EFFICIENCY, Attribute.BOUNCINESS, Attribute.FRICTION_MODIFIER, Attribute.AIR_DRAG_MODIFIER };

    @Test
    void matchesRealTravelCollisionAndEffects() throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/living-vehicle-body.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414c56, in.readInt());
            assertEquals(2, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int mask = in.readInt();
            assertEquals(3 | (4) | (24), mask);
            assertEquals(48, in.readInt());
            for (int kind = 0; kind < 5; kind++) if ((mask & (1 << kind)) != 0)
                for (int s = 0; s < 48; s++) {
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
                    float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat();
                    boolean pushed = in.readBoolean(), controlled = in.readBoolean(), driver = in.readBoolean(), noGravity = in.readBoolean(), water = in.readBoolean(), lava = in.readBoolean(), shift = in.readBoolean();
                    float yaw = in.readFloat(), speed = in.readFloat();
                    double flying = in.readDouble();
                    double[] attrs = new double[ATTRIBUTES.length];
                    for (int a = 0; a < attrs.length; a++) attrs[a] = in.readDouble();
                    double waterHeight = in.readDouble(), lavaHeight = in.readDouble();
                    var body = PistonPhysicsOracleTest.state(in);
                    assertEquals(0, in.readInt());
                    body.fixedBodyDimensions = true;
                    body.bodyWidth = width;
                    body.bodyHeight = height;
                    body.yRot = yaw;
                    body.shiftKeyDown = shift;
                    body.wasTouchingWater = water;
                    body.isInLava = lava;
                    body.lavaHeight = lavaHeight;
                    body.waterHeight = waterHeight;
                    body.oldPosition = new Vec3d(.5, 64.6, .5);
                    body.fluidShapes = FluidShapeCache.empty();
                    for (int a = 0; a < attrs.length; a++) body.attributes.get(ATTRIBUTES[a]).setBaseValue(attrs[a]);
                    body.firstTick = false;
                    var type = OtherVehicleRiding.Kind.values()[kind];
                    var physics = new LivingVehicleBodyPhysics(env, body, type, eye, pushed, controlled, driver, noGravity, flying);
                    var control = new OtherVehicleRiding(type);
                    var state = new OtherVehicleRiding.State();
                    assertEquals(12, in.readInt());
                    for (int step = 0; step < 12; step++) {
                        body.wasTouchingWater = in.readBoolean();
                        body.isInLava = in.readBoolean();
                        body.waterHeight = in.readDouble();
                        body.lavaHeight = in.readDouble();
                        String context = "26.3 " + type + " " + s + " " + step;
                        var input = OracleBits.vector(in);
                        boolean ridden = in.readBoolean();
                        float currentSpeed = in.readFloat();
                        if (ridden) {
                            var facts = new OtherVehicleRiding.Facts(in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readDouble(), in.readDouble(), in.readFloat());
                            var rider = new OtherVehicleRiding.Rider(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readBoolean());
                            int duration = kind < 2 ? in.readInt() : 0;
                            var controls = OtherVehicleOracleTest.state(in, kind, duration).copy();
                            physics.travelRidden(control, controls, facts, rider, true);
                            OtherVehicleOracleTest.compare(in, controls, kind, duration, context + " integrated controls");
                        } else
                            physics.travelBody(input, currentSpeed);
                        if (in.readBoolean())
                            physics.blockEffects();
                        PistonPhysicsOracleTest.compare(PistonPhysicsOracleTest.state(in), body, context);
                        assertEquals(in.readInt(), body.movementThisTick.size(), context + " records");
                        if (kind == 1) {
                            physics.floatStrider(control, state);
                            PistonPhysicsOracleTest.compare(PistonPhysicsOracleTest.state(in), body, context + " floating");
                            assertEquals(in.readInt(), body.movementThisTick.size(), context + " float records");
                        }
                        assertEquals(Set.of(), physics.unmodeled(), context);
                    }
                }
            assertEquals(-1, in.read());
        }
    }
}
