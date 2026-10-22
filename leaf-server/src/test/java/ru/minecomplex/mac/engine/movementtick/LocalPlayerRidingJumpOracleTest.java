package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.player.state.LocalPlayerState;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LocalPlayerRidingJumpOracleTest {

    @Test
    void ridingJump() throws IOException {
        var raw = getClass().getResourceAsStream("/vanilla/777/local-riding-jump.bin.gz");
        assertNotNull(raw, "26.3");
        int charges = 0;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d41434a, in.readInt()); assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); int count = in.readInt(); assertEquals(4096, count);
            var state = new LocalPlayerState();
            var ticker = new MovementTickerPlayer(LocalPlayerFluidOracleTest.environment(
                    ru.minecomplex.mac.world.BlockRegistry.get(), java.util.List.of()),
                    new ru.minecomplex.mac.player.state.PlayerState(), state, ru.minecomplex.mac.fixture.FixturePlayers.loaded());
            for (int i = 0; i < count; i++) {
                boolean oldJump = in.readBoolean(), jump = in.readBoolean(), available = in.readBoolean();
                int cooldown = in.readInt();
                state.jumpRidingTicks = in.readInt(); state.jumpRidingScale = in.readFloat();
                int charge = ticker.updateRidingJump(oldJump, jump, available, cooldown);
                var result = charge == MovementTickerPlayer.NO_RIDING_JUMP ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(charge);
                assertEquals(in.readInt(), state.jumpRidingTicks, "26.3 sample " + i + " timer");
                assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(state.jumpRidingScale), "26.3 sample " + i + " scale");
                int callbacks = in.readInt(); assertEquals(result.isPresent() ? 1 : 0, callbacks, "26.3 sample " + i);
                if (callbacks != 0) { assertEquals(in.readInt(), result.orElseThrow()); charges++; }
                int packets = in.readInt(); assertEquals(callbacks, packets, "26.3 sample " + i);
                if (packets != 0) {
                    assertEquals("START_RIDING_JUMP", in.readUTF());
                    assertEquals(in.readInt(), result.orElseThrow());
                }
            }
            assertEquals(-1, in.read());
        }
        assertTrue(charges > 0, "Oracle releases a charged jump");
    }
}
