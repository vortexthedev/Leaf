package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.entity.interpolation.BoatInterpolationOracleTest;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BoatTickOracleTest {

    record Recorded(PlayerState body, int moves, BoatMotionOracleTest.Sample motion, float outOfControl, boolean firstTick, int tickCount, double water, double lava, boolean inWater, boolean inLava, boolean eyeWater, boolean eyeLava) {

        static Recorded read(DataInputStream in) throws IOException {
            return new Recorded(PistonPhysicsOracleTest.state(in), in.readInt(), BoatMotionOracleTest.Sample.read(in), in.readFloat(), in.readBoolean(), in.readInt(), in.readDouble(), in.readDouble(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean());
        }

        void compare(BoatState actual, String context) {
            PistonPhysicsOracleTest.compare(body, actual.body, context);
            assertEquals(moves, actual.body.movementThisTick.size(), context + " recordings");
            motion.compare(actual, context);
            BoatEnvironmentOracleTest.bits(outOfControl, actual.outOfControlTicks, context + " out of control");
            assertEquals(firstTick, actual.body.firstTick, context);
            assertEquals(tickCount, actual.tickCount, context);
            PistonPhysicsOracleTest.bits(water, actual.body.waterHeight, context + " water height");
            PistonPhysicsOracleTest.bits(lava, actual.body.lavaHeight, context + " lava height");
            assertEquals(inWater, actual.body.wasTouchingWater, context);
            assertEquals(inLava, actual.body.isInLava, context);
            assertEquals(eyeWater, actual.body.eyeInWater, context);
            assertEquals(eyeLava, actual.body.eyeInLava, context);
        }
    }

    @Test
    void matchesChainedActualBoatTicks() throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/boat-tick.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414254, in.readInt());
            assertEquals(2, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int scenarios = in.readInt();
            assertEquals(64, scenarios);
            for (int s = 0; s < scenarios; s++) {
                var map = new HashMap<BlockPosition, Integer>();
                for (int n = in.readInt(); n > 0; n--) map.put(new BlockPosition(in.readInt(), in.readInt(), in.readInt()), in.readInt());
                MovementEnvironment env = new MovementEnvironment() {

                    public boolean fluidsResolved() {
                        return true;
                    }

                    public Tri fastLava() {
                        return Tri.FALSE;
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

                    public List<VoxelShape> entityCollisions(Aabb b) {
                        return List.of();
                    }

                    public VoxelShape borderShape(Aabb b) {
                        return null;
                    }
                };
                boolean noGravity = in.readBoolean();
                float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat();
                var initial = Recorded.read(in);
                var state = new BoatState(new Object(), width, height, eye);
                state.body = initial.body;
                state.body.fixedBodyDimensions = true;
                state.body.bodyWidth = width;
                state.body.bodyHeight = height;
                state.body.firstTick = initial.firstTick;
                state.body.yRot = initial.motion.yaw();
                state.body.fluidShapes = FluidShapeCache.empty();
                initial.motion.applyTo(state, false);
                state.outOfControlTicks = initial.outOfControl;
                state.tickCount = initial.tickCount;
                state.noGravity = noGravity;
                var physics = new MovementTickerBoat(env, state, box -> List.of());
                for (int steps = in.readInt(), t = 0; t < steps; t++) {
                    int flags = in.readUnsignedByte();
                    boolean authority = in.readBoolean(), driver = in.readBoolean(), controlled = in.readBoolean();
                    if (in.readBoolean()) {
                        var target = BoatInterpolationOracleTest.vector(in);
                        float yaw = in.readFloat(), pitch = in.readFloat();
                        state.interpolation.receive(state.body, target, yaw, pitch);
                    }
                    state.input = new PlayerInput((flags & 4) != 0, (flags & 8) != 0, (flags & 1) != 0, (flags & 2) != 0, false, false, false);
                    var result = physics.tick(true, driver, false, controlled, authority ? Tri.TRUE : Tri.FALSE);
                    String context = "26.3 scenario " + s + " tick " + t;
                    Recorded.read(in).compare(state, context);
                    var packets = new ArrayList<InboundEvent>();
                    for (int n = in.readInt(); n > 0; n--) packets.add(LocalPlayerOracleCodec.packet(in));
                    assertEquals(packets, Wire.in(result.packets()), context + " packets");
                    assertEquals(Set.of(), result.unmodeled(), context);
                }
            }
            assertEquals(-1, in.read());
        }
    }
}
