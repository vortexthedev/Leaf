package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.fixture.inbound.Pong;
import ru.minecomplex.mac.fixture.inbound.KeepAlive;
import ru.minecomplex.mac.fixture.inbound.ChunkBatchReceived;
import ru.minecomplex.mac.frame.*;
import ru.minecomplex.mac.player.MacPlayer;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.replica.WorldModel;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class DynamicBlockTimelineTest {
    private static ChunkData chunk(int state, List<BlockEntityData> entities) {
        var wire = entities.stream().map(e -> new ru.minecomplex.mac.fixture.outbound.BlockEntityData(e.x(), e.y(), e.z(), e.type(), e.nbt())).toList();
        return new ChunkData(0, 0, new int[]{0}, new int[]{1}, new int[]{state}, new long[0], ChunkBlockEntities.encode(wire));
    }

    @Test
    void prefixesKeepChunkInputsAndBlockChangesInWireOrder() {
        var registry = BlockRegistry.get(); var model = new WorldModel(registry);
        int shulker = registry.block("minecraft:shulker_box").firstStateId();
        var nbt = new BlockEntityData(1, 2, 3, "minecraft:shulker_box", new byte[]{10, 0});
        // Wrong packet block id is retained: vanilla dispatches to the current block anyway.
        var event = new BlockEvent(1, 2, 3, 1, 1, registry.block("minecraft:stone").registryId());
        model.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 16, 2)));
        model.sent(1, Wire.of(chunk(shulker, List.of(nbt))));
        model.sent(2, Wire.of(event));
        model.sent(3, Wire.of(new BlockUpdate(1, 2, 3, 0)));
        var window = new SyncWindow(1, 5);
        assertArrayEquals(new long[]{1, 2, 3}, model.prefixEnds(window));
        var acknowledged = model.acknowledged(window);
        assertEquals(List.of(nbt), acknowledged.column(0, 0).dynamicInputs());
        var view = model.viewAt(window, 3);
        assertEquals(List.of(nbt, event, new BlockChange(1, 2, 3, 0)), view.column(0, 0).dynamicInputs());
        assertEquals(shulker, acknowledged.getBlock(1, 2, 3));
        assertEquals(0, view.getBlock(1, 2, 3));
        assertFalse(view.dynamicApplicationUnresolved());
        assertTrue(view.dynamicGaps().contains(ru.minecomplex.mac.engine.Unmodeled.SHULKER_TICK_TIMING));
        var expected = view.column(0, 0).dynamicInputs();
        model.settle(new SyncWindow(3, 5));
        assertEquals(expected, model.acknowledged(new SyncWindow(3, 5)).column(0, 0).dynamicInputs());
    }

    @Test
    void replacementUnloadAndWorldEpochCannotReuseOldInputs() {
        var registry = BlockRegistry.get(); var model = new WorldModel(registry);
        int piston = registry.block("minecraft:moving_piston").firstStateId();
        var nbt = new BlockEntityData(1, 2, 3, "minecraft:piston", new byte[]{10, 0});
        model.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 16, 2)));
        model.sent(1, Wire.of(chunk(piston, List.of(nbt))));
        assertEquals(List.of(nbt), model.acknowledged(new SyncWindow(1, 2)).column(0, 0).dynamicInputs());
        model.sent(2, Wire.of(chunk(piston, List.of())));
        assertTrue(model.acknowledged(new SyncWindow(2, 3)).column(0, 0).dynamicInputs().isEmpty());
        model.sent(3, Wire.of(new ForgetChunk(0, 0))); model.sent(4, Wire.of(nbt));
        assertNull(model.acknowledged(new SyncWindow(4, 5)).column(0, 0));
        model.sent(5, Wire.of(chunk(piston, List.of())));
        assertTrue(model.acknowledged(new SyncWindow(5, 6)).column(0, 0).dynamicInputs().isEmpty());
        // MovingPistonBlock.newBlockEntity returns null: chunk NBT cannot create an animation.
        assertFalse(model.acknowledged(new SyncWindow(5, 6)).dynamicApplicationUnresolved());
        assertNull(model.acknowledged(new SyncWindow(5, 6)).piston(new BlockPosition(1, 2, 3)));
        model.sent(6, Wire.of(new Respawn("minecraft:the_nether", 0, 0, 16)));
        assertFalse(model.acknowledged(new SyncWindow(6, 7)).dynamicApplicationUnresolved());
    }

    @Test
    void onlyObservedMarkerAcknowledgementMakesEventsCertain() {
        var session = new MacPlayer(new FrameListener() {
            public void betweenTicks(BetweenTicks event) { }
            public void frame(ClientTickFrame frame) { }
        });
        int shulker = session.world().registry().block("minecraft:shulker_box").firstStateId();
        Wire.send(session, new JoinGame(1, "minecraft:overworld", 0, 16, 2), 0);
        Wire.send(session, chunk(shulker, List.of()), 1);
        Wire.send(session, BundleDelimiter.INSTANCE, 2);
        assertTrue(Wire.send(session, new ru.minecomplex.mac.fixture.outbound.BlockEvent(1, 2, 3, 1, 1, 0), 3));
        assertFalse(session.canWriteMarker());
        Wire.receive(session, new KeepAlive(99), 4); Wire.receive(session, new ChunkBatchReceived(1), 5);
        assertEquals(-1, session.currentWindow().certainThrough());
        Wire.send(session, BundleDelimiter.INSTANCE, 6); assertTrue(session.canWriteMarker());
        int marker = session.allocateMarker(7); Wire.send(session, new Ping(marker), 8);
        assertEquals(-1, session.currentWindow().certainThrough());
        Wire.receive(session, new Pong(marker), 9);
        var world = session.world().acknowledged(session.currentWindow());
        assertEquals(1, world.column(0, 0).dynamicInputs().size());
        assertFalse(world.dynamicApplicationUnresolved());
        assertTrue(world.dynamicGaps().contains(ru.minecomplex.mac.engine.Unmodeled.SHULKER_TICK_TIMING));
    }

    @Test
    void discardedHistoryCannotCertifyDynamicPhysics() {
        var registry = BlockRegistry.get(); var model = new WorldModel(registry);
        model.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 16, 2)));
        model.sent(1, Wire.of(chunk(registry.block("minecraft:shulker_box").firstStateId(), List.of())));
        for (int i = 2; i < 302; i++) model.sent(i, Wire.of(new BlockEvent(1, 2, 3, 1, i & 1, 0)));
        var world = model.acknowledged(new SyncWindow(301, 302));
        assertTrue(world.column(0, 0).dynamicInputsTruncated());
        assertFalse(world.dynamicApplicationUnresolved());
        assertTrue(world.dynamicGaps().contains(ru.minecomplex.mac.engine.Unmodeled.SHULKER_TICK_TIMING));
        var env = new ru.minecomplex.mac.engine.MovementEnvironment() {
            public BlockSource blocks() { return world; }
            public BlockRegistry registry() { return registry; }
            public BlockTags tags() { return BlockTags.NONE; }
            public boolean hasChunkAt(int x, int z) { return world.isLoaded(x >> 4, z >> 4); }
            public int minY() { return world.minY(); }
            public List<ru.minecomplex.mac.collision.VoxelShape> entityCollisions(ru.minecomplex.mac.collision.Aabb box) { return List.of(); }
            public ru.minecomplex.mac.collision.VoxelShape borderShape(ru.minecomplex.mac.collision.Aabb box) { return null; }
        };
        var physics = new ru.minecomplex.mac.engine.movementtick.MovementTicker(env, new ru.minecomplex.mac.player.state.PlayerState());
        assertTrue(physics.unmodeled().contains(ru.minecomplex.mac.engine.Unmodeled.SHULKER_TICK_TIMING));
        physics.clearUnmodeled();
        assertTrue(physics.unmodeled().contains(ru.minecomplex.mac.engine.Unmodeled.SHULKER_TICK_TIMING));
    }
}
