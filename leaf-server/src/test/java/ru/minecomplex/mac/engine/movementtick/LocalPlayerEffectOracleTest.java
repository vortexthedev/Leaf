package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.fixture.FixturePlayers;
import ru.minecomplex.mac.Wire;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.effect.EffectInstance;
import ru.minecomplex.mac.entity.effect.MobEffect;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.world.BlockRegistry;
import static org.junit.jupiter.api.Assertions.*;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LocalPlayerEffectOracleTest {

    @Test
    void chainedTick() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/local-effect-ticks.bin.gz");
        assertNotNull(raw, "26.3");
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414c45, in.readInt()); assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(12, in.readInt());
            for (int scenario = 0; scenario < 12; scenario++) {
                var effect = MobEffect.valueOf(in.readUTF()); int stone = in.readInt();
                var initial = LocalTickOracle.State.read(in); var base = initial.base();
                PlayerState player = base.player().toState();
                var local = new LocalPlayerState();
                local.input = base.input(); local.vector = base.vector(); local.send = base.sendState();
                local.send.wasShiftKeyDown = initial.wasShift();
                player.effects.replace(effect, new EffectInstance(19, 0, false, true, true));
                player.effects.add(effect, new EffectInstance(3, 5, false, true, true));
                player.effects.add(effect, new EffectInstance(7, 2, false, true, true));
                var blocks = new ArrayList<LocalTickOracle.Block>();
                for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) blocks.add(new LocalTickOracle.Block(x, 63, z, stone));
                var playerData = FixturePlayers.loaded();
                var physics = new MovementTickerPlayer(LocalPlayerPhysicsOracleTest.environment(BlockRegistry.get(), blocks), player, local, playerData);
                assertEquals(40, in.readInt());
                for (int tick = 0; tick < 40; tick++) {
                    boolean jump = in.readBoolean(), remove = in.readBoolean();
                    if (remove) player.effects.remove(effect);
                    var expected = LocalTickOracle.State.read(in);
                    var packets = new ArrayList<InboundEvent>();
                    int count = in.readInt(); for (int p = 0; p < count; p++) packets.add(LocalPlayerOracleCodec.packet(in));
                    playerData.food = initial.food();
                    local.autoJumpOption = false;
                    local.sprintWindow = 7;
                    var gaps = physics.tick(PlayerInput.fromMask(1 | (jump ? 16 : 0)));
                    String context = "26.3 scenario " + scenario + " tick " + tick;
                    assertTrue(gaps.isEmpty(), context + gaps);
                    assertEquals(List.of(), expected.base().player().differences(MovementOracleTest.Recorded.of(player)), context);
                    assertEquals(packets, Wire.in(physics.packets()), context);
                    assertEquals(in.readBoolean(), player.effects.has(effect), context);
                    if (player.effects.has(effect)) {
                        assertEquals(in.readInt(), player.effects.instance(effect).duration(), context);
                        assertEquals(in.readInt(), player.effects.amplifier(effect), context);
                    }
                }
            }
            assertEquals(-1, in.read());
        }
    }
}
