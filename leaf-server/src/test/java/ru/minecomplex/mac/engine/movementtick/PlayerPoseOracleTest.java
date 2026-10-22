package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PlayerPoseOracleTest {
    @Test
    void poseFitAndRefreshedBoxMatchVanilla() throws Exception {
        String fixture = "player-poses";
        var registry = BlockRegistry.get();
        var resource = getClass().getResourceAsStream("/vanilla/777/" + fixture + ".bin.gz");
        assertNotNull(resource);
        int changed = 0, rejected = 0;
        try (var in = new DataInputStream(new GZIPInputStream(resource))) {
            assertEquals(0x4d414350, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            assertEquals(1024, in.readInt());
            for (int sample = 0; sample < 1024; sample++) {
                PlayerState state = new PlayerState();
                state.position = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                state.attributes.get(Attribute.SCALE).setBaseValue(in.readFloat());
                int flags = in.readInt();
                state.sleeping = (flags & 1) != 0;
                state.swimming = (flags & 2) != 0;
                state.fallFlying = (flags & 4) != 0;
                state.autoSpinAttack = (flags & 8) != 0;
                state.shiftKeyDown = (flags & 16) != 0;
                state.spectator = (flags & 32) != 0;
                state.passenger = (flags & 64) != 0;
                state.abilities.flying = in.readBoolean();
                state.pose = Pose.values()[in.readInt()];
                state.refreshDimensions(); // the generator refreshes after scale and pose
                Pose initial = state.pose;
                Map<String, Integer> blocks = new HashMap<>();
                int count = in.readInt();
                for (int i = 0; i < count; i++) blocks.put(key(in.readInt(), in.readInt(), in.readInt()), in.readInt());
                MovementEnvironment env = new MovementEnvironment() {
                    public BlockSource blocks() { return (x, y, z) -> blocks.getOrDefault(key(x, y, z), 0); }
                    public BlockRegistry registry() { return registry; }
                    public BlockTags tags() { return BlockTags.NONE; }
                    public boolean hasChunkAt(int x, int z) { return true; }
                    public int minY() { return -64; }
                    public List<VoxelShape> entityCollisions(Aabb box) { return List.of(); }
                    public VoxelShape borderShape(Aabb box) { return null; }
                };
                var physics = new MovementTicker(env, state);
                String context = "" + fixture + " sample " + sample;
                for (Pose pose : Pose.PLAYER_POSES) {
                    boolean expected = in.readBoolean();
                    assertEquals(expected, physics.canFit(pose), context + " " + pose);
                    if (!expected) rejected++;
                }
                physics.updatePlayerPose();
                assertEquals(Pose.valueOf(in.readUTF()), state.pose, context);
                Aabb box = state.boundingBox();
                for (double face : new double[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()}) {
                    assertEquals(Double.doubleToLongBits(in.readDouble()), Double.doubleToLongBits(face), context);
                }
                assertTrue(physics.unmodeled().isEmpty(), context);
                assertEquals(state.pose, state.copy().pose);
                assertEquals(state.spectator, state.copy().spectator);
                assertEquals(state.autoSpinAttack, state.copy().autoSpinAttack);
                if (state.pose != initial) changed++;
            }
            assertEquals(-1, in.read());
        }
        assertTrue(changed > 100, "must exercise pose refresh");
        assertTrue(rejected > 100, "must exercise obstructed poses");
    }

    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }
}
