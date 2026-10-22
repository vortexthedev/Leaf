package ru.minecomplex.mac.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.fixture.outbound.PlayerInfoGameMode;
import ru.minecomplex.mac.fixture.outbound.TeamUpdate;

/**
 * The pushes of living entities and the shulker's opening as {@link EntityMotions} applies them to the local player;
 * the vanilla behaviour itself is pinned by the MAMP oracle ({@code MobPushProbeOracleTest}), these check the wiring of
 * the cases the model reports instead of simulating.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LivingPushTest {
    static final int ME = EntityMotionsTest.ME;

    static EntitySpawn spawn(int id, String type, double x, double y, double z, UUID uuid) {
        return new EntitySpawn(id, type, x, y, z, 0.0F, 0.0F, 0.0F, 0, false, 0.0, 0.0, 0.0,
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    /** The player's velocity after one client tick of the entities, and what they could not resolve. */
    static Vec3d pushed(EntityMotionsTest.Wire wire, EntityMotionsTest.Player player, Set<Unmodeled> gaps) {
        wire.world().tickEntities(true, (id, body, self, queryGaps) -> box -> true, player, gaps);
        wire.world().tickEntities(false, (id, body, self, queryGaps) -> box -> true, player, gaps);
        return player.velocity;
    }

    @Test
    void livingEntitiesPushExceptThoseThatNeverPushPlayers() {
        for (String type : List.of("minecraft:zombie", "minecraft:cow", "minecraft:bat", "minecraft:parrot", "minecraft:vex",
                "minecraft:armor_stand")) {
            var wire = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0), spawn(50, type, 8.7, 16.0, 8.5, new UUID(1, 50)));
            var gaps = EnumSet.noneOf(Unmodeled.class);
            Vec3d velocity = pushed(wire, new EntityMotionsTest.Player(8.5, 16.0, 8.5), gaps);
            assertEquals(EnumSet.noneOf(Unmodeled.class), gaps, type);
            boolean pushes = EntityMotions.pushesPlayers(type);
            assertEquals(pushes, velocity.x() < 0.0, type + " " + velocity);
            assertEquals(0.0, velocity.z(), type);
            assertFalse(wire.motions().mayPush(new Vec3d(8.5, 16.0, 8.5), new EntityMotionsTest.Player(8.5, 16.0, 8.5).box()), type);
        }
    }

    /**
     * {@code Entity.push} also pushes the mob unless its {@code isPushable()} says no: {@code AbstractHorse} only checks
     * {@code !isVehicle()}, a {@code Creaking} must be able to move ({@code CAN_MOVE}, not followed).
     */
    @Test
    void thePushersOwnPushabilityFollowsItsClass() {
        for (boolean onLadder : new boolean[]{false, true}) {
            for (String type : List.of("minecraft:cow", "minecraft:horse", "minecraft:creaking")) {
                var wire = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0), spawn(50, type, 8.7, 16.0, 8.5, new UUID(1, 50)));
                var player = new EntityMotionsTest.Player(8.5, 16.0, 8.5);
                var gaps = EnumSet.noneOf(Unmodeled.class);
                // a dry world (a mob's own velocity stays known), with or without the mob on a climbable block
                for (boolean before : new boolean[]{true, false}) {
                    wire.world().tickEntities(before, (id, body, self, queryGaps) -> box -> true, player, gaps, box -> false,
                            position -> onLadder ? ru.minecomplex.mac.util.Tri.TRUE : ru.minecomplex.mac.util.Tri.FALSE);
                }
                String context = type + (onLadder ? " on a ladder" : "");
                assertTrue(player.velocity.x() < 0.0, context);
                Vec3d mob = wire.motions().knownVelocity(50);
                if (type.equals("minecraft:creaking")) assertNull(mob, context);
                // LivingEntity.isPushable: not on a climbable block; AbstractHorse: only !isVehicle()
                else if (onLadder && type.equals("minecraft:cow")) assertEquals(Vec3d.ZERO, mob, context);
                else assertTrue(mob != null && mob.x() > 0.0, context + " " + mob);
            }
        }
    }

    /** A dying mob (health 0) still runs {@code pushEntities}, but is not pushable itself ({@code isAlive}). */
    @Test
    void aDyingMobPushesButIsNotPushed() {
        var wire = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0), spawn(50, "minecraft:cow", 8.7, 16.0, 8.5, new UUID(1, 50)),
                new EntitySyncedData(50, EntitySyncedData.HEALTH, (byte) 0, false, "", (byte) 0, 0.0F, new byte[0]));
        var player = new EntityMotionsTest.Player(8.5, 16.0, 8.5);
        for (boolean before : new boolean[]{true, false}) {
            wire.world().tickEntities(before, (id, body, self, queryGaps) -> box -> true, player, EnumSet.noneOf(Unmodeled.class),
                    box -> false, position -> ru.minecomplex.mac.util.Tri.FALSE);
        }
        assertTrue(player.velocity.x() < 0.0);
        assertEquals(Vec3d.ZERO, wire.motions().knownVelocity(50));
    }

    @Test
    void teamsDecideAndUnknownProfilesAreReported() {
        var wire = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0), spawn(50, "minecraft:zombie", 8.7, 16.0, 8.5, new UUID(1, 50)));
        wire.send(TeamUpdate.of("t", TeamUpdate.ADD, TeamUpdate.NEVER, List.of(new UUID(1, 50).toString())));
        var gaps = EnumSet.noneOf(Unmodeled.class);
        assertEquals(Vec3d.ZERO, pushed(wire, new EntityMotionsTest.Player(8.5, 16.0, 8.5), gaps));
        assertTrue(gaps.isEmpty());
        // a player entity of a recording without profiles: whether it is a spectator is not known
        var players = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0),
                spawn(60, "minecraft:player", 8.7, 16.0, 8.5, new UUID(2, 60)));
        pushed(players, new EntityMotionsTest.Player(8.5, 16.0, 8.5), gaps);
        assertEquals(EnumSet.of(Unmodeled.ENTITY_PUSH), gaps);
    }

    @Test
    void spectatorPlayersDoNotPush() {
        var uuid = new UUID(2, 60);
        for (int mode : new int[]{0, 3}) {
            var wire = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0),
                    new PlayerInfoGameMode(false, false, 0, PlayerInfoGameMode.encode(List.of(new PlayerInfoGameMode.Profile(uuid, "other"))),
                            PlayerInfoGameMode.encodeGameModes(List.of(new PlayerInfoGameMode.EntryGameMode(uuid, mode)))),
                    spawn(60, "minecraft:player", 8.7, 16.0, 8.5, uuid));
            var gaps = EnumSet.noneOf(Unmodeled.class);
            Vec3d velocity = pushed(wire, new EntityMotionsTest.Player(8.5, 16.0, 8.5), gaps);
            assertTrue(gaps.isEmpty());
            assertEquals(mode != 3, velocity.x() < 0.0, "game mode " + mode + ": " + velocity);
        }
    }

    /**
     * A boat places its passenger in its tick (RiderSeatOracleTest pins the seat): the passenger's own tick before that
     * pushes from where it sat, a dismount leaves it where it sat (the client does not move it), a passenger of the
     * local player's own boat or of a living mount is not placed.
     */
    @Test
    void aBoatsPassengerIsPlacedAndPushesFromItsSeat() {
        var wire = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0),
                spawn(50, "minecraft:oak_boat", 8.5, 16.0, 8.5, new UUID(1, 50)),
                spawn(51, "minecraft:zombie", 3.5, 16.0, 3.5, new UUID(1, 51)), new EntityPassengers(50, new int[]{51}));
        var gaps = EnumSet.noneOf(Unmodeled.class);
        // the first tick: the zombie's own tick pushes from where it stood (far away), then the boat seats it
        assertEquals(Vec3d.ZERO, pushed(wire, new EntityMotionsTest.Player(20.5, 16.0, 20.5), gaps));
        Vec3d seat = wire.motions().position(51);
        // a boat's third of its height, less the zombie's vehicle point
        assertEquals(new Vec3d(8.5, 16.0 + 0.5625F / 3.0F - 0.699999988079071, 8.5), seat);
        assertFalse(wire.motions().mayPush(new Vec3d(8.8, 16.1, 8.5), new EntityMotionsTest.Player(8.8, 16.1, 8.5).box()));
        // now it pushes from the seat (feet above the boat's bottom: AbstractBoat.push leaves this player alone)
        var beside = new EntityMotionsTest.Player(8.8, 16.1, 8.5);
        Vec3d velocity = pushed(wire, beside, gaps);
        assertTrue(velocity.x() > 0.0, velocity.toString());
        assertTrue(gaps.isEmpty(), gaps.toString());
        // a dismount keeps it where it sat
        wire.send(new EntityPassengers(50, new int[0]));
        assertEquals(seat, wire.motions().position(51));
        pushed(wire, new EntityMotionsTest.Player(20.5, 16.0, 20.5), gaps);
        assertEquals(seat, wire.motions().position(51));
    }

    @Test
    void passengersOfTheOwnBoatOrOfALivingMountAreNotPlaced() {
        var own = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0),
                spawn(50, "minecraft:oak_boat", 8.5, 16.0, 8.5, new UUID(1, 50)),
                spawn(51, "minecraft:zombie", 3.5, 16.0, 3.5, new UUID(1, 51)), new EntityPassengers(50, new int[]{ME, 51}));
        pushed(own, new EntityMotionsTest.Player(20.5, 16.0, 20.5), EnumSet.noneOf(Unmodeled.class));
        assertNull(own.motions().position(51), "the own boat's transform is the ride's");
        var horse = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0),
                spawn(50, "minecraft:horse", 8.5, 16.0, 8.5, new UUID(1, 50)),
                spawn(51, "minecraft:zombie", 3.5, 16.0, 3.5, new UUID(1, 51)), new EntityPassengers(50, new int[]{51}));
        pushed(horse, new EntityMotionsTest.Player(20.5, 16.0, 20.5), EnumSet.noneOf(Unmodeled.class));
        assertNull(horse.motions().position(51), "a living mount's seats are not modelled");
    }

    @Test
    void aPassengerOfAVehicleWhoseSeatsAreNotModelledIsReported() {
        var wire = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0),
                spawn(50, "minecraft:minecart", 8.7, 16.0, 8.5, new UUID(1, 50)),
                spawn(51, "minecraft:zombie", 8.7, 16.0, 8.5, new UUID(1, 51)), new EntityPassengers(50, new int[]{51}));
        var gaps = EnumSet.noneOf(Unmodeled.class);
        pushed(wire, new EntityMotionsTest.Player(20.5, 16.0, 20.5), gaps);
        assertNull(wire.motions().position(51), "a minecart's positionRider is not modelled");
        assertTrue(wire.motions().mayPush(new Vec3d(8.5, 16.0, 8.5), new EntityMotionsTest.Player(8.5, 16.0, 8.5).box()));
    }

    @Test
    void anOpeningShulkerMovesThePlayerAndMarksOtherEntitiesUnknown() {
        var shulker = new UUID(1, 70);
        var wire = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0), spawn(70, "minecraft:shulker", 8.5, 16.0, 8.5, shulker),
                spawn(71, "minecraft:cow", 8.5, 17.0, 8.5, new UUID(1, 71)));
        int peek = EntityTypes.get().living("minecraft:shulker").item("DATA_PEEK_ID");
        wire.send(new EntitySyncedData(70, 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(Map.of(peek, (byte) 100))));
        var moved = new java.util.ArrayList<Vec3d>();
        var player = new EntityMotionsTest.Player(8.5, 17.0, 8.5) {
            @Override
            public void moveByShulker(Vec3d delta, Set<Unmodeled> gaps) {
                moved.add(delta);
            }
        };
        var gaps = EnumSet.noneOf(Unmodeled.class);
        pushed(wire, player, gaps);
        assertTrue(gaps.isEmpty(), gaps.toString());
        assertEquals(1, moved.size());
        assertTrue(moved.getFirst().y() > 0.0 && moved.getFirst().x() == 0.0 && moved.getFirst().z() == 0.0, moved.toString());
        // the cow standing on it moves by its own Entity.move: not modelled
        assertNull(wire.motions().position(71));
        assertNotNull(wire.motions().boxOf(70));
    }

    @Test
    void forksKeepTheirOwnShapes() {
        var wire = new EntityMotionsTest.Wire().send(EntityMotionsTest.chunk(0, 0), spawn(50, "minecraft:slime", 8.5, 16.0, 8.5, new UUID(1, 50)));
        int size = EntityTypes.get().living("minecraft:slime").item("ID_SIZE");
        var copy = wire.world().detachedCopy();
        wire.send(new EntitySyncedData(50, 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(Map.of(size, 4))));
        assertEquals(wire.motions().boxOf(50).maxY() - 16.0, 4.0 * (copy.entities().motions().boxOf(50).maxY() - 16.0), 1.0E-6);
    }
}
