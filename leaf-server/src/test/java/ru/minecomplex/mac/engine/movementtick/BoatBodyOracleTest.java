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
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BoatBodyOracleTest {

    @Test
    void matchesActualBoatMovement() throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/boat-body.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414242, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int scenarios = in.readInt();
            assertEquals(120, scenarios);
            for (int s = 0; s < scenarios; s++) {
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
                boolean shift = in.readBoolean(), controlled = in.readBoolean();
                var body = PistonPhysicsOracleTest.state(in);
                assertEquals(0, in.readInt());
                body.fixedBodyDimensions = true;
                body.bodyWidth = width;
                body.bodyHeight = height;
                body.shiftKeyDown = shift;
                body.oldPosition = new Vec3d(0.5, 64.0, 0.5);
                body.fluidShapes = FluidShapeCache.empty();
                var boat = new BoatState(new Object(), width, height, 0.0F);
                boat.body = body;
                boat.lastYd = in.readDouble();
                var physics = new MovementTickerBoat(env, boat, null);
                physics.controls(controlled, true);
                for (int steps = in.readInt(), t = 0; t < steps; t++) {
                    Vec3d delta = PistonPhysicsOracleTest.vector(in);
                    boolean effects = in.readBoolean();
                    body.deltaMovement = delta;
                    physics.move(MovementTicker.MoverType.SELF, delta);
                    if (effects)
                        physics.applyEffectsFromBlocks();
                    String context = "26.3 scenario " + s + " step " + t;
                    PistonPhysicsOracleTest.compare(PistonPhysicsOracleTest.state(in), body, context);
                    assertEquals(in.readInt(), body.movementThisTick.size(), context + " recordings");
                    PistonPhysicsOracleTest.bits(in.readDouble(), boat.lastYd, context + " lastYd");
                    assertEquals(Set.of(), physics.unmodeled(), context);
                }
            }
            assertEquals(-1, in.read());
        }
    }
}
