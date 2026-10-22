package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.List;

import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.util.collection.LongMap;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;

/**
 * Chained full LocalPlayer movement and send state; never reset physics from an expected after-state.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LocalPlayerClimbingOracleTest {

    @Test
    void chainedTick() throws IOException {
        var trace = LocalTickOracle.read(false, "local-climbing-ticks");
        var registry = BlockRegistry.get();
        int count = 0;
        for (var scenario : trace.scenarios()) {
            var initial = scenario.initial().base();
            PlayerState player = initial.player().toState();
            player.pose = Pose.valueOf(initial.pose());
            player.swimming = initial.swimming();
            player.fallFlying = initial.fallFlying();
            player.abilities.mayfly = scenario.mayfly();
            player.abilities.flying = initial.flying();
            var local = new LocalPlayerState();
            local.input = initial.input();
            local.vector = initial.vector();
            local.crouching = initial.crouching();
            local.jumpTriggerTime = initial.jumpTriggerTime();
            local.sprintTriggerTime = initial.sprintTriggerTime();
            local.autoJumpTime = initial.autoJumpTime();
            local.send = initial.sendState();
            local.send.wasShiftKeyDown = scenario.initial().wasShift();
            var playerData = FixturePlayers.loaded();
            var physics = new MovementTickerPlayer(environment(registry, scenario.blocks()), player, local, playerData);
            int rawPrevious = 0, effective = 0;
            for (var tick : scenario.ticks()) {
                int raw = tick.rawKeys();
                for (int key = 0; key < 7; key++) {
                    int bit = 1 << key;
                    boolean toggle = key == 5 && scenario.toggleCrouch() || key == 6 && scenario.toggleSprint();
                    if (toggle) {
                        if ((raw & bit) != 0 && (rawPrevious & bit) == 0)
                            effective ^= bit;
                    } else
                        effective = effective & ~bit | raw & bit;
                }
                rawPrevious = raw;
                player.yRot = tick.yaw();
                player.xRot = tick.pitch();
                playerData.food = scenario.initial().food();
                local.autoJumpOption = scenario.autoJump();
                local.sprintWindow = scenario.sprintWindow();
                var sendBefore = local.send.copy();
                var gaps = physics.simulateTick(PlayerInput.fromMask(effective));
                {
                    assertEquals(sendBefore.lastSentInput, local.send.lastSentInput, "Input send phase");
                    assertEquals(sendBefore.positionReminder, local.send.positionReminder, "Position send phase");
                    assertTrue(physics.packets().stream().noneMatch(p -> p instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket || p instanceof net.minecraft.network.protocol.game.ServerboundPlayerInputPacket));
                    gaps = physics.sendAfterEntityTicks();
                }
                var after = tick.after().base();
                String context = "26.3 " + scenario.family() + " tick " + count;
                assertTrue(gaps.isEmpty(), context + " " + gaps);
                assertEquals(List.of(), after.player().differences(MovementOracleTest.Recorded.of(player)), context);
                assertEquals(after.pose(), player.pose.name(), context + " pose");
                assertEquals(after.crouching(), local.crouching, context + " crouching");
                assertEquals(after.flying(), player.abilities.flying, context + " flying");
                assertEquals(after.jumpTriggerTime(), local.jumpTriggerTime, context + " jump timer");
                assertEquals(after.sprintTriggerTime(), local.sprintTriggerTime, context + " sprint timer");
                assertEquals(after.autoJumpTime(), local.autoJumpTime, context + " autojump timer");
                assertEquals(after.input(), local.input, context + " input");
                bits(after.vector().x(), local.vector.x(), context + " input x");
                bits(after.vector().y(), local.vector.y(), context + " input y");
                bits(after.xxa(), player.xxa, context + " xxa");
                bits(after.zza(), player.zza, context + " zza");
                assertEquals(after.swimming(), player.swimming, context + " swimming");
                assertEquals(after.fallFlying(), player.fallFlying, context + " fall flying");
                assertEquals(tick.after().wasFallFlying(), local.wasFallFlying, context + " previous glide");
                assertEquals(tick.after().waterVision(), local.waterVisionTime, context + " water vision");
                assertEquals(tick.after().ridingTicks(), local.jumpRidingTicks, context + " riding timer");
                bits(tick.after().ridingScale(), local.jumpRidingScale, context + " riding scale");
                assertEquals(tick.packets(), Wire.in(physics.packets()), context + " packets");
                count++;
            }
        }
        assertEquals(2880, count);
    }

    private static void bits(float expected, float actual, String context) {
        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual), context);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    static MovementEnvironment environment(BlockRegistry registry, List<LocalTickOracle.Block> entries) {
        var map = new LongMap<Integer>();
        for (var block : entries) map.put(key(block.x(), block.y(), block.z()), block.state());
        return new MovementEnvironment() {

            public BlockSource blocks() {
                return (x, y, z) -> {
                    var state = map.get(key(x, y, z));
                    return state == null ? 0 : state;
                };
            }

            public BlockRegistry registry() {
                return registry;
            }

            public BlockTags tags() {
                var ids = new java.util.EnumMap<ru.minecomplex.mac.world.BlockTag, int[]>(ru.minecomplex.mac.world.BlockTag.class);
                ids.put(ru.minecomplex.mac.world.BlockTag.CLIMBABLE, new int[] { registry.block("minecraft:ladder").registryId(), registry.block("minecraft:vine").registryId(), registry.block("minecraft:scaffolding").registryId() });
                ids.put(ru.minecomplex.mac.world.BlockTag.CAN_GLIDE_THROUGH, new int[] { registry.block("minecraft:vine").registryId() });
                return new ru.minecomplex.mac.world.ResolvedBlockTags(ids);
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
    }
}
