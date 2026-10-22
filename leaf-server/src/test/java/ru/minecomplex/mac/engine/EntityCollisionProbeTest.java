package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.Shapes;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.entity.EntityMotion;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerLoaded;
import ru.minecomplex.mac.fixture.outbound.ChunkBlockEntities;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.EntityMove;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.fixture.outbound.Respawn;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;

/**
 * Wiring of entity collisions in {@link MovementCheckRunner}: a "client" made of the same engine stands on a bamboo raft and a
 * happy ghast, whose boxes it computes on its own, and ticks a raft moved by packets where the client's tick list puts
 * it. The probe must follow with zero offset and without gaps. Not a vanilla test: the entity boxes and queries are
 * verified by MAEC, the raft's motion by MAEM.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class EntityCollisionProbeTest {
    static final int ME = 7, RAFT = 50, GHAST = 51;
    // bamboo raft 1.375 x 0.5625, adult happy ghast 4 x 4
    static final double RAFT_X = 8.5, RAFT_Y = 20.0, RAFT_Z = 8.5;
    static final double GHAST_X = 11.5, GHAST_Y = 16.5625, GHAST_Z = 8.5;

    /** The sequence of world and player changes and the windows that make them certain. */
    static final class Wire {
        final BlockRegistry registry = BlockRegistry.get();
        final WorldModel world = new WorldModel(registry);
        final PlayerModel player = new PlayerModel();
        long seq = -1;

        long sent(OutboundEvent event, boolean world, boolean player) {
            seq++;
            if (world) this.world.sent(seq, ru.minecomplex.mac.Wire.of(event));
            if (player) this.player.sent(seq, ru.minecomplex.mac.Wire.of(event));
            return seq;
        }

        SyncWindow window() {
            return new SyncWindow(seq, seq + 1);
        }
    }

    static Wire join() {
        var wire = new Wire();
        int stone = wire.registry.block("minecraft:stone").firstStateId();
        var join = new JoinGame(ME, "minecraft:overworld", 0, 32, 2);
        wire.sent(join, true, true);
        wire.sent(new ChunkData(0, 0, new int[]{0, 0}, new int[]{1, 1}, new int[]{stone, 0}, new long[0],
                ChunkBlockEntities.encode(List.of())), true, false);
        return wire;
    }

    static Aabb raftBox(double x, double y, double z) {
        return new Aabb(x - 0.6875F, y, z - 0.6875F, x + 0.6875F, y + 0.5625F, z + 0.6875F);
    }

    static final Aabb GHAST_BOX = new Aabb(GHAST_X - 2.0F, GHAST_Y, GHAST_Z - 2.0F, GHAST_X + 2.0F, GHAST_Y + 4.0F, GHAST_Z + 2.0F);

    /** The client's own world view: the stone floor and the entity boxes it knows. */
    static MovementEnvironment clientEnvironment(CompensatedWorld level, PlayerState p, java.util.function.Supplier<List<Aabb>> raft, boolean ghast) {
        return new MovementEnvironment() {
            @Override public boolean fluidsResolved() { return true; }
            @Override public BlockSource blocks() { return level; }
            @Override public BlockRegistry registry() { return BlockRegistry.get(); }
            @Override public BlockTags tags() { return BlockTags.NONE; }
            @Override public boolean hasChunkAt(int x, int z) { return level.isLoaded(x >> 4, z >> 4); }
            @Override public int minY() { return level.minY(); }
            @Override public List<VoxelShape> entityCollisions(Aabb box) {
                Aabb search = box.inflate(1.0E-7);
                var shapes = new ArrayList<VoxelShape>();
                for (Aabb entity : raft.get()) if (intersects(entity, search)) shapes.add(Shapes.create(entity));
                // a happy ghast collides with a player at or above its top
                if (ghast && intersects(GHAST_BOX, search) && p.y() >= GHAST_BOX.maxY()) shapes.add(Shapes.create(GHAST_BOX));
                return shapes;
            }
            @Override public VoxelShape borderShape(Aabb box) { return null; }
        };
    }

    static boolean intersects(Aabb a, Aabb b) {
        return a.intersects(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    static ClientTickFrame frame(int tick, SyncWindow window, List<InboundEvent> packets, int mask, Movement movement) {
        return new ClientTickFrame(tick, tick, tick, window, ru.minecomplex.mac.Wire.ofAll(packets), mask, true,
                movement == null ? null : ru.minecomplex.mac.Wire.of(movement), Set.of());
    }

    @Test
    void standsOnARaftAndWalksOntoAHappyGhast() {
        var wire = join();
        wire.sent(new EntitySpawn(RAFT, "minecraft:bamboo_raft", RAFT_X, RAFT_Y, RAFT_Z, 0, 0, 0, 0, false, 0, 0, 0), true, false);
        wire.sent(new EntitySpawn(GHAST, "minecraft:happy_ghast", GHAST_X, GHAST_Y, GHAST_Z, 0, 0, 0, 0, false, 0, 0, 0), true, false);
        wire.sent(new PlayerPosition(1, RAFT_X, RAFT_Y + 0.5625, RAFT_Z, 0, 0, 0, -90.0F, 10.0F, 0), false, true);
        SyncWindow window = wire.window();
        var probe = new MovementCheckRunner(wire.world, wire.player, new SyncWindow(-1, 0));
        var client = wire.player.start(new PlayerState(), new LocalPlayerState(), new ClientPlayerData(), -1);
        client.apply(window, window.certainThrough());
        CompensatedWorld level = wire.world.viewAt(window, window.certainThrough());
        var p = client.player();
        var raft = raftBox(RAFT_X, RAFT_Y, RAFT_Z);
        var env = clientEnvironment(level, p, () -> List.of(raft), true);
        // stand, walk east off the raft onto the ghast, jump, sneak to its far edge, walk back
        int[] keys = new int[70];
        for (int t = 0; t < keys.length; t++) {
            keys[t] = t < 5 ? 0 : t < 18 ? 1 : t < 20 ? 1 | 16 : t < 30 ? 0 : t < 50 ? 1 | 32 : t < 55 ? 0 : 2;
        }
        boolean onGhast = false;
        for (int tick = 0; tick < keys.length; tick++) {
            p.yRot = -90.0F;
            p.xRot = 10.0F;
            var ticker = new MovementTickerPlayer(env, p, client.local(), FixturePlayers.loaded());
            ticker.tick(PlayerInput.fromMask(keys[tick]));
            Movement movement = null;
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0) packets.add(PlayerLoaded.INSTANCE);
            for (InboundEvent packet : ru.minecomplex.mac.Wire.in(ticker.packets())) {
                packets.add(packet);
                if (packet instanceof Movement move) movement = move;
            }
            var report = probe.frame(frame(tick, window, packets, keys[tick], movement));
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), "tick " + tick + " " + report.note());
            assertEquals(0.0, report.offset(), "tick " + tick + " " + report);
            assertTrue(report.gaps().isEmpty(), "tick " + tick + " " + report.gaps());
            if (p.x() > GHAST_BOX.minX() + 0.3 && p.y() == GHAST_BOX.maxY()) onGhast = true;
        }
        assertTrue(onGhast, "the walk must reach the ghast's top: " + p.position);
        assertTrue(p.y() >= GHAST_BOX.maxY(), "stays on top: " + p.position);
    }

    /**
     * A raft lowered under the player by move packets: it ticks after the player (spawned after login), or before it once
     * a same-level respawn re-added the player behind it. A client ticking it on the other side is told apart.
     */
    @Test
    void aRaftMovedByPacketsTicksAfterThePlayer() {
        assertEquals(0, ridesSinkingRaft(false, false));
        assertTrue(ridesSinkingRaft(false, true) > 0, "the tick order must show");
    }

    @Test
    void aRaftPresentAtARespawnTicksBeforeThePlayer() {
        assertEquals(0, ridesSinkingRaft(true, false));
        assertTrue(ridesSinkingRaft(true, true) > 0, "the tick order must show");
    }

    /** The number of ticks the probe did not follow. */
    private static int ridesSinkingRaft(boolean respawn, boolean wrongOrder) {
        var wire = join();
        wire.sent(new EntitySpawn(RAFT, "minecraft:bamboo_raft", RAFT_X, RAFT_Y, RAFT_Z, 0, 0, 0, 0, false, 0, 0, 0), true, false);
        if (respawn) wire.sent(new Respawn("minecraft:overworld", 0, 0, 32), true, true);
        wire.sent(new PlayerPosition(1, RAFT_X, RAFT_Y + 0.5625, RAFT_Z, 0, 0, 0, -90.0F, 10.0F, 0), false, true);
        SyncWindow window = wire.window();
        var probe = new MovementCheckRunner(wire.world, wire.player, new SyncWindow(-1, 0));
        var client = wire.player.start(new PlayerState(), new LocalPlayerState(), new ClientPlayerData(), -1);
        client.apply(window, window.certainThrough());
        CompensatedWorld level = wire.world.viewAt(window, window.certainThrough());
        var p = client.player();
        var motion = new EntityMotion(false, 3, 1.375F, 0.5625F);
        motion.spawn(RAFT_X, RAFT_Y, RAFT_Z, 0.0F, 0.0F);
        var env = clientEnvironment(level, p, () -> List.of(motion.box()), false);
        boolean before = respawn != wrongOrder;
        int missed = 0;
        for (int tick = 0; tick < 40; tick++) {
            List<InboundEvent> packets = new ArrayList<>();
            if (tick == 0) packets.add(PlayerLoaded.INSTANCE);
            if (tick >= 3 && tick < 20) {
                // the server lowers the raft by 205/4096 a tick; the client applies it before its tick
                var move = EntityMove.linear(RAFT, true, false, (short) 0, (short) -205, (short) 0, (byte) 0, (byte) 0, false);
                wire.sent(move, true, false);
                window = wire.window();
                motion.move(ru.minecomplex.mac.Wire.of(move), false);
            }
            if (before) motion.tick(box -> true);
            p.yRot = -90.0F;
            p.xRot = 10.0F;
            var ticker = new MovementTickerPlayer(env, p, client.local(), FixturePlayers.loaded());
            ticker.tick(PlayerInput.EMPTY);
            if (!before) motion.tick(box -> true);
            Movement movement = null;
            for (InboundEvent packet : ru.minecomplex.mac.Wire.in(ticker.packets())) {
                packets.add(packet);
                if (packet instanceof Movement move) movement = move;
            }
            var report = probe.frame(frame(tick, window, packets, 0, movement));
            assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), "tick " + tick + " " + report.note());
            assertTrue(report.gaps().isEmpty(), "tick " + tick + " " + report.gaps());
            if (report.offset() != 0.0) missed++;
        }
        if (!wrongOrder) {
            assertEquals(motion.box().maxY(), p.y(), "rode the raft down: " + p.position);
            assertTrue(p.y() < RAFT_Y, "the raft went down: " + p.position);
        }
        return missed;
    }
}
