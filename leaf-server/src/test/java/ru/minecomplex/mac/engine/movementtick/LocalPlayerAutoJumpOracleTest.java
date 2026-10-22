package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.fixture.FixturePlayers;
import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.effect.MobEffect;
import ru.minecomplex.mac.entity.Pose;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec2f;
import ru.minecomplex.mac.util.math.Vec3d;

/** Direct vanilla 26.3 auto-jump oracle. Includes non-default dimensions, jump effects and headroom. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LocalPlayerAutoJumpOracleTest {

    @Test
    void autoJump() throws IOException {
        String path = "/vanilla/777/local-autojump.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        int scheduled = 0, boosted = 0;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414341, in.readInt()); assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            int count = in.readInt(); assertEquals(2048, count);
            for (int i = 0; i < count; i++) {
                var player = new PlayerState(); var local = new LocalPlayerState();
                player.position = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                player.yRot = in.readFloat(); player.xRot = in.readFloat();
                player.attributes.get(Attribute.SCALE).setBaseValue(in.readFloat());
                player.pose = Pose.valueOf(in.readUTF()); player.onGround = in.readBoolean();
                player.refreshDimensions(); // the generator refreshes after scale and pose
                local.send.autoJumpEnabled = in.readBoolean(); local.autoJumpTime = in.readInt();
                local.input = PlayerInput.fromMask(in.readUnsignedByte()); player.shiftKeyDown = local.input.shift();
                local.vector = new Vec2f(in.readFloat(), in.readFloat());
                player.attributes.get(Attribute.MOVEMENT_SPEED).setBaseValue(in.readFloat());
                int amplifier = in.readInt();
                if (amplifier >= 0) player.effects.set(MobEffect.JUMP_BOOST, amplifier);
                float xa = in.readFloat(), za = in.readFloat();
                int blockCount = in.readInt(); var blocks = new ArrayList<LocalTickOracle.Block>();
                for (int b = 0; b < blockCount; b++)
                    blocks.add(new LocalTickOracle.Block(in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                int expected = in.readInt();
                var physics = new MovementTickerPlayer(LocalPlayerPhysicsOracleTest.environment(BlockRegistry.get(), blocks), player, local, FixturePlayers.loaded());
                physics.updateAutoJump(xa, za);
                assertTrue(physics.unmodeled().isEmpty(), "26.3 sample " + i);
                assertEquals(expected, local.autoJumpTime, "26.3 sample " + i);
                if (expected == 1) { scheduled++; if (amplifier >= 0) boosted++; }
            }
            assertEquals(-1, in.read());
        }
        assertTrue(scheduled > 0, "Oracle schedules jumps");
        assertTrue(boosted > 0, "Oracle schedules jumps with jump effects");
    }
}
