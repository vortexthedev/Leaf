package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LocalPlayerCollisionOracleTest {

    @Test
    void directMethodsMatchVanilla() throws Exception {
        String fixture = "local-collisions";
        var registry = BlockRegistry.get();
        var resource = getClass().getResourceAsStream("/vanilla/777/" + fixture + ".bin.gz");
        assertNotNull(resource);
        int pushes = 0, suffocating = 0, minor = 0;
        try (var in = new DataInputStream(new GZIPInputStream(resource))) {
            assertEquals(0x4d414343, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            assertEquals(2048, in.readInt());
            for (int sample = 0; sample < 2048; sample++) {
                boolean allTags = in.readBoolean();
                var state = new PlayerState();
                state.position = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                state.attributes.get(Attribute.SCALE).setBaseValue(in.readFloat());
                state.pose = Pose.valueOf(in.readUTF());
                // the generator refreshes after scale and pose
                state.refreshDimensions();
                Vec3d initial = readVector(in);
                state.deltaMovement = initial;
                Map<BlockPos, Integer> blocks = new HashMap<>();
                int count = in.readInt();
                for (int i = 0; i < count; i++) blocks.put(new BlockPos(in.readInt(), in.readInt(), in.readInt()), in.readInt());
                BlockTags tags = (tag, block) -> allTags && tag == BlockTag.CAUSES_SUFFOCATION;
                var physics = new MovementTickerPlayer(environment(registry, blocks, tags), state, new LocalPlayerState(), FixturePlayers.loaded());
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                boolean expected = in.readBoolean();
                String context = "" + fixture + " sample " + sample;
                assertEquals(expected, physics.suffocatesAt(x, y, z), context);
                if (expected)
                    suffocating++;
                physics.moveTowardsClosestSpace(state.x(), state.z());
                assertVector(readVector(in), state.deltaMovement, context + " single");
                if (!state.deltaMovement.equals(initial))
                    pushes++;
                state.deltaMovement = initial;
                physics.pushOutOfBlocks();
                assertVector(readVector(in), state.deltaMovement, context + " corners");
                assertTrue(physics.unmodeled().isEmpty(), context);
            }
            assertEquals(8192, in.readInt());
            var state = new PlayerState();
            var physics = new MovementTickerPlayer(environment(registry, Map.of(), BlockTags.NONE), state, new LocalPlayerState(), FixturePlayers.loaded());
            for (int sample = 0; sample < 8192; sample++) {
                state.yRot = in.readFloat();
                state.xxa = in.readFloat();
                state.zza = in.readFloat();
                Vec3d movement = new Vec3d(in.readDouble(), 0.0, in.readDouble());
                boolean expected = in.readBoolean();
                assertEquals(expected, physics.isHorizontalCollisionMinor(movement), "minor " + sample);
                if (expected)
                    minor++;
            }
            assertEquals(-1, in.read());
        }
        assertTrue(suffocating > 100);
        assertTrue(pushes > 50);
        assertTrue(minor > 100);
    }

    @Test
    void phaseGuardsAndBlockEntityUncertainty() {
        var registry = BlockRegistry.get();
        int stone = registry.block("minecraft:stone").firstStateId();
        var blocks = Map.of(new BlockPos(0, 64, 0), stone, new BlockPos(0, 65, 0), stone);
        BlockTags tags = (tag, block) -> tag == BlockTag.CAUSES_SUFFOCATION;
        var state = new PlayerState();
        state.position = new Vec3d(0.5, 64.0, 0.5);
        Vec3d original = new Vec3d(0.25, -0.0, 0.375);
        state.deltaMovement = original;
        state.noPhysics = true;
        var physics = new MovementTickerPlayer(environment(registry, blocks, tags), state, new LocalPlayerState(), FixturePlayers.loaded());
        physics.pushOutOfBlocks();
        assertVector(original, state.deltaMovement, "noPhysics");
        state.noPhysics = false;
        state.passenger = true;
        physics.pushOutOfBlocks();
        assertVector(original, state.deltaMovement, "passenger");
        int shulker = registry.block("minecraft:shulker_box").firstStateId();
        var unknown = new MovementTickerPlayer(environment(registry, Map.of(new BlockPos(0, 64, 0), shulker), tags), state, new LocalPlayerState(), FixturePlayers.loaded());
        unknown.pushOutOfBlocks();
        // Passenger exclusion avoids any suffocation query in 26.3; query directly to check the missing state.
        unknown.suffocatesAt(0, 64, 0);
        assertTrue(unknown.unmodeled().contains(Unmodeled.BLOCK_ENTITY_SUFFOCATION));
    }

    private record BlockPos(int x, int y, int z) {
    }

    private static MovementEnvironment environment(BlockRegistry registry, Map<BlockPos, Integer> blocks, BlockTags tags) {
        return new MovementEnvironment() {

            public BlockSource blocks() {
                return (x, y, z) -> blocks.getOrDefault(new BlockPos(x, y, z), 0);
            }

            public BlockRegistry registry() {
                return registry;
            }

            public BlockTags tags() {
                return tags;
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
    }

    private static Vec3d readVector(DataInputStream in) throws Exception {
        return new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
    }

    private static void assertVector(Vec3d expected, Vec3d actual, String context) {
        assertEquals(Double.doubleToLongBits(expected.x()), Double.doubleToLongBits(actual.x()), context + " x");
        assertEquals(Double.doubleToLongBits(expected.y()), Double.doubleToLongBits(actual.y()), context + " y");
        assertEquals(Double.doubleToLongBits(expected.z()), Double.doubleToLongBits(actual.z()), context + " z");
    }
}
