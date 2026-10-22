package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import ru.minecomplex.mac.fixture.outbound.BlockTagsUpdate;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.Respawn;
import ru.minecomplex.mac.replay.RecordingBuffer;
import ru.minecomplex.mac.replay.ReplayFormat;
import ru.minecomplex.mac.player.transaction.MarkerPolicy;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.replica.WorldModel;

/**
 * The connection's block tags as the client received them ({@code update_tags} in configuration and play,
 * {@code handleUpdateTags}): the configuration's before the login, a play update when handled, kept by a new level.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BlockTagsSyncTest {
    private static final BlockRegistry REGISTRY = BlockRegistry.get();

    private static BlockTagsUpdate tags(String... climbable) {
        Map<String, int[]> tags = new LinkedHashMap<>();
        tags.put("minecraft:climbable", java.util.Arrays.stream(climbable).mapToInt(name -> REGISTRY.block(name).registryId()).toArray());
        tags.put("minecraft:mineable/pickaxe", new int[]{REGISTRY.block("minecraft:stone").registryId()});
        return BlockTagsUpdate.of(tags);
    }

    private static boolean climbable(CompensatedWorld world, String block) {
        return world.tags().contains(BlockTag.CLIMBABLE, REGISTRY.block(block));
    }

    @Test
    void theUpdateKeepsEveryTagAndResolvesTheModelledOnes() {
        BlockTagsUpdate update = tags("minecraft:ladder", "minecraft:vine");
        assertEquals(2, update.tags().size());
        assertEquals(2, update.tags().get("minecraft:climbable").length);
        assertTrue(update.resolve().contains(BlockTag.CLIMBABLE, REGISTRY.block("minecraft:ladder")));
        assertFalse(update.resolve().contains(BlockTag.CLIMBABLE, REGISTRY.block("minecraft:stone")));
        // a tag the update does not list is empty on the client
        assertFalse(update.resolve().contains(BlockTag.BLOCKS_MOTION_IN_HEIGHTMAP, REGISTRY.block("minecraft:stone")));
        assertEquals(BlockTagsUpdate.of(Map.of()), BlockTagsUpdate.of(Map.of()));
        assertTrue(BlockTagsUpdate.of(Map.of()).tags().isEmpty());
        assertTrue(MarkerPolicy.markerAfter(Wire.of(update), 1, Wire.FACTS.localUuid()));
        assertTrue(WorldModel.isWorldChange(Wire.of(update)));
    }

    @Test
    void theConfigurationTagsComeBeforeTheLoginAndAPlayUpdateWhenHandled() {
        var model = new WorldModel(REGISTRY);
        model.sent(0, Wire.of(tags("minecraft:ladder")));
        model.sent(1, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 32, 2)));
        model.sent(2, Wire.of(tags("minecraft:vine")));
        model.sent(3, Wire.of(new Respawn("minecraft:the_nether", 0, 0, 32)));
        try (var h = model.hypothesis(new SyncWindow(1, 4), 1)) {
            assertTrue(climbable(h.world(), "minecraft:ladder"), "the login's level has the configuration's tags");
            assertFalse(climbable(h.world(), "minecraft:vine"));
            h.apply(new SyncWindow(1, 4), 2);
            assertTrue(climbable(h.world(), "minecraft:vine"));
            assertFalse(climbable(h.world(), "minecraft:ladder"), "an update replaces the tags");
            h.apply(new SyncWindow(1, 4), 3);
            assertEquals("minecraft:the_nether", h.world().dimension());
            assertTrue(climbable(h.world(), "minecraft:vine"), "a new level keeps them");
        }
        // without any update_tags: none (the probe's own default stands in)
        var bare = new WorldModel(REGISTRY);
        bare.sent(0, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 32, 2)));
        try (var h = bare.hypothesis(new SyncWindow(0, 1), 0)) {
            assertNull(h.world().tags());
        }
    }

    @Test
    void aRecordingKeepsTheUpdate() throws Exception {
        var buffer = new RecordingBuffer(1000);
        BlockTagsUpdate update = tags("minecraft:ladder", "minecraft:scaffolding");
        var session = new ru.minecomplex.mac.player.MacPlayer(new ru.minecomplex.mac.frame.FrameListener() {
                    public void frame(ru.minecomplex.mac.frame.ClientTickFrame frame) { }
                    public void betweenTicks(ru.minecomplex.mac.frame.BetweenTicks event) { }
                });
        session.record(buffer);
        Wire.send(session, update, 0L);
        var bytes = new ByteArrayOutputStream();
        ReplayFormat.write(buffer.snapshot("tags"), bytes);
        var read = ReplayFormat.read(new ByteArrayInputStream(bytes.toByteArray()));
        var facts = Wire.FACTS;
        assertTrue(read.entries().stream().anyMatch(entry -> entry instanceof ru.minecomplex.mac.replay.Recording.Packet packet
                && update.equals(ru.minecomplex.mac.fixture.FixtureCodec.encodeOutbound(ru.minecomplex.mac.packet.PacketDecoder.decode(
                        new ru.minecomplex.mac.replay.ReplayConnection(facts).send(packet)), facts))), read.entries().toString());
    }
}
