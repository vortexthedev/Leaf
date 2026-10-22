package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.*;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
public class PistonPhysicsOracleTest {

    public static Vec3d vector(DataInputStream in) throws IOException {
        return new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
    }

    public static PlayerState state(DataInputStream in) throws IOException {
        var p = new PlayerState();
        p.position = vector(in);
        p.deltaMovement = vector(in);
        p.stuckSpeedMultiplier = vector(in);
        p.onGround = in.readBoolean();
        p.horizontalCollision = in.readBoolean();
        p.verticalCollision = in.readBoolean();
        p.verticalCollisionBelow = in.readBoolean();
        p.minorHorizontalCollision = in.readBoolean();
        p.onGroundNoBlocks = in.readBoolean();
        p.isInPowderSnow = in.readBoolean();
        p.fallDistance = in.readDouble();
        return p;
    }

    public static void bits(double e, double a, String message) {
        assertEquals(Double.doubleToLongBits(e), Double.doubleToLongBits(a), message);
    }

    public static void compare(PlayerState e, PlayerState a, String message) {
        for (Axis axis : Axis.values()) {
            bits(e.position.get(axis), a.position.get(axis), message + " position " + axis);
            bits(e.deltaMovement.get(axis), a.deltaMovement.get(axis), message + " velocity " + axis);
            bits(e.stuckSpeedMultiplier.get(axis), a.stuckSpeedMultiplier.get(axis), message + " stuck " + axis);
        }
        assertEquals(e.onGround, a.onGround, message + " ground");
        assertEquals(e.horizontalCollision, a.horizontalCollision, message + " horizontal");
        assertEquals(e.verticalCollision, a.verticalCollision, message + " vertical");
        assertEquals(e.verticalCollisionBelow, a.verticalCollisionBelow, message + " below");
        assertEquals(e.minorHorizontalCollision, a.minorHorizontalCollision, message + " minor");
        assertEquals(e.onGroundNoBlocks, a.onGroundNoBlocks, message + " support");
        assertEquals(e.isInPowderSnow, a.isInPowderSnow, message + " powder");
        bits(e.fallDistance, a.fallDistance, message + " fall");
    }

    @Test
    void matchesVanilla() throws Exception {
        var registry = BlockRegistry.get();
        var raw = getClass().getResourceAsStream("/vanilla/777/piston-physics.bin.gz");
        assertNotNull(raw);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d415046, in.readInt());
            assertEquals(2, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int count = in.readInt();
            for (int s = 0; s < count; s++) {
                var map = new HashMap<BlockPosition, Integer>();
                for (int n = in.readInt(); n > 0; n--) map.put(new BlockPosition(in.readInt(), in.readInt(), in.readInt()), in.readInt());
                MovementEnvironment env = new MovementEnvironment() {

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
                var p = state(in);
                assertEquals(0, in.readInt());
                p.oldPosition = new Vec3d(0.5, 64, 0.5);
                var physics = new MovementTicker(env, p);
                int steps = in.readInt();
                for (int t = 0; t < steps; t++) {
                    long time = in.readLong();
                    BlockDirection direction = BlockDirection.values()[in.readInt()];
                    double amount = in.readDouble();
                    String message = "26.3 scenario " + s + " step " + t;
                    physics.moveByPiston(direction, new Vec3d(amount * direction.x, amount * direction.y, amount * direction.z), time);
                    compare(state(in), p, message);
                    assertEquals(in.readInt(), p.movementThisTick.size(), message + " movement recordings");
                    assertTrue(physics.unmodeled().isEmpty(), physics.unmodeled().toString());
                }
            }
            assertEquals(-1, in.read());
        }
    }
}
