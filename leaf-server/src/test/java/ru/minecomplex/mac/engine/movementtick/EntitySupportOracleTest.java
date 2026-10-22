package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.DataInputStream;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.replica.CompensatedEntities;
import ru.minecomplex.mac.entity.EntityMotions;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;

/**
 * MAES (EntitySupportSamples): a real LocalPlayer standing, walking, jumping, sprinting and sneaking on and against a
 * raft, boats and a happy ghast, whose real {@code getEntityCollisions} sees them. {@link MovementTickerPlayer}, with the
 * entity collisions {@link EntityMotions} gives the probe, must reproduce every tick bit-exact (supporting block, friction,
 * jump, step-up, edge back-off, ghast top rule).
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class EntitySupportOracleTest {

    static final String[] KINDS = { "minecraft:oak_boat", "minecraft:bamboo_raft", "minecraft:oak_chest_boat", "minecraft:happy_ghast" };

    static final int PLAYER = 1;

    @Test
    void movesOnEntitiesLikeVanilla() throws Exception {
        var registry = BlockRegistry.get();
        var types = EntityTypes.get();
        String path = "/vanilla/777/entity-support.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        int count = 0, onEntity = 0;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414553, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            in.readUTF();
            for (int scenarios = in.readInt(), s = 0; s < scenarios; s++) {
                var entities = new CompensatedEntities(PLAYER);
                int entityCount = in.readInt();
                for (int i = 0; i < entityCount; i++) {
                    int kind = in.readUnsignedByte();
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    int flags = in.readUnsignedByte();
                    entities.spawn(Wire.of(new EntitySpawn(100 + i, KINDS[kind], x, y, z, 0.0F, 0.0F, 0.0F, 0, false, 0, 0, 0)), chunk -> false);
                    if ((flags & 4) != 0) {
                        entities.syncedData(Wire.of(new EntitySyncedData(100 + i, 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(Map.of(types.dataId("HappyGhast.STAYS_STILL"), true)))));
                    }
                }
                var motions = entities.motions();
                var scenario = LocalTickOracle.readScenario(in, false, false);
                var initial = scenario.initial().base();
                PlayerState player = initial.player().toState();
                player.pose = Pose.valueOf(initial.pose());
                var local = new LocalPlayerState();
                local.input = initial.input();
                local.vector = initial.vector();
                local.crouching = initial.crouching();
                local.jumpTriggerTime = initial.jumpTriggerTime();
                local.sprintTriggerTime = initial.sprintTriggerTime();
                local.autoJumpTime = initial.autoJumpTime();
                local.send = initial.sendState();
                local.send.wasShiftKeyDown = scenario.initial().wasShift();
                var collisionGaps = EnumSet.noneOf(Unmodeled.class);
                var blocks = LocalPlayerPhysicsOracleTest.environment(registry, scenario.blocks());
                MovementEnvironment env = new MovementEnvironment() {

                    public BlockSource blocks() {
                        return blocks.blocks();
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
                        return motions.collisions(box, EntityMotions.Collider.player(PLAYER, player.position), null, collisionGaps);
                    }

                    public VoxelShape borderShape(Aabb box) {
                        return null;
                    }
                };
                var playerData = FixturePlayers.loaded();
                var physics = new MovementTickerPlayer(env, player, local, playerData);
                for (var tick : scenario.ticks()) {
                    player.yRot = tick.yaw();
                    player.xRot = tick.pitch();
                    playerData.food = scenario.initial().food();
                    local.autoJumpOption = false;
                    local.sprintWindow = 7;
                    var gaps = physics.simulateTick(PlayerInput.fromMask(tick.rawKeys()));
                    {
                        gaps = physics.sendAfterEntityTicks();
                    }
                    var after = tick.after().base();
                    String context = "26.3 " + scenario.family() + " scenario " + s + " tick " + count;
                    assertTrue(gaps.isEmpty(), context + " " + gaps);
                    assertEquals(Set.of(), collisionGaps, context);
                    assertEquals(List.of(), after.player().differences(MovementOracleTest.Recorded.of(player)), context);
                    assertEquals(after.pose(), player.pose.name(), context + " pose");
                    assertEquals(after.crouching(), local.crouching, context + " crouching");
                    assertEquals(after.jumpTriggerTime(), local.jumpTriggerTime, context + " jump timer");
                    assertEquals(after.sprintTriggerTime(), local.sprintTriggerTime, context + " sprint timer");
                    assertEquals(tick.packets(), Wire.in(physics.packets()), context + " packets");
                    if (player.onGround && player.onGroundNoBlocks)
                        onEntity++;
                    count++;
                }
            }
            assertEquals(-1, in.read());
        }
        assertTrue(onEntity > 300, "26.3 ticks standing on an entity: " + onEntity);
    }
}
