package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import ru.minecomplex.mac.packet.NativeRuntime;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.util.collection.LongMap;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Differential test of {@link MovementTicker} against vanilla: every tick of {@code vanilla/777/movement.bin.gz}
 * ({@code tools/datagen} {@code MovementSamples}) is replayed from its recorded state, and the state after
 * {@code aiStep} must equal vanilla's bit for bit, signed zeros included.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
public class MovementOracleTest {

    @Test
    void matchesVanillaAiStep() throws IOException {
        BlockRegistry registry = BlockRegistry.get();
        Oracle oracle = Oracle.read();
        Map<String, Integer> mismatchesByField = new TreeMap<>();
        List<String> firstFailures = new ArrayList<>();
        Map<Unmodeled, Integer> unmodeledTicks = new java.util.EnumMap<>(Unmodeled.class);
        int ticks = 0;
        int compared = 0;
        for (Scenario scenario : oracle.scenarios) {
            MovementEnvironment environment = environment(registry, scenario.blocks);
            for (Tick tick : scenario.ticks) {
                ticks++;
                PlayerState state = tick.before.toState();
                state.xxa = tick.xxa;
                state.zza = tick.zza;
                state.jumping = tick.jumping;
                MovementTicker physics = new MovementTicker(environment, state);
                physics.aiStep();
                if (!physics.unmodeled().isEmpty()) {
                    // not a prediction: the engine declares what it does not simulate (waterlogged obstacles)
                    for (Unmodeled reason : physics.unmodeled()) {
                        unmodeledTicks.merge(reason, 1, Integer::sum);
                    }
                    continue;
                }
                compared++;
                List<String> differences = tick.after.differences(Recorded.of(state));
                for (String difference : differences) {
                    mismatchesByField.merge(difference.substring(0, difference.indexOf(':')), 1, Integer::sum);
                }
                if (!differences.isEmpty() && firstFailures.size() < 5) {
                    firstFailures.add("scenario " + scenario.index + " tick " + tick.index + " input(" + tick.xxa + ", " + tick.zza + ", " + tick.jumping
                            + ")\n  before " + tick.before + "\n  " + String.join("\n  ", differences));
                }
            }
        }
        int total = ticks;
        int exact = compared;
        System.out.println("26.3" + ": " + compared + "/" + total + " ticks compared, unmodelled " + unmodeledTicks
                + ", mismatching fields " + mismatchesByField);
        assertEquals(0, mismatchesByField.size(), () -> "26.3" + ": " + exact + "/" + total + " ticks compared, unmodelled "
                + unmodeledTicks + ", mismatching fields " + mismatchesByField + "\n" + String.join("\n", firstFailures));
        assertTrue(compared > total * 8 / 10, () -> "too few ticks compared: " + exact + "/" + total + " " + unmodeledTicks);
    }

    private static MovementEnvironment environment(BlockRegistry registry, LongMap<Integer> blocks) {
        BlockSource source = (x, y, z) -> {
            Integer state = blocks.get(key(x, y, z));
            return state == null ? 0 : state;
        };
        return new MovementEnvironment() {
            @Override
            public BlockSource blocks() {
                return source;
            }

            @Override
            public BlockRegistry registry() {
                return registry;
            }

            @Override
            public BlockTags tags() {
                return BlockTags.NONE;
            }

            @Override
            public boolean hasChunkAt(int blockX, int blockZ) {
                return true;
            }

            @Override
            public int minY() {
                return -64;
            }

            @Override
            public List<VoxelShape> entityCollisions(Aabb box) {
                return List.of();
            }

            @Override
            public VoxelShape borderShape(Aabb box) {
                return null;
            }
        };
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    record Oracle(List<Scenario> scenarios) {
        static Oracle read() throws IOException {
            String resource = "/vanilla/" + NativeRuntime.PROTOCOL + "/movement.bin.gz";
            try (InputStream raw = MovementOracleTest.class.getResourceAsStream(resource)) {
                if (raw == null) {
                    throw new IOException("Missing " + resource);
                }
                DataInputStream in = new DataInputStream(new GZIPInputStream(raw, 1 << 16));
                if (in.readInt() != 0x4D41434D) {
                    throw new IOException("Not a movement oracle");
                }
                if (in.readShort() != 1) {
                    throw new IOException("Unsupported movement oracle format");
                }
                if (in.readInt() != NativeRuntime.PROTOCOL) {
                    throw new IOException("Oracle of another protocol");
                }
                org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
                int count = in.readInt();
                List<Scenario> scenarios = new ArrayList<>(count);
                for (int s = 0; s < count; s++) {
                    LongMap<Integer> blocks = new LongMap<>();
                    int blockCount = in.readInt();
                    for (int b = 0; b < blockCount; b++) {
                        int x = in.readInt();
                        int y = in.readInt();
                        int z = in.readInt();
                        blocks.put(key(x, y, z), in.readInt());
                    }
                    int tickCount = in.readInt();
                    List<Tick> ticks = new ArrayList<>(tickCount);
                    for (int t = 0; t < tickCount; t++) {
                        Recorded before = Recorded.read(in);
                        float xxa = in.readFloat();
                        float zza = in.readFloat();
                        boolean jumping = in.readBoolean();
                        Recorded after = Recorded.read(in);
                        ticks.add(new Tick(t, before, xxa, zza, jumping, after));
                    }
                    scenarios.add(new Scenario(s, blocks, ticks));
                }
                return new Oracle(scenarios);
            }
        }
    }

    record Scenario(int index, LongMap<Integer> blocks, List<Tick> ticks) {
    }

    record Tick(int index, Recorded before, float xxa, float zza, boolean jumping, Recorded after) {
    }

    /** One recorded state, in the oracle's field order. */
    public record Recorded(double x, double y, double z, double dx, double dy, double dz, float yRot, float xRot,
                    boolean onGround, boolean horizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow,
                    boolean minorHorizontalCollision, double fallDistance, double stuckX, double stuckY, double stuckZ,
                    boolean supporting, int supportingX, int supportingY, int supportingZ, boolean isInPowderSnow,
                    boolean wasInPowderSnow, boolean sprinting, boolean shift, int noJumpDelay) {

        static Recorded read(DataInputStream in) throws IOException {
            return new Recorded(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readFloat(), in.readFloat(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(),
                    in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readBoolean(), in.readInt(), in.readInt(),
                    in.readInt(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readInt());
        }

        public static Recorded of(PlayerState s) {
            return new Recorded(s.position.x(), s.position.y(), s.position.z(), s.deltaMovement.x(), s.deltaMovement.y(),
                    s.deltaMovement.z(), s.yRot, s.xRot, s.onGround, s.horizontalCollision, s.verticalCollision,
                    s.verticalCollisionBelow, s.minorHorizontalCollision, s.fallDistance, s.stuckSpeedMultiplier.x(),
                    s.stuckSpeedMultiplier.y(), s.stuckSpeedMultiplier.z(), s.hasSupportingBlock,
                    s.hasSupportingBlock ? s.supportingX : 0, s.hasSupportingBlock ? s.supportingY : 0,
                    s.hasSupportingBlock ? s.supportingZ : 0, s.isInPowderSnow, s.wasInPowderSnow, s.sprinting, s.shiftKeyDown,
                    s.noJumpDelay);
        }

        /**
         * The simulated state before a tick: every recorded field, the old position as {@code setOldPosAndRot} leaves
         * it, and {@code onGroundNoBlocks}, which {@code checkSupportingBlock} leaves equal to "on ground without a
         * supporting block".
         */
        public PlayerState toState() {
            PlayerState s = new PlayerState();
            s.position = new Vec3d(x, y, z);
            s.oldPosition = s.position;
            s.deltaMovement = new Vec3d(dx, dy, dz);
            s.yRot = yRot;
            s.xRot = xRot;
            s.onGround = onGround;
            s.horizontalCollision = horizontalCollision;
            s.verticalCollision = verticalCollision;
            s.verticalCollisionBelow = verticalCollisionBelow;
            s.minorHorizontalCollision = minorHorizontalCollision;
            s.fallDistance = fallDistance;
            s.stuckSpeedMultiplier = new Vec3d(stuckX, stuckY, stuckZ);
            s.hasSupportingBlock = supporting;
            s.supportingX = supportingX;
            s.supportingY = supportingY;
            s.supportingZ = supportingZ;
            s.onGroundNoBlocks = onGround && !supporting;
            s.isInPowderSnow = isInPowderSnow;
            s.wasInPowderSnow = wasInPowderSnow;
            s.setSprinting(sprinting);
            s.shiftKeyDown = shift;
            s.noJumpDelay = noJumpDelay;
            return s;
        }

        public List<String> differences(Recorded actual) {
            List<String> out = new ArrayList<>();
            compare(out, "x", x, actual.x);
            compare(out, "y", y, actual.y);
            compare(out, "z", z, actual.z);
            compare(out, "dx", dx, actual.dx);
            compare(out, "dy", dy, actual.dy);
            compare(out, "dz", dz, actual.dz);
            compare(out, "onGround", onGround, actual.onGround);
            compare(out, "horizontalCollision", horizontalCollision, actual.horizontalCollision);
            compare(out, "verticalCollision", verticalCollision, actual.verticalCollision);
            compare(out, "verticalCollisionBelow", verticalCollisionBelow, actual.verticalCollisionBelow);
            compare(out, "minorHorizontalCollision", minorHorizontalCollision, actual.minorHorizontalCollision);
            compare(out, "fallDistance", fallDistance, actual.fallDistance);
            compare(out, "stuckX", stuckX, actual.stuckX);
            compare(out, "stuckY", stuckY, actual.stuckY);
            compare(out, "stuckZ", stuckZ, actual.stuckZ);
            compare(out, "supporting", supporting, actual.supporting);
            if (supporting && actual.supporting) {
                compare(out, "supportingPos", supportingX + "," + supportingY + "," + supportingZ,
                        actual.supportingX + "," + actual.supportingY + "," + actual.supportingZ);
            }
            compare(out, "isInPowderSnow", isInPowderSnow, actual.isInPowderSnow);
            compare(out, "noJumpDelay", noJumpDelay, actual.noJumpDelay);
            return out;
        }

        private static void compare(List<String> out, String field, double expected, double actual) {
            if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
                out.add(field + ": vanilla " + expected + " ours " + actual + " (diff " + (actual - expected) + ")");
            }
        }

        private static void compare(List<String> out, String field, Object expected, Object actual) {
            if (!expected.equals(actual)) {
                out.add(field + ": vanilla " + expected + " ours " + actual);
            }
        }
    }
}
