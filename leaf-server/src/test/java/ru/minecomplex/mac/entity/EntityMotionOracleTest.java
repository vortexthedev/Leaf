package ru.minecomplex.mac.entity;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.DataInputStream;
import java.util.ArrayList;

import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.fixture.outbound.EntityMove;
import ru.minecomplex.mac.fixture.outbound.EntityPositionSync;
import ru.minecomplex.mac.fixture.outbound.EntityTeleport;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerFluidOracleTest;
import ru.minecomplex.mac.engine.movementtick.LocalTickOracle;
import ru.minecomplex.mac.mechanics.collision.EntityBodyQueries;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;

/**
 * MAEM (EntityMotionSamples): real move/teleport/position-sync packets through the real client handlers to a real boat,
 * raft and happy ghast with their real ticks; {@link EntityMotion} must reproduce position, rotation, ground flag and
 * codec base bit-exact after every packet and tick.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class EntityMotionOracleTest {

    private static void compare(DataInputStream in, EntityMotion motion, String context) throws Exception {
        var p = motion.body;
        FluidContactOracleTest.bits(in.readDouble(), p.x(), context + " x");
        FluidContactOracleTest.bits(in.readDouble(), p.y(), context + " y");
        FluidContactOracleTest.bits(in.readDouble(), p.z(), context + " z");
        assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(p.yRot), context + " yaw");
        assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(p.xRot), context + " pitch");
        assertEquals(in.readBoolean(), p.onGround, context + " ground");
        FluidContactOracleTest.bits(in.readDouble(), motion.codecBase.x(), context + " base x");
        FluidContactOracleTest.bits(in.readDouble(), motion.codecBase.y(), context + " base y");
        FluidContactOracleTest.bits(in.readDouble(), motion.codecBase.z(), context + " base z");
    }

    @Test
    void clientMovesOtherEntitiesLikeVanilla() throws Exception {
        var registry = BlockRegistry.get();
        var types = EntityTypes.get();
        int stone = registry.block("minecraft:stone").firstStateId();
        var blocks = new ArrayList<LocalTickOracle.Block>();
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) blocks.add(new LocalTickOracle.Block(x, 63, z, stone));
        for (int y = 64; y <= 66; y++) blocks.add(new LocalTickOracle.Block(3, y, 3, stone));
        var env = LocalPlayerFluidOracleTest.environment(registry, blocks);
        String path = "/vanilla/777/entity-motion.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        int events = 0;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d41454d, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int kinds = in.readInt();
            assertEquals(3, kinds);
            for (int k = 0; k < kinds; k++) {
                String typeKey = in.readUTF();
                boolean living = typeKey.equals("minecraft:happy_ghast");
                var type = types.type(typeKey);
                var dimensions = living ? types.livingDimensions(types.rideable(typeKey), false, Pose.STANDING, 1.0F) : type.dimensions();
                for (int scenarios = in.readInt(), s = 0; s < scenarios; s++) {
                    var motion = new EntityMotion(living, type.updateInterval(), dimensions.width(), dimensions.height());
                    var queries = new EntityBodyQueries(env, motion.body);
                    motion.spawn(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat());
                    String context0 = "26.3 " + typeKey + " scenario " + s;
                    compare(in, motion, context0 + " spawn");
                    for (int n = in.readInt(), e = 0; e < n; e++) {
                        int event = in.readUnsignedByte();
                        String context = context0 + " event " + e + " type " + event;
                        if (event == 3) {
                            motion.tick(queries::free);
                            compare(in, motion, context);
                            events++;
                            continue;
                        }
                        boolean ticking = in.readBoolean(), controlled = in.readBoolean();
                        if (event == 0) {
                            boolean hasPos = in.readBoolean(), hasRot = in.readBoolean();
                            byte yRot = in.readByte(), xRot = in.readByte();
                            boolean onGround = in.readBoolean();
                            int steps = in.readInt();
                            short[][] deltas = new short[Math.max(steps, 1)][3];
                            int[] ticks = new int[Math.max(steps, 1)];
                            for (int i = 0; i < deltas.length; i++) {
                                for (int a = 0; a < 3; a++) deltas[i][a] = in.readShort();
                                ticks[i] = in.readInt();
                            }
                            EntityMove move = steps == 0 ? EntityMove.linear(7, hasPos, hasRot, deltas[0][0], deltas[0][1], deltas[0][2], yRot, xRot, onGround) : EntityMove.stepped(7, hasRot, deltas, ticks, yRot, xRot, onGround);
                            motion.move(Wire.of(move), controlled);
                        } else {
                            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                            double dx = in.readDouble(), dy = in.readDouble(), dz = in.readDouble();
                            float yRot = in.readFloat(), xRot = in.readFloat();
                            int mask = in.readInt();
                            boolean onGround = in.readBoolean();
                            int steps = in.readInt();
                            double[][] positions = new double[steps][3];
                            int[] offsets = new int[steps];
                            for (int i = 0; i < steps; i++) {
                                for (int a = 0; a < 3; a++) positions[i][a] = in.readDouble();
                                offsets[i] = in.readInt();
                            }
                            if (event == 1) {
                                motion.teleport(Wire.of(new EntityTeleport(7, x, y, z, dx, dy, dz, yRot, xRot, mask, onGround)), controlled, ticking);
                            } else {
                                var sync = steps == 0 ? EntityPositionSync.linear(7, x, y, z, yRot, xRot, onGround) : EntityPositionSync.stepped(7, positions, offsets, yRot, xRot, onGround);
                                motion.sync(Wire.of(sync), controlled, ticking);
                            }
                        }
                        compare(in, motion, context);
                        events++;
                    }
                }
            }
            assertEquals(-1, in.read());
        }
        assertEquals(true, events > 1000, "26.3 " + events);
    }
}
