package ru.minecomplex.mac.entity;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.replica.CompensatedEntities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkCacheCenter;
import ru.minecomplex.mac.fixture.outbound.ChunkCacheRadius;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import ru.minecomplex.mac.fixture.outbound.EntityPositionSync;
import ru.minecomplex.mac.fixture.outbound.EntityRemove;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntityTeleport;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.fixture.outbound.ForgetChunk;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.Respawn;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;

/** {@link EntityMotions} through the world model: tick list membership and order, ownership hand-over, gaps. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class EntityMotionsTest {
    static final int ME = 7;

    /** A persistent world hypothesis fed in wire order, every change certain at once. */
    static final class Wire {
        final WorldModel model = new WorldModel(BlockRegistry.get());
        final WorldModel.Hypothesis hypothesis;
        long seq;

        Wire() {
            model.sent(0, ru.minecomplex.mac.Wire.of(new JoinGame(ME, "minecraft:overworld", 0, 32, 2)));
            hypothesis = model.hypothesis(new SyncWindow(0, 1), 0);
        }

        Wire send(OutboundEvent... events) {
            for (OutboundEvent event : events) {
                seq++;
                model.sent(seq, ru.minecomplex.mac.Wire.of(event));
            }
            hypothesis.apply(new SyncWindow(seq, seq + 1), seq);
            return this;
        }

        CompensatedWorld world() {
            return hypothesis.world();
        }

        EntityMotions motions() {
            return world().entities().motions();
        }

        void tick() {
            world().tickEntities(true, (id, body, self, gaps) -> box -> true);
            world().tickEntities(false, (id, body, self, gaps) -> box -> true);
        }
    }

    static ChunkData chunk(int x, int z) {
        return new ChunkData(x, z, new int[]{0, 0}, new int[]{1, 1}, new int[]{0, 0}, new long[0], ChunkBlockEntities.encode(List.of()));
    }

    static EntitySpawn spawn(int id, String type, double x, double y, double z) {
        return new EntitySpawn(id, type, x, y, z, 0.0F, 0.0F, 0.0F, 0, false, 0.0, 0.0, 0.0);
    }

    @Test
    void earlyControllerHandlerAlternativeCopiesCodecGroundAndGenerationLifetime() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:pig", 8.5, 20, 8.5),
                new EntityPassengers(50, new int[]{ME}));
        wire.motions().localController(50, Tri.FALSE);
        wire.motions().beginEarlyController(50);
        assertNull(wire.motions().earlyControllerStart(50), "no handler ran");
        wire.send(EntityPositionSync.linear(50, 9.5, 20, 8.5, 90F, -40F, true));
        var start = wire.motions().earlyControllerStart(50);
        assertNotNull(start);
        assertEquals(new Vec3d(8.5, 20, 8.5), start.body.position);
        assertEquals(new Vec3d(9.5, 20, 8.5), start.codecBase);
        assertFalse(start.body.onGround, "owned position sync ignores even the ground flag");
        assertFalse(start.interpolating());
        start.body.position = Vec3d.ZERO;
        var fork = wire.world().detachedCopy();
        wire.motions().acceptEarlyController(50);
        assertEquals(new Vec3d(8.5, 20, 8.5), wire.motions().rideStartEstimate(50).body.position);
        assertEquals(new Vec3d(9.5, 20, 8.5), wire.motions().passengerVehicle(50).codecBase);
        assertNull(wire.motions().earlyControllerStart(50));
        var forkStart = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> fork.entities().motions().earlyControllerStart(50), "fork must expose a valid detached alternative");
        assertNotNull(forkStart, "fork owns its alternative");
        fork.entities().motions().beginEarlyController(null);
        assertNull(fork.entities().motions().earlyControllerStart(50), "alternatives expire next frame");
        wire.motions().beginEarlyController(50);
        wire.send(EntityPositionSync.linear(50, 10.5, 20, 8.5, 0F, 0F, true));
        wire.send(spawn(50, "minecraft:pig", 4.5, 20, 4.5));
        assertNull(wire.motions().earlyControllerStart(50), "replacement id cannot inherit a handler alternative");
    }

    @Test
    void steeringBoostStartsOnMetadataBeforeMountAndSurvivesWorldCopies() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:pig", 8.5, 20, 8.5));
        int boostId = EntityTypes.get().dataId("Pig.DATA_BOOST_TIME");
        var packet = new EntitySyncedData(50, 0, (byte)0, false, "", (byte)0, 0F,
                EntitySyncedData.encodeMobItems(java.util.Map.of(boostId, 120)));
        wire.send(packet);
        for (int tick = 0; tick < 7; tick++) wire.tick();
        var boost = wire.motions().steeringBoost(50);
        assertNotNull(boost);
        assertTrue(boost.boosting);
        assertEquals(0, boost.boostTime, "remote travel never invokes tickRidden/tickBoost");
        assertEquals(120, boost.boostDuration);
        boost.boostTime = 7;
        wire.motions().steeringBoost(50, boost, true);
        var copy = wire.world().detachedCopy();
        assertEquals(7, copy.entities().motions().steeringBoost(50).boostTime);
        wire.send(packet);
        assertEquals(0, wire.motions().steeringBoost(50).boostTime, "even a repeated assignment runs onSynced");
        assertEquals(7, copy.entities().motions().steeringBoost(50).boostTime);
        wire.send(spawn(50, "minecraft:pig", 4.5, 20, 4.5));
        assertFalse(wire.motions().steeringBoost(50).boosting, "a replacement entity has constructor boost state");
    }

    @Test
    void entitiesTickAfterTheLoggedInPlayerAndBeforeOneRespawnedBehindThem() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5));
        assertTrue(wire.motions().ticking(50));
        assertFalse(wire.motions().ticksBeforePlayer(50));
        wire.send(new Respawn("minecraft:overworld", 0, 0, 32));
        assertTrue(wire.motions().ticksBeforePlayer(50), "present at a same-level respawn");
        wire.send(spawn(51, "minecraft:bamboo_raft", 4.5, 20.0, 4.5));
        assertFalse(wire.motions().ticksBeforePlayer(51), "spawned after the respawn");
        wire.send(new Respawn("minecraft:the_nether", 0, 0, 32));
        assertNull(wire.motions().ticking(50), "another level starts without them");
    }

    @Test
    void chunksStartAndStopTheirEntities() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 3 * 16 + 8.5, 20.0, 3 * 16 + 8.5));
        assertFalse(wire.motions().ticking(50), "added in a chunk that does not tick");
        wire.send(chunk(3, 3));
        assertTrue(wire.motions().ticking(50));
        assertFalse(wire.motions().ticksBeforePlayer(50), "it entered the tick list behind the player");
        wire.send(new ForgetChunk(3, 3));
        assertFalse(wire.motions().ticking(50));
        wire.send(chunk(3, 3));
        assertTrue(wire.motions().ticking(50));
        // a view-radius change drops chunks from the storage without unloading them
        wire.send(new ChunkCacheRadius(0));
        assertTrue(wire.motions().ticking(50), "storage range 3 keeps chunk 3");
        wire.send(new ChunkCacheRadius(2), new ChunkCacheCenter(9, 0));
        // a chunk loaded into the slot of another one unloads it (range 5, ring 11: x 3 and 14 share a slot)
        wire.send(chunk(14, 3));
        assertFalse(wire.motions().ticking(50));
    }

    @Test
    void aViewRadiusChangeKeepsDroppedChunksTicking() {
        var wire = new Wire().send(new ChunkCacheRadius(4), chunk(7, 0), spawn(50, "minecraft:oak_boat", 7 * 16 + 8.5, 20.0, 8.5));
        assertTrue(wire.motions().ticking(50));
        wire.send(new ChunkCacheRadius(2));
        assertFalse(wire.world().isLoaded(7, 0), "outside storage range 5");
        assertTrue(wire.motions().ticking(50), "ClientChunkCache.updateViewRadius does not unload");
    }

    @Test
    void movingIntoAChunkThatDoesNotTickStopsTheEntity() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5));
        // farther than 64 blocks: the sync snaps it there
        wire.send(EntityPositionSync.linear(50, 6 * 16 + 8.5, 20.0, 8.5, 0.0F, 0.0F, false));
        assertEquals(new Vec3d(6 * 16 + 8.5, 20.0, 8.5), wire.motions().position(50));
        assertFalse(wire.motions().ticking(50));
    }

    @Test
    void theOwnBoatKeepsTheRidesLastTransform() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5));
        wire.send(new EntityPassengers(50, new int[]{ME}));
        wire.tick();
        var reported = new Vec3d(9.25, 20.0, 8.75);
        wire.world().rideTransform(50, reported, 12.0F, 0.0F);
        wire.send(new EntityPassengers(50, new int[0]));
        wire.tick();
        assertFalse(wire.motions().unknown(50));
        assertEquals(reported, wire.motions().position(50));
    }

    @Test
    void anUnreportedOrTeleportedOwnBoatBecomesUnknown() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5));
        wire.send(new EntityPassengers(50, new int[]{ME}));
        wire.tick();
        wire.world().rideTransform(50, null, 0.0F, 0.0F);
        wire.send(new EntityPassengers(50, new int[0]));
        wire.tick();
        assertTrue(wire.motions().unknown(50), "no move_vehicle on the last controlled tick");
        assertNull(wire.motions().position(50));

        var teleported = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5));
        teleported.send(new EntityPassengers(50, new int[]{ME}));
        teleported.tick();
        teleported.send(new EntityTeleport(50, 9.5, 20.0, 9.5, 0, 0, 0, 0.0F, 0.0F, 0, false));
        teleported.tick();
        teleported.world().rideTransform(50, new Vec3d(9.0, 20.0, 9.0), 0.0F, 0.0F);
        teleported.send(new EntityPassengers(50, new int[0]));
        teleported.tick();
        assertTrue(teleported.motions().unknown(50), "its interpolation followed the ride");
        // a sync while it does not tick snaps it and makes it known again
        teleported.send(new ForgetChunk(0, 0), EntityPositionSync.linear(50, 8.0, 20.0, 8.0, 0.0F, 0.0F, false));
        assertFalse(teleported.motions().unknown(50));
    }

    @Test
    void aTeleportWhileOwnedLeavesTheTransformUnknownEvenWhenItsInterpolationEnded() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5));
        wire.send(new EntityPassengers(50, new int[]{ME}));
        wire.tick();
        wire.send(new EntityTeleport(50, 9.5, 20.0, 9.5, 0, 0, 0, 0.0F, 0.0F, 0, false));
        for (int i = 0; i < 4; i++) {
            wire.tick();
            wire.world().rideTransform(50, new Vec3d(9.0 + i, 20.0, 9.0), 0.0F, 0.0F);
        }
        wire.send(new EntityPassengers(50, new int[0]));
        wire.tick();
        assertTrue(wire.motions().unknown(50));
    }

    @Test
    void anInterpolationRunningAtTheMountLeavesTheTransformUnknownAtAQuickDismount() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5));
        wire.send(ru.minecomplex.mac.fixture.outbound.EntityMove.linear(50, true, false, (short) 4096, (short) 0, (short) 0,
                (byte) 0, (byte) 0, false));
        wire.send(new EntityPassengers(50, new int[]{ME}));
        wire.tick();
        wire.world().rideTransform(50, new Vec3d(9.0, 20.0, 8.5), 0.0F, 0.0F);
        wire.send(new EntityPassengers(50, new int[0]));
        wire.tick();
        assertTrue(wire.motions().unknown(50), "its interpolation was still running");
    }

    @Test
    void aFormerPassengerIsUnknown() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5),
                spawn(51, "minecraft:happy_ghast", 8.5, 16.0, 8.5), new EntityPassengers(51, new int[]{50}));
        wire.tick();
        wire.send(new EntityPassengers(51, new int[0]));
        assertTrue(wire.motions().unknown(50), "placed by its vehicle while it rode");
        assertFalse(wire.motions().unknown(51));
    }

    @Test
    void passengersAndShulkersAndPushablesAreReportedNotGuessed() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5),
                spawn(51, "minecraft:happy_ghast", 8.5, 16.0, 8.5), new EntityPassengers(51, new int[]{50}),
                spawn(52, "minecraft:shulker", 2.5, 16.0, 2.5), spawn(53, "minecraft:pig", 24.5, 16.0, 24.5));
        var motions = wire.motions();
        var gaps = EnumSet.noneOf(Unmodeled.class);
        Aabb onTheBoat = new Aabb(8.2, 20.4, 8.2, 8.8, 22.3, 8.8);
        motions.collisionBoxes(onTheBoat, EntityMotions.Collider.player(ME, new Vec3d(8.5, 20.5, 8.5)), null, gaps);
        assertEquals(EnumSet.of(Unmodeled.ENTITY_COLLISION), gaps, "the boat rides the ghast: placed by it");

        gaps.clear();
        Aabb byTheShulker = new Aabb(3.2, 16.0, 2.2, 3.8, 17.8, 2.8);
        assertTrue(motions.collisionBoxes(byTheShulker, EntityMotions.Collider.player(ME, new Vec3d(3.5, 16.0, 2.5)), null, gaps).isEmpty());
        assertTrue(gaps.isEmpty(), "a closed shulker fills its block only");
        Aabb intoTheShulker = new Aabb(2.6, 16.2, 2.6, 3.4, 17.8, 3.4);
        assertEquals(List.of(new Aabb(2.0, 16.0, 2.0, 3.0, 17.0, 3.0)),
                motions.collisionBoxes(intoTheShulker, EntityMotions.Collider.player(ME, new Vec3d(3.0, 16.2, 3.0)), null, gaps));
        assertTrue(gaps.isEmpty());

        gaps.clear();
        Aabb byThePig = new Aabb(23.2, 16.0, 24.2, 23.8, 17.8, 24.8);
        assertTrue(motions.collisionBoxes(byThePig, EntityMotions.Collider.player(ME, new Vec3d(23.5, 16.0, 24.5)), null, gaps).isEmpty());
        assertTrue(gaps.isEmpty(), "a pig does not collide with a player");
        assertFalse(motions.mayPush(new Vec3d(23.5, 16.0, 24.5), byThePig), "its pushes are simulated in its tick");
        motions.collisionBoxes(new Aabb(24.2, 16.0, 24.2, 24.8, 17.8, 24.8), motions.vehicleCollider(60, "minecraft:oak_boat", Vec3d.ZERO), null, gaps);
        assertEquals(EnumSet.of(Unmodeled.VEHICLE_ENTITIES), gaps, "a boat collides with pushable entities");
        Aabb far = new Aabb(40.2, 16.0, 40.2, 40.8, 17.8, 40.8);
        assertFalse(motions.mayPush(new Vec3d(40.5, 16.0, 40.5), far));
    }

    /** A player standing at {@code position} with a player box, as a boat's tick pushes it. */
    static class Player implements EntityMotions.LocalPlayerTarget {
        final Vec3d position;
        Vec3d velocity = Vec3d.ZERO;

        Player(double x, double y, double z) {
            position = new Vec3d(x, y, z);
        }

        @Override public Vec3d position() { return position; }
        @Override public Aabb box() { return new Aabb(position.x() - 0.3, position.y(), position.z() - 0.3, position.x() + 0.3, position.y() + 1.8, position.z() + 0.3); }
        @Override public boolean pushable() { return true; }
        @Override public boolean spectator() { return false; }
        @Override public boolean noPhysics() { return false; }
        @Override public boolean vehicle() { return false; }
        @Override public boolean sleeping() { return false; }
        @Override public Vec3d velocity() { return velocity; }
        @Override public void velocity(Vec3d value) { velocity = value; }
    }

    private static Vec3d pushed(Wire wire, Player player) {
        var gaps = EnumSet.noneOf(Unmodeled.class);
        wire.world().tickEntities(true, (id, body, self, queryGaps) -> box -> true, player, gaps);
        wire.world().tickEntities(false, (id, body, self, queryGaps) -> box -> true, player, gaps);
        assertEquals(EnumSet.noneOf(Unmodeled.class), gaps);
        return player.velocity;
    }

    /**
     * {@code FireworkRocketEntity.tick}: a rocket attached to the local player pushes it after the player's own tick (it was
     * added later to the tick list); attached to nobody it does nothing; one whose tick found the player before a respawn
     * keeps that old player; one that had not ticked yet finds the new one, which a same-level respawn added after it.
     */
    @Test
    void rocketsPushTheLocalPlayerTheyAreAttachedTo() {
        int attach = EntityTypes.get().dataId("FireworkRocketEntity.DATA_ATTACHED_TO_TARGET");
        java.util.function.IntFunction<ru.minecomplex.mac.fixture.outbound.EntitySyncedData> attached = id ->
                new ru.minecomplex.mac.fixture.outbound.EntitySyncedData(id, 0, (byte) 0, false, "", (byte) 0, 0.0F,
                        ru.minecomplex.mac.fixture.outbound.EntitySyncedData.encodeMobItems(java.util.Map.of(attach, java.util.OptionalInt.of(ME))));
        var wire = new Wire().send(chunk(0, 0), spawn(100, "minecraft:firework_rocket", 8.5, 20.0, 8.5), attached.apply(100),
                spawn(101, "minecraft:firework_rocket", 8.5, 20.0, 8.5));
        var boosts = new ArrayList<String>();
        class Rockets extends Player {
            boolean before;
            Rockets() { super(8.5, 20.0, 8.5); }
            @Override public void rocketBoost(int id) { boosts.add(id + (before ? " before" : " after")); }
        }
        var player = new Rockets();
        Runnable tick = () -> {
            var gaps = EnumSet.noneOf(Unmodeled.class);
            player.before = true;
            wire.world().tickEntities(true, (id, body, self, queryGaps) -> box -> true, player, gaps);
            player.before = false;
            wire.world().tickEntities(false, (id, body, self, queryGaps) -> box -> true, player, gaps);
            assertEquals(EnumSet.noneOf(Unmodeled.class), gaps);
        };
        tick.run();
        assertEquals(List.of("100 after"), boosts, "the attached rocket after the player; the other flies on its own");
        assertTrue(wire.motions().rocketOnLocalPlayer(100));
        assertFalse(wire.motions().rocketOnLocalPlayer(101));
        // a rocket attached now that has not ticked yet, then a respawn in the same level
        wire.send(spawn(102, "minecraft:firework_rocket", 8.5, 20.0, 8.5), attached.apply(102),
                new ru.minecomplex.mac.fixture.outbound.Respawn("minecraft:overworld", 0, 0, 32, 0));
        boosts.clear();
        tick.run();
        assertEquals(List.of("102 before"), boosts, "100 keeps the old player; 102 finds the new one, which ticks after it");
        assertFalse(wire.motions().rocketOnLocalPlayer(100));
    }

    @Test
    void aSpinAttackTouchIsDecidedOnlyForALivingEntityFollowedExactlyWithNoPlayerThere() {
        var wire = new Wire().send(chunk(0, 0), spawn(60, "minecraft:cow", 8.5, 20.0, 8.5));
        // the cow's box: 0.9 wide, 1.4 high
        assertEquals(Tri.TRUE, wire.motions().livingIn(new Aabb(8.9, 20.0, 8.0, 9.5, 21.0, 9.0), ME));
        assertEquals(Tri.FALSE, wire.motions().livingIn(new Aabb(9.0, 20.0, 8.0, 9.5, 21.0, 9.0), ME), "touching faces only");
        assertEquals(Tri.FALSE, wire.motions().livingIn(new Aabb(12.0, 20.0, 12.0, 13.0, 21.0, 13.0), ME));
        // a boat is not living
        wire.send(spawn(61, "minecraft:oak_boat", 12.5, 20.0, 12.5));
        assertEquals(Tri.FALSE, wire.motions().livingIn(new Aabb(12.0, 20.0, 12.0, 13.0, 21.0, 13.0), ME));
        // a living entity followed only by its packet positions (the ender dragon ticks its own way)
        wire.send(spawn(63, "minecraft:ender_dragon", 30.5, 20.0, 30.5));
        assertEquals(Tri.EITHER, wire.motions().livingIn(new Aabb(30.0, 20.0, 30.0, 31.0, 21.0, 31.0), ME));
        // another player there: its client-side hurt succeeds, the attack may knock back and slow
        wire.send(spawn(62, "minecraft:player", 9.2, 20.0, 8.5));
        assertEquals(Tri.EITHER, wire.motions().livingIn(new Aabb(8.9, 20.0, 8.0, 9.5, 21.0, 9.0), ME));
    }

    @Test
    void aBoatPushesOnlyWhatIsNotAboveItsBottom() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5));
        // standing on it
        assertEquals(Vec3d.ZERO, pushed(wire, new Player(8.7, 20.5625, 8.5)));
        // beside it, feet at its bottom: pushed away along x
        Vec3d beside = pushed(wire, new Player(9.6, 20.0, 8.5));
        assertTrue(beside.x() > 0.0 && beside.z() == 0.0, beside.toString());
        // beside it inside its push box, feet above its bottom
        assertEquals(Vec3d.ZERO, pushed(wire, new Player(9.6, 20.1, 8.5)));
        // centred under it: closer than 0.01 apart
        assertEquals(Vec3d.ZERO, pushed(wire, new Player(8.505, 19.9, 8.5)));
        // the boat's own team never collides
        wire.send(new ru.minecomplex.mac.fixture.outbound.TeamUpdate("t", 0, ru.minecomplex.mac.fixture.outbound.TeamUpdate.NEVER,
                ru.minecomplex.mac.fixture.outbound.TeamUpdate.encode(List.of(new java.util.UUID(0L, 0L).toString()))));
        assertEquals(Vec3d.ZERO, pushed(wire, new Player(9.6, 20.0, 8.5)));
    }

    @Test
    void removedVehicleOfThePlayerIsTracked() {
        var entities = new CompensatedEntities(ME);
        entities.spawn(ru.minecomplex.mac.Wire.of(spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5)), chunk -> true);
        entities.spawn(ru.minecomplex.mac.Wire.of(spawn(60, "minecraft:happy_ghast", 8.5, 30.0, 8.5)), chunk -> true);
        // removing an entity the player does not ride
        entities.remove(ru.minecomplex.mac.Wire.of(new EntityRemove(new int[]{60})));
        assertEquals(ru.minecomplex.mac.util.Tri.FALSE, entities.formerVehicle(60));
        entities.apply(ru.minecomplex.mac.Wire.of(new EntityPassengers(50, new int[]{ME})));
        entities.remove(ru.minecomplex.mac.Wire.of(new EntityRemove(new int[]{50})));
        assertEquals(ru.minecomplex.mac.util.Tri.TRUE, entities.formerVehicle(50));
        assertNull(entities.passengers().get(ME).vehicle());
        // the id reused by a new entity
        entities.spawn(ru.minecomplex.mac.Wire.of(spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5)), chunk -> true);
        assertEquals(ru.minecomplex.mac.util.Tri.FALSE, entities.formerVehicle(50));
        // a boat on a ghast, removed outer first: the last removal carrying the player wins
        entities.spawn(ru.minecomplex.mac.Wire.of(spawn(60, "minecraft:happy_ghast", 8.5, 30.0, 8.5)), chunk -> true);
        entities.apply(ru.minecomplex.mac.Wire.of(new EntityPassengers(60, new int[]{50})));
        entities.apply(ru.minecomplex.mac.Wire.of(new EntityPassengers(50, new int[]{ME})));
        entities.remove(ru.minecomplex.mac.Wire.of(new EntityRemove(new int[]{60, 50})));
        assertEquals(ru.minecomplex.mac.util.Tri.TRUE, entities.formerVehicle(50));
        assertEquals(ru.minecomplex.mac.util.Tri.FALSE, entities.formerVehicle(60));
        // a mount of the player clears it
        entities.spawn(ru.minecomplex.mac.Wire.of(spawn(70, "minecraft:oak_boat", 8.5, 20.0, 8.5)), chunk -> true);
        entities.apply(ru.minecomplex.mac.Wire.of(new EntityPassengers(70, new int[]{ME})));
        assertEquals(ru.minecomplex.mac.util.Tri.FALSE, entities.formerVehicle(50));
    }

    @Test
    void forksDoNotShareTransforms() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:oak_boat", 8.5, 20.0, 8.5));
        wire.send(EntityPositionSync.linear(50, 10.0, 20.0, 8.5, 0.0F, 0.0F, false));
        CompensatedWorld copy = wire.world().detachedCopy();
        wire.tick();
        assertNotEquals(copy.entities().motions().position(50), wire.motions().position(50));
        assertEquals(new Vec3d(8.5, 20.0, 8.5), copy.entities().motions().position(50));
    }

    @Test
    void aLivingRideHandsItsClientTransformToTheRemoteEntity() {
        for (String type : List.of("horse", "camel", "pig", "strider", "nautilus", "zombie_nautilus", "camel_husk")) {
            var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:"+type, 8.5, 20, 8.5),
                    new ru.minecomplex.mac.fixture.outbound.EntitySyncedData(50,0,(byte)0,false,"",(byte)0,0F,
                            ru.minecomplex.mac.fixture.outbound.EntitySyncedData.encodeMobItems(java.util.Map.of(18,(byte)2))),
                    ru.minecomplex.mac.fixture.outbound.EntityEquipment.of(50,ru.minecomplex.mac.fixture.WireItem.of("minecraft:saddle",1),null),
                    new EntityPassengers(50, new int[]{ME}));
            CompensatedWorld fork = wire.world().detachedCopy();
            Vec3d driven = new Vec3d(12.5, 20, 9.5);
            wire.world().rideTransform(50, driven, 37F, 5F, Vec3d.ZERO);
            assertEquals(driven, wire.motions().position(50), type);
            assertEquals(new Vec3d(8.5, 20, 8.5), fork.entities().motions().position(50), type+" fork");
            wire.send(new EntityPassengers(50, new int[0]));
            wire.tick();
            assertEquals(driven, wire.motions().position(50), type+" dismounted");
            assertFalse(wire.motions().unknown(50), type);
        }
    }

    @Test
    void anEstimatedLivingRideRetainsItsUncertaintyAndRequiresAController() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:pig", 8.5, 20, 8.5));
        Vec3d driven = new Vec3d(12.5, 20, 9.5);
        wire.world().rideTransform(50, driven, 37F, 5F, Vec3d.ZERO);
        assertEquals(new Vec3d(8.5,20,8.5),wire.motions().position(50),"no local controller");
        wire.send(new EntityPassengers(50,new int[]{ME}));
        wire.world().rideTransform(50,driven,37F,5F,Vec3d.ZERO,null,false);
        assertTrue(wire.motions().phase(50)[0]);
        assertTrue(wire.motions().phase(50)[2]);
        wire.send(new EntityPassengers(50,new int[0]));
        wire.tick();
        assertTrue(wire.motions().phase(50)[0],"estimate cannot become exact just by dismounting");
    }

    @Test
    void aRemoteHorseSeatRetainsItsHistoryAcrossWorldForks() {
        var wire=new Wire().send(chunk(0,0),spawn(50,"minecraft:horse",8.5,20,8.5),
                new ru.minecomplex.mac.fixture.outbound.EntitySyncedData(50,0,(byte)0,false,"",(byte)0,0F,
                        ru.minecomplex.mac.fixture.outbound.EntitySyncedData.encodeMobItems(java.util.Map.of(18,(byte)34))),
                new EntityPassengers(50,new int[]{ME}));
        wire.tick();wire.tick();
        CompensatedWorld fork=wire.world().detachedCopy();
        float previous=fork.entities().motions().horseSeatAnimation(50);
        assertEquals(previous,wire.motions().horseSeatAnimation(50));
        wire.tick();
        assertNotEquals(previous,wire.motions().horseSeatAnimation(50));
        assertEquals(previous,fork.entities().motions().horseSeatAnimation(50));
        assertTrue(wire.motions().passengerVehicle(50)!=null);
        wire.motions().phaseUncertain(50);
        assertNull(wire.motions().passengerVehicle(50));
    }

    @Test
    void livingControllerAndGroundStateBelongToTheEntityGenerationAndFork() {
        var wire = new Wire().send(chunk(0,0),spawn(50,"minecraft:pig",8.5,20,8.5),new EntityPassengers(50,new int[]{ME}));
        wire.motions().localController(50,Tri.TRUE);
        wire.tick();
        var position = new Vec3d(9.25,20,8.75);
        wire.world().rideTransform(50,position,12F,0F,Vec3d.ZERO);
        wire.motions().rideOnGround(50,true);
        var copy = wire.world().detachedCopy();
        assertTrue(copy.entities().motions().passengerVehicle(50).body.onGround);
        var move = ru.minecomplex.mac.fixture.outbound.EntityMove.linear(50,true,false,(short)4096,(short)0,(short)0,(byte)0,(byte)0,false);
        copy.entities().motions().move(ru.minecomplex.mac.Wire.of(move),chunk -> true);
        assertFalse(copy.entities().motions().unknown(50),"a fork keeps the resolved controller");
        assertFalse(copy.entities().motions().passengerVehicle(50).interpolating());
        copy.entities().motions().localController(50,Tri.FALSE);
        copy.entities().motions().move(ru.minecomplex.mac.Wire.of(move),chunk -> true);
        wire.send(move);
        assertEquals(position,wire.motions().position(50));
        assertFalse(wire.motions().passengerVehicle(50).interpolating());
        assertTrue(copy.entities().motions().passengerVehicle(50).interpolating());
        assertFalse(copy.entities().motions().passengerVehicle(50).body.onGround);
        assertTrue(wire.motions().passengerVehicle(50).body.onGround);
        wire.motions().uncertainController(50);
        assertNull(wire.motions().passengerVehicle(50));
        wire.world().rideTransform(50,position,12F,0F,Vec3d.ZERO);
        assertFalse(wire.motions().unknown(50),"a resolved driven baseline ends controller uncertainty");
        wire.send(new EntityRemove(new int[]{50}),spawn(50,"minecraft:pig",8.5,20,8.5),new EntityPassengers(50,new int[]{ME}),move);
        assertTrue(wire.motions().unknown(50),"the new generation has no resolved controller");
    }

    @Test
    void anEstimatedRideVelocityStaysUncertainAcrossTicksAndForks() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:pig", 8.5, 20, 8.5),
                new EntityPassengers(50, new int[]{ME}));
        wire.motions().localController(50, Tri.TRUE);
        wire.tick();
        var estimate = new Vec3d(0.125, -0.0784, -0.25);
        wire.world().rideTransform(50, new Vec3d(8.5, 20, 8.5), 0F, 0F, estimate, false,
                wire.motions().rideStart(50).interpolation, true);
        wire.tick();
        assertNull(wire.motions().rideStartVelocity(50), "an estimate is not a velocity certificate");
        assertEquals(estimate, wire.motions().rideStartVelocityEstimate(50));
        var fork = wire.world().detachedCopy();
        assertNull(fork.entities().motions().rideStartVelocity(50));
        assertEquals(estimate, fork.entities().motions().rideStartVelocityEstimate(50));
    }

    @Test
    void anUnknownRideRetainsItsPreTickTransformAndGroundEstimateAcrossForks() {
        var wire = new Wire().send(chunk(0, 0), spawn(50, "minecraft:pig", 8.5, 20, 8.5),
                new EntityPassengers(50, new int[]{ME}));
        wire.motions().localController(50, Tri.TRUE);
        wire.tick();
        var at = new Vec3d(9.25, 20, 8.75);
        var velocity = new Vec3d(0.125, -0.0784, -0.25);
        wire.world().rideTransform(50, at, 12F, 5F, velocity, false,
                wire.motions().rideStart(50).interpolation, false);
        wire.motions().rideOnGround(50, false);
        wire.motions().uncertainController(50);
        wire.tick();
        assertNull(wire.motions().rideStart(50));
        assertNull(wire.motions().rideStartVelocity(50));
        assertTrue(wire.motions().unknown(50));
        var estimate = wire.motions().rideStartEstimate(50);
        assertNotNull(estimate);
        assertEquals(at, estimate.body.position);
        assertFalse(estimate.body.onGround);
        assertEquals(velocity, wire.motions().rideStartVelocityEstimate(50));
        var fork = wire.world().detachedCopy();
        assertNull(fork.entities().motions().rideStart(50));
        var forkEstimate = fork.entities().motions().rideStartEstimate(50);
        assertNotNull(forkEstimate, "the fork must retain the pre-tick snapshot");
        assertFalse(forkEstimate.body.onGround);
        forkEstimate.body.position = Vec3d.ZERO;
        assertEquals(at, estimate.body.position, "the fork must own its pre-tick snapshot");
    }
}
