package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.fixture.outbound.EntityAnimation;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.world.BedStandUp;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * The own player in a bed: {@code LivingEntity.onSyncedDataUpdated(SLEEPING_POS_ID)} puts it on the bed
 * ({@code setPosToBed}), {@code handleAnimate}'s wake-up runs {@code stopSleepInBed} → {@code stopSleeping}.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class SleepTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeNativePackets() { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }

    private static final int ME = 7;

    /** Where {@code stopSleeping} stands the player up in {@link #bed} (a loaded bed). */
    private static final BedStandUp.Outcome STAND_UP = new BedStandUp.Outcome(new Vec3d(11.5, 64.5625, 10.5), 90.0F, true);

    /** A bed at (10, 64, 10) in a loaded chunk, or {@code sleepHeight} there (26.3). */
    private static ClientPlayerActions.Target bed(Double sleepHeight, boolean loaded) {
        return bed(sleepHeight, loaded ? STAND_UP : BedStandUp.Outcome.STAYS);
    }

    private static ClientPlayerActions.Target bed(Double sleepHeight, BedStandUp.Outcome outcome) {
        return new ClientPlayerActions.Target() {
            @Override public String block(int x, int y, int z) { return "minecraft:red_bed"; }
            @Override public String entityType(int entityId) { return null; }
            @Override public Double sleepHeight(int x, int y, int z) { return sleepHeight; }
            @Override public BedStandUp.Outcome stopSleeping(int x, int y, int z, float yRot, Set<Unmodeled> gaps) {
                assertEquals(List.of(10, 64, 10), List.of(x, y, z));
                return outcome;
            }
        };
    }

    private static EntitySyncedData sleeping(boolean asleep) {
        return new EntitySyncedData(ME, EntitySyncedData.SLEEPING_POS, (byte) 0, false, "", (byte) 0, 0.0F, new byte[0],
                asleep, 10, 64, 10);
    }

    private static ClientPlayerHandlers.Result apply(PlayerState p, ru.minecomplex.mac.fixture.outbound.OutboundEvent event,
                                                     ClientPlayerActions.Target target) {
        ClientPlayerData data = new ClientPlayerData();
        data.entityId = ME;
        return new ClientPlayerHandlers().apply(p, new LocalPlayerState(), data, Wire.of(event, Wire.FACTS), target);
    }

    private static PlayerState standing() {
        PlayerState p = new PlayerState();
        p.position = new Vec3d(9.2, 64.0, 11.7);
        return p;
    }

    @Test
    void theClientPutsTheSleeperOnItsBed() {
        PlayerState p = standing();
        var result = apply(p, sleeping(true), bed(0.5625, true));
        assertEquals(Set.of(), result.gaps());
        assertTrue(p.sleeping);
        assertEquals(new Vec3d(10.5, 64.6875, 10.5), p.position);
        // 26.3: the bed's sleep height plus 0.125
        PlayerState straw = standing();
        apply(straw, sleeping(true), bed(0.25, true));
        assertEquals(new Vec3d(10.5, 64.0 + 0.25 + 0.125, 10.5), straw.position);
        // 26.3 on no bed: the position stays; a bed class not known: reported
        PlayerState none = standing();
        apply(none, sleeping(true), bed(null, true));
        assertEquals(new Vec3d(9.2, 64.0, 11.7), none.position);
        assertTrue(none.sleeping);
        assertEquals(Set.of(Unmodeled.BED_STAND_UP), apply(standing(), sleeping(true), bed(Double.NaN, true)).gaps());
        // the sleeping position cleared
        apply(p, sleeping(false), bed(0.5625, true));
        assertFalse(p.sleeping);
        assertNull(p.sleepingPos);
    }

    @Test
    void aWakeUpStandsThePlayerUpWhereTheClientFindsRoomTurnedToTheBed() {
        PlayerState p = standing();
        apply(p, sleeping(true), bed(0.5625, true));
        p.pose = Pose.SLEEPING;
        // another action (particles, 2) changes nothing; the wake-up is action 0
        assertEquals(Set.of(), apply(p, new EntityAnimation(ME, 2), bed(0.5625, true)).gaps());
        assertTrue(p.sleeping);
        p.xRot = 40.0F;
        var result = apply(p, new EntityAnimation(ME, 0), bed(0.5625, true));
        assertEquals(Set.of(), result.gaps());
        assertEquals(STAND_UP.position(), p.position);
        assertEquals(90.0F, p.yRot);
        assertEquals(0.0F, p.xRot);
        assertFalse(p.sleeping);
        assertNull(p.sleepingPos);
        assertEquals(Pose.STANDING, p.pose);
        // a stand-up position not known (a straw bed, a border phase): reported until the server's absolute teleport
        PlayerState unknown = standing();
        apply(unknown, sleeping(true), bed(0.25, true));
        var unknownResult = apply(unknown, new EntityAnimation(ME, 0), bed(0.25, BedStandUp.Outcome.UNKNOWN));
        assertEquals(Set.of(Unmodeled.BED_STAND_UP), unknownResult.gaps());
        assertEquals(Pose.STANDING, unknown.pose);
        var teleport = apply(unknown, new PlayerPosition(1, 11.5, 64.5625, 10.5, 0, 0, 0, 0.0F, 0.0F, 0), bed(0.25, true));
        assertTrue(teleport.resolves().contains(Unmodeled.BED_STAND_UP));
        // no chunk at the bed: stopSleeping keeps the position
        PlayerState noChunk = standing();
        apply(noChunk, sleeping(true), bed(0.5625, true));
        Vec3d onBed = noChunk.position;
        assertEquals(Set.of(), apply(noChunk, new EntityAnimation(ME, 0), bed(0.5625, false)).gaps());
        assertEquals(onBed, noChunk.position);
        assertFalse(noChunk.sleeping);
        // another entity's wake-up is not the player's
        PlayerState other = standing();
        apply(other, sleeping(true), bed(0.5625, true));
        assertEquals(Set.of(), apply(other, new EntityAnimation(ME + 1, 0), bed(0.5625, true)).gaps());
        assertTrue(other.sleeping);
        assertEquals(List.of(10, 64, 10), java.util.Arrays.stream(other.sleepingPos).boxed().toList());
    }

    /** {@code LivingEntity.tick}: a sleeping player's pitch is set to 0 after its {@code aiStep}. */
    @Test
    void aSleeperLooksStraightAhead() {
        PlayerState p = standing();
        p.sleeping = true;
        p.xRot = 35.0F;
        var env = new ru.minecomplex.mac.engine.MovementEnvironment() {
            @Override public boolean fluidsResolved() { return true; }
            @Override public ru.minecomplex.mac.world.BlockSource blocks() { return (x, y, z) -> 0; }
            @Override public ru.minecomplex.mac.world.BlockRegistry registry() { return ru.minecomplex.mac.world.BlockRegistry.get(); }
            @Override public ru.minecomplex.mac.world.BlockTags tags() { return ru.minecomplex.mac.world.BlockTags.NONE; }
            @Override public boolean hasChunkAt(int x, int z) { return true; }
            @Override public int minY() { return -64; }
            @Override public java.util.List<ru.minecomplex.mac.collision.VoxelShape> entityCollisions(ru.minecomplex.mac.collision.Aabb box) { return java.util.List.of(); }
            @Override public ru.minecomplex.mac.collision.VoxelShape borderShape(ru.minecomplex.mac.collision.Aabb box) { return null; }
        };
        new MovementTickerPlayer(env, p, new LocalPlayerState(), FixturePlayers.loaded())
                .tick(ru.minecomplex.mac.player.state.PlayerInput.fromMask(0));
        assertEquals(0.0F, p.xRot);
        assertEquals(Pose.SLEEPING, p.pose);
    }
}
