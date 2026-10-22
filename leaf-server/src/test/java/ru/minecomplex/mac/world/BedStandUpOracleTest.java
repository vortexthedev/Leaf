package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.fixture.outbound.EntityAnimation;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.replica.CompensatedWorld;

/**
 * MABS ({@code tools/datagen/.../BedStandUpSamples.java}): a real vanilla player put on its bed ({@code setPosToBed})
 * and woken up ({@code stopSleepInBed} → {@code stopSleeping} → {@code findStandUpPosition}) among random blocks; the
 * model, given the same blocks, the {@code SLEEPING_POS} data and the wake-up animation, stands up at the same position
 * with the same rotation, bit for bit.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BedStandUpOracleTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeNativePackets() { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }

    private static final int ME = 1;

    /** The tags the generator binds to its palette. */
    private static BlockTags tags() {
        return (tag, block) -> switch (tag) {
            case CLIMBABLE -> block.name().equals("minecraft:ladder") || block.name().equals("minecraft:vine");
            case FIRE -> block.name().equals("minecraft:fire") || block.name().equals("minecraft:soul_fire");
            case CAMPFIRES -> block.name().equals("minecraft:campfire");
            case INVALID_SPAWN_INSIDE -> block.name().equals("minecraft:end_portal");
            default -> false;
        };
    }

    @Test
    void playersStandUpWhereVanillaPutsThem() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/" + ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL + "/bed-stand-up.bin.gz");
        assertNotNull(raw, "Missing /vanilla/" + ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL + "/bed-stand-up.bin.gz (tools/datagen/run_bed_stand_up.py)");
        BlockRegistry registry = BlockRegistry.get();
        var handlers = new ClientPlayerHandlers();
        BlockTags tags = tags();
        int bits = 32 - Integer.numberOfLeadingZeros(registry.stateCount() - 1);
        int wake = 0;
        int moved = 0, stayed = 0;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414253, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int samples = in.readInt();
            for (int sample = 0; sample < samples; sample++) {
                CompensatedWorld world = new CompensatedWorld("minecraft:overworld", -64, 384, 8, registry);
                for (int cx = -1; cx <= 0; cx++) {
                    for (int cz = -1; cz <= 0; cz++) {
                        PalettedSection[] sections = new PalettedSection[24];
                        for (int i = 0; i < sections.length; i++) sections[i] = PalettedSection.single(CompensatedWorld.AIR, bits);
                        world.load(new ChunkColumn(cx, cz, sections));
                    }
                }
                int blocks = in.readInt();
                for (int b = 0; b < blocks; b++) world.setBlock(in.readInt(), in.readInt(), in.readInt(), in.readInt());
                int bx = in.readInt(), by = in.readInt(), bz = in.readInt();
                PlayerState p = new PlayerState();
                p.position = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                p.yRot = in.readFloat();
                p.xRot = in.readFloat();
                Vec3d onBed = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                Vec3d standing = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                float yRot = in.readFloat(), xRot = in.readFloat();
                String context = "sample " + sample;

                ClientPlayerActions.Target target = new ClientPlayerActions.Target() {
                    @Override public String block(int x, int y, int z) {
                        int state = world.getBlock(x, y, z);
                        return state == CompensatedWorld.UNLOADED ? null : registry.block(state).name();
                    }
                    @Override public String entityType(int entityId) { return null; }
                    @Override public Double sleepHeight(int x, int y, int z) { return world.sleepHeight(x, y, z); }
                    @Override public BedStandUp.Outcome stopSleeping(int x, int y, int z, float yaw, Set<Unmodeled> gaps) {
                        return BedStandUp.stopSleeping(world, registry, tags, x, y, z, yaw, gaps);
                    }
                };
                ClientPlayerData data = new ClientPlayerData();
                data.entityId = ME;
                var sleep = handlers.apply(p, new LocalPlayerState(), data, Wire.of(new EntitySyncedData(ME, EntitySyncedData.SLEEPING_POS,
                        (byte) 0, false, "", (byte) 0, 0.0F, new byte[0], true, bx, by, bz)), target);
                assertEquals(Set.of(), sleep.gaps(), context);
                assertBits(onBed, p.position, context + " on the bed");
                Vec3d before = p.position;
                var woken = handlers.apply(p, new LocalPlayerState(), data, Wire.of(new EntityAnimation(ME, wake), Wire.FACTS), target);
                assertFalse(woken.gaps().contains(Unmodeled.BED_STAND_UP), context + " " + woken.gaps());
                assertBits(standing, p.position, context + " standing");
                assertEquals(Float.floatToIntBits(yRot), Float.floatToIntBits(p.yRot), context + " yaw " + p.yRot + " vs " + yRot);
                assertEquals(Float.floatToIntBits(xRot), Float.floatToIntBits(p.xRot), context + " pitch");
                if (before.equals(p.position)) stayed++;
                else moved++;
            }
        }
        assertTrue(moved > stayed && stayed > 0, "moved " + moved + ", stayed " + stayed);
    }

    private static void assertBits(Vec3d expected, Vec3d actual, String context) {
        String message = context + " " + actual + " vs " + expected;
        assertEquals(Double.doubleToLongBits(expected.x()), Double.doubleToLongBits(actual.x()), message);
        assertEquals(Double.doubleToLongBits(expected.y()), Double.doubleToLongBits(actual.y()), message);
        assertEquals(Double.doubleToLongBits(expected.z()), Double.doubleToLongBits(actual.z()), message);
    }
}
